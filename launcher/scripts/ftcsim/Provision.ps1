# Provisioning: installs the pinned JDK into the FTC-Sim cache when discovery finds nothing usable.
#
# Pipeline, all under one exclusive lock so two launches never corrupt the cache:
#   look again (another launch may have finished) -> sweep leftovers -> ask the release API -> check disk space
#   -> download (resumable) -> verify SHA-256 -> unpack into a staging folder on the same volume
#   -> prove the unpacked JDK runs -> record what was installed -> atomic rename into jdks\<vendor>-<version>-<sha8>.
# A crash at any point leaves only staging leftovers, which the next launch sweeps; jdks\ only ever holds finished installs.

function Get-CachePaths {
    param([string]$CacheRoot)
    # Join-Path would insist that the drive exists, which hides the real reason from the message below.
    [pscustomobject]@{
        Root  = $CacheRoot
        Jdks  = [System.IO.Path]::Combine($CacheRoot, 'jdks')
        Tmp   = [System.IO.Path]::Combine($CacheRoot, 'tmp')
        Locks = [System.IO.Path]::Combine($CacheRoot, 'locks')
    }
}

# Windows only takes paths longer than 259 characters with the \\?\ prefix, and its machine-wide long path setting is off by
# default. The prefix switches Windows' own path cleaning off, so the path is made absolute and clean first. -Always is for
# a folder whose contents may be longer than the folder itself, such as a tree that is about to be deleted.
function ConvertTo-ExtendedPath {
    param([string]$Path, [switch]$Always)
    $full = [System.IO.Path]::GetFullPath($Path)
    if ((-not $Always -and $full.Length -lt 240) -or $full.StartsWith('\\?\')) { return $full }
    if ($full.StartsWith('\\')) { return '\\?\UNC\' + $full.Substring(2) }
    '\\?\' + $full
}

function Remove-PathQuietly {
    param([string]$Path)
    try {
        $target = ConvertTo-ExtendedPath $Path -Always
        if ([System.IO.Directory]::Exists($target)) { [System.IO.Directory]::Delete($target, $true) }
        elseif ([System.IO.File]::Exists($target)) { [System.IO.File]::Delete($target) }
    } catch {
        try {
            # Read-only files block deletion; clear the attribute and try once more.
            foreach ($item in @(Get-ChildItem -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue)) { $item.Attributes = 'Normal' }
            if ([System.IO.Directory]::Exists($target)) { [System.IO.Directory]::Delete($target, $true) }
            elseif ([System.IO.File]::Exists($target)) { [System.IO.File]::Delete($target) }
        } catch {
            Write-Verbose "Could not remove ${Path}: $($_.Exception.Message)"
        }
    }
}

function Enter-CacheLock {
    param([string]$LockPath, [int]$TimeoutSeconds)

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextNotice = [DateTime]::MinValue
    while ($true) {
        try {
            return [System.IO.File]::Open($LockPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        } catch [System.IO.IOException] {
            # 32 = sharing violation, 33 = lock violation: somebody else holds it. The OS frees it if that process dies.
            if (($_.Exception.HResult -band 0xFFFF) -notin 32, 33) { throw }
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            $waited = "$([int]($TimeoutSeconds / 60)) minutes"
            if ($TimeoutSeconds -lt 60) { $waited = "$TimeoutSeconds seconds" }
            Stop-Ftc 'LockTimeout' "Another FTC-Sim window has been preparing the Java runtime for more than $waited."
        }
        if ([DateTime]::UtcNow -ge $nextNotice) {
            Write-FtcInfo '  Another FTC-Sim window is preparing the Java runtime; waiting for it to finish...'
            $nextNotice = [DateTime]::UtcNow.AddSeconds(30)
        }
        Start-Sleep -Milliseconds 500
    }
}

function Assert-FreeSpace {
    param([string]$Path, [long]$RequiredBytes)
    try {
        $free = (New-Object System.IO.DriveInfo([System.IO.Path]::GetPathRoot($Path))).AvailableFreeSpace
    } catch {
        return   # a network path or an unusual root: let the write itself fail if there is no room
    }
    if ($free -lt $RequiredBytes) {
        Stop-Ftc 'DiskFull' "FTC-Sim needs about $(Format-Megabytes $RequiredBytes) free for its Java runtime but only $(Format-Megabytes $free) is free on the drive holding $Path."
    }
}

# Everything in tmp\ is a leftover when we hold the lock, except the partial download that can be resumed.
function Clear-StaleArtifacts {
    param($Paths, [string]$KeepName)
    foreach ($leftover in @(Get-ChildItem -LiteralPath $Paths.Tmp -Force -ErrorAction SilentlyContinue)) {
        if ($leftover.Name -ne $KeepName) { Remove-PathQuietly $leftover.FullName }
    }
    foreach ($broken in @(Get-ChildItem -LiteralPath $Paths.Jdks -Directory -Force -ErrorAction SilentlyContinue)) {
        if ($broken.Name -match '\.broken-') { Remove-PathQuietly $broken.FullName }
    }
}

function Expand-UrlTemplate {
    param([string]$Template, [hashtable]$Values)
    foreach ($key in $Values.Keys) { $Template = $Template.Replace('{' + $key + '}', [string]$Values[$key]) }
    $Template
}

# A missing property is no value instead of an error, so an unexpected answer becomes a MetadataInvalid message.
function Get-JsonField {
    param($Object, [string]$Name)
    if ($Object -and $Object.PSObject.Properties[$Name]) { $Object.PSObject.Properties[$Name].Value }
}

# Understands both Adoptium shapes: the 'latest' list (one 'binary' per release) and the 'release_name' object (several 'binaries').
# Whatever else the service offers, the asset must be the manifest's operating system, architecture and image type.
function Select-JdkAsset {
    param($Manifest, $Document)

    $runtime = $Manifest.runtime
    $offered = @()
    foreach ($item in @($Document)) {
        $binary = Get-JsonField $item 'binary'
        if ($binary) { $candidates = @($binary); $version = Get-JsonField $item 'version' }
        else { $candidates = @(Get-JsonField $item 'binaries'); $version = Get-JsonField $item 'version_data' }
        foreach ($candidate in $candidates) {
            $os = Get-JsonField $candidate 'os'; $architecture = Get-JsonField $candidate 'architecture'; $imageType = Get-JsonField $candidate 'image_type'
            $offered += "$os/$architecture $imageType"
            if ($os -eq $runtime.os -and $architecture -eq $runtime.architecture -and $imageType -eq $runtime.imageType -and (Get-JsonField $candidate 'package')) {
                return [pscustomobject]@{ Item = $item; Binary = $candidate; Version = $version }
            }
        }
    }
    if ($offered.Count -eq 0) { Stop-Ftc 'MetadataInvalid' 'The release service answered with a document FTC-Sim does not understand.' }
    Stop-Ftc 'MetadataInvalid' "The release service offered no $($runtime.os)/$($runtime.architecture) $($runtime.imageType) package, only: $($offered -join ', ')."
}

function ConvertTo-JdkRelease {
    param($Manifest, $Document)

    $runtime = $Manifest.runtime
    $asset = Select-JdkAsset $Manifest $Document
    $package = Get-JsonField $asset.Binary 'package'
    $sha256 = ([string](Get-JsonField $package 'checksum')).ToLowerInvariant()
    if ($sha256 -notmatch '^[0-9a-f]{64}$') { Stop-Ftc 'MetadataInvalid' 'The release metadata has no valid SHA-256 checksum.' }
    $size = [long](Get-JsonField $package 'size')
    $fileName = [string](Get-JsonField $package 'name')
    if ($size -le 0 -or $fileName -notmatch '^[A-Za-z0-9._+-]+\.zip$') {
        Stop-Ftc 'MetadataInvalid' 'The release metadata does not describe a zip package.'
    }
    $link = [string](Get-JsonField $package 'link')
    $url = $null
    if (-not [Uri]::TryCreate($link, [UriKind]::Absolute, [ref]$url) -or -not (Test-OriginAllowed $Manifest $url)) {
        Stop-Ftc 'MetadataInvalid' "The release metadata points at a download address that is not allowed: $link"
    }
    $releaseName = [string](Get-JsonField $asset.Item 'release_name')
    $major = [int](Get-JsonField $asset.Version 'major')
    $openJdkVersion = [string](Get-JsonField $asset.Version 'openjdk_version')
    if (-not $releaseName -or $major -ne [int]$runtime.major -or $openJdkVersion -notmatch '^(\d+(?:\.\d+)*)\+(\d+)') {
        Stop-Ftc 'MetadataInvalid' "The release metadata describes Java $major ($openJdkVersion), not the pinned Java $($runtime.major)."
    }
    $versionText = "$($Matches[1])+$($Matches[2])"
    if ($runtime.exactRelease -and $releaseName -ne $runtime.exactRelease) {
        Stop-Ftc 'MetadataInvalid' "The release service returned $releaseName but the manifest pins $($runtime.exactRelease)."
    }
    if ($runtime.exactSha256 -and $sha256 -ne ([string]$runtime.exactSha256).ToLowerInvariant()) {
        Stop-Ftc 'MetadataInvalid' "The release checksum $sha256 differs from the checksum pinned in the manifest."
    }
    $directoryName = '{0}-{1}-{2}' -f $runtime.vendor, $versionText.Replace('+', '_'), $sha256.Substring(0, 8)
    if ($directoryName -notmatch '^[A-Za-z0-9._-]+$') { Stop-Ftc 'MetadataInvalid' "The release would install into an unsafe folder name: $directoryName" }
    [pscustomobject]@{
        Name = $releaseName; Version = $versionText; Url = $url; Sha256 = $sha256; Size = $size
        FileName = $fileName; DirectoryName = $directoryName
    }
}

function Get-JdkRelease {
    param($Manifest)
    $runtime = $Manifest.runtime
    $values = @{ major = $runtime.major; architecture = $runtime.architecture; os = $runtime.os; imageType = $runtime.imageType }
    $path = $Manifest.source.latestPath
    if ($runtime.exactRelease) {
        $values.release = [Uri]::EscapeDataString($runtime.exactRelease)
        $path = $Manifest.source.releasePath
    }
    $uri = [Uri]($Manifest.source.apiBase.TrimEnd('/') + (Expand-UrlTemplate $path $values))
    $notFound = $null
    if ($runtime.exactRelease) {
        $notFound = "The release service no longer offers the runtime release $($runtime.exactRelease) that this FTC-Sim is pinned to."
    }
    $document = Get-JsonDocument $Manifest $uri $notFound
    try {
        ConvertTo-JdkRelease $Manifest $document
    } catch {
        if ((Get-FtcKind $_) -ne 'Unexpected') { throw }
        Stop-Ftc 'MetadataInvalid' "The release service answered with something FTC-Sim does not understand ($($_.Exception.Message))." $_.Exception
    }
}

function Get-StreamSha256 {
    param([System.IO.Stream]$Stream)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = $algorithm.ComputeHash($Stream) } finally { $algorithm.Dispose() }
    ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

# Downloads and verifies the archive, then returns it open for reading with writers excluded, so the bytes that were
# checked are the bytes that get unpacked. A checksum failure after a resume is retried once from scratch.
function Open-VerifiedArchive {
    param($Manifest, $Release, [string]$ArchivePath)

    for ($round = 1; ; $round++) {
        Write-FtcInfo "  Downloading $($Release.FileName) ($(Format-Megabytes $Release.Size))"
        $resumed = Save-AllowedFile $Manifest $Release.Url $ArchivePath $Release.Size $Release.FileName
        Write-FtcInfo '  Verifying the download...'
        $stream = [System.IO.File]::Open($ArchivePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        if ((Get-StreamSha256 $stream) -eq $Release.Sha256) {
            [void]$stream.Seek(0, [System.IO.SeekOrigin]::Begin)
            return $stream
        }
        $stream.Dispose()
        Remove-PathQuietly $ArchivePath
        if ($round -ge 2 -or -not $resumed) {
            Stop-Ftc 'ChecksumMismatch' "The downloaded $($Release.FileName) does not match the published SHA-256 $($Release.Sha256)."
        }
        Write-FtcInfo '  The resumed download did not verify; starting over.'
    }
}

function Find-JdkRoot {
    param([string]$StageDirectory)
    $top = @(Get-ChildItem -LiteralPath $StageDirectory -Force)
    $root = $StageDirectory
    if ($top.Count -eq 1 -and $top[0].PSIsContainer) { $root = $top[0].FullName }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'bin\java.exe') -PathType Leaf)) {
        Stop-Ftc 'MetadataInvalid' 'The downloaded archive does not contain a JDK (there is no bin\java.exe).'
    }
    $root
}

# Nothing is published until the unpacked JDK has been run and is the pinned one.
function Test-StagedRuntime {
    param($Manifest, [string]$JdkRoot, [string]$Destination)

    $timeout = [int]$Manifest.probe.timeoutSeconds
    $facts = Get-JdkFacts (New-JdkCandidate 'Staging' (Join-Path $JdkRoot 'bin\java.exe') $JdkRoot) $timeout
    if ($facts.Rejection) {
        Stop-Ftc 'RuntimeBlocked' "The Java runtime FTC-Sim unpacked into $Destination cannot be used: $($facts.Rejection)."
    }
    $pinned = Get-PolicyRejection $facts 'Pinned' $Manifest.runtime
    if ($pinned) { Stop-Ftc 'MetadataInvalid' "The downloaded runtime is not the pinned one: $pinned." }
    if ($Manifest.runtime.imageType -ne 'jdk') { return }
    $compiler = Invoke-NativeProcess (Join-Path $JdkRoot 'bin\javac.exe') '-version' $timeout
    if (-not $compiler.Started -or $compiler.TimedOut -or $compiler.ExitCode -ne 0) {
        Stop-Ftc 'RuntimeBlocked' "The compiler of the Java runtime FTC-Sim unpacked into $Destination does not run."
    }
}

function Write-InstallRecord {
    param([string]$JdkRoot, $Manifest, $Release)
    $record = [ordered]@{
        vendor = $Manifest.runtime.vendor; release = $Release.Name; version = $Release.Version; sha256 = $Release.Sha256
        sizeBytes = $Release.Size; source = $Release.Url.AbsoluteUri; installedUtc = [DateTime]::UtcNow.ToString('o')
    }
    [System.IO.File]::WriteAllText((Join-Path $JdkRoot 'ftcsim-install.json'), ($record | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
}

# The rename is the commit point; a folder that is already there is a broken install, since discovery would have used a good one.
# Windows refuses to rename a folder while any file inside it is open, and antivirus scanners hold the files of a freshly
# unpacked JDK open for a moment, so a refused rename is retried before it is reported.
function Publish-Directory {
    param([string]$Source, [string]$Target, [int]$Attempts = 20, [int]$DelayMilliseconds = 500)

    $broken = $null
    for ($attempt = 1; ; $attempt++) {
        try {
            if (-not $broken -and [System.IO.Directory]::Exists($Target)) {
                $moved = $Target + '.broken-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
                [System.IO.Directory]::Move($Target, $moved)
                $broken = $moved
            }
            [System.IO.Directory]::Move($Source, $Target)
            break
        } catch {
            if ($attempt -ge $Attempts) {
                $reason = (Get-BaseException $_.Exception).Message
                Stop-Ftc 'AccessDenied' "Could not move the new Java runtime into place at ${Target}: $reason A security program may still be scanning the new files; close other FTC-Sim windows and run FTC-Sim again." $_.Exception
            }
            if ($attempt -eq 1) { Write-FtcInfo '  Windows is still busy with the new files; waiting a moment...' }
            Start-Sleep -Milliseconds $DelayMilliseconds
        }
    }
    if ($broken) { Remove-PathQuietly $broken }
}

function Install-PinnedJdk {
    param($Manifest, [string]$CacheRoot, [string]$Policy, $Evaluated = @())

    $paths = Get-CachePaths $CacheRoot
    try {
        New-Item -ItemType Directory -Force -Path $paths.Jdks, $paths.Tmp, $paths.Locks | Out-Null
        $lock = Enter-CacheLock (Join-Path $paths.Locks 'provision.lock') ([int]$Manifest.network.lockWaitSeconds)
    } catch {
        if ((Get-FtcKind $_) -ne 'Unexpected') { throw }
        Stop-Ftc 'AccessDenied' "FTC-Sim could not use its data folder $CacheRoot ($((Get-BaseException $_.Exception).Message))." $_.Exception
    }
    try {
        $existing = (Find-Jdk $Manifest $CacheRoot $Policy @('Cache')).Selected
        if ($existing) { return [pscustomobject]@{ Facts = $existing; Installed = $false } }

        Write-IgnoredRuntimes $Manifest $Evaluated
        Write-FtcInfo 'FTC-Sim is preparing its Java runtime. This one-time setup needs the internet.'
        $release = Get-JdkRelease $Manifest
        $partName = 'dl-' + $release.Sha256.Substring(0, 12) + '.part'
        Clear-StaleArtifacts $paths $partName
        Assert-FreeSpace $CacheRoot ($release.Size + [long]$Manifest.runtime.extractedSizeEstimateBytes)

        $archive = Join-Path $paths.Tmp $partName
        $stage = Join-Path $paths.Tmp ('s-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
        $destination = Join-Path $paths.Jdks $release.DirectoryName
        try {
            $stream = Open-VerifiedArchive $Manifest $release $archive
            try {
                [void][System.IO.Directory]::CreateDirectory($stage)
                Write-FtcInfo '  Unpacking...'
                $unpacked = Expand-ZipSafely $stream $stage ([long]$Manifest.limits.maxExtractedBytes) ([int]$Manifest.limits.maxEntries)
                Write-FtcInfo "  Unpacked $($unpacked.Files) files ($(Format-Megabytes $unpacked.Bytes))."
            } finally {
                $stream.Dispose()
            }
            $jdkRoot = Find-JdkRoot $stage
            Write-FtcInfo '  Checking that Java runs...'
            Test-StagedRuntime $Manifest $jdkRoot $paths.Jdks
            Write-InstallRecord $jdkRoot $Manifest $release
            Publish-Directory $jdkRoot $destination
        } catch {
            # A verified archive that is unsafe or holds no usable JDK will never get better, so it is not kept; one that only
            # met a full disk or a blocked folder is worth keeping, because a retry then needs no new download.
            if ((Get-FtcKind $_) -in 'UnsafeArchive', 'MetadataInvalid') { Remove-PathQuietly $archive }
            throw
        } finally {
            Remove-PathQuietly $stage
        }
        Remove-PathQuietly $archive

        $facts = Get-JdkFacts (New-JdkCandidate 'Cache' (Join-Path $destination 'bin\java.exe') $destination) ([int]$Manifest.probe.timeoutSeconds)
        if ($facts.Rejection) { Stop-Ftc 'RuntimeBlocked' "The installed Java runtime in $destination cannot be used: $($facts.Rejection)." }
        Write-FtcInfo "  Java runtime ready: $destination"
        [pscustomobject]@{ Facts = $facts; Installed = $true }
    } finally {
        $lock.Dispose()
    }
}

function Format-RejectionList {
    param($Evaluated)
    if (@($Evaluated).Count -eq 0) { return 'no Java installation was found' }
    (@($Evaluated) | ForEach-Object { "$($_.Facts.Source): $($_.Facts.JavaExe) - $($_.Rejection)" }) -join '; '
}

# What someone with another Java on the PC is told before the pinned one is downloaded: why theirs is not used, and that it is
# left alone. JAVA_HOME has been reported already.
function Write-IgnoredRuntimes {
    param($Manifest, $Evaluated)
    $ignored = @($Evaluated | Where-Object { $_.Rejection -and $_.Facts.Source -ne 'JavaHome' })
    if ($ignored.Count -eq 0) { return }
    $shown = 4
    foreach ($entry in @($ignored | Select-Object -First $shown)) {
        $what = $entry.Facts.JavaExe
        if ($entry.Facts.Version) { $what = Format-JdkSummary $entry.Facts }
        Write-FtcInfo "$what is not used: $($entry.Rejection)."
    }
    if ($ignored.Count -gt $shown) {
        Write-FtcInfo "$($ignored.Count - $shown) more Java runtimes on this PC are not used either; FTC-Sim.cmd -ListJdks lists them all."
    }
    $pinned = "Java $($Manifest.runtime.major)"
    if ($Manifest.runtime.exactRelease) { $pinned = $Manifest.runtime.exactRelease }
    Write-FtcInfo "FTC-Sim runs on one exact Java build ($pinned) so that it behaves the same on every PC. It keeps its own copy and leaves the Java on this PC alone."
}

function Resolve-Runtime {
    param($Manifest, [string]$CacheRoot, [string]$Policy, [string[]]$Sources, [bool]$AllowProvision)

    $search = Find-Jdk $Manifest $CacheRoot $Policy $Sources
    # JAVA_HOME is the runtime people expect FTC-Sim to use, so say why a wrong one is passed over instead of ignoring it quietly.
    foreach ($entry in $search.Evaluated) {
        if ($entry.Facts.Source -eq 'JavaHome' -and $entry.Rejection) {
            Write-FtcInfo "JAVA_HOME ($($entry.Facts.JavaExe)) is not used: $($entry.Rejection)."
        }
    }
    if ($search.Selected) { return [pscustomobject]@{ Facts = $search.Selected; Installed = $false } }
    if (-not $AllowProvision) {
        Stop-Ftc 'NoRuntime' "No usable Java $($Manifest.runtime.major) runtime was found ($Policy): $(Format-RejectionList $search.Evaluated)."
    }
    Install-PinnedJdk $Manifest $CacheRoot $Policy $search.Evaluated
}

# Java runtime discovery. Candidates come from several places but nothing here changes JAVA_HOME, PATH or the registry;
# every candidate is proven usable by running it, so a stale registry entry or a program that does not start is rejected with
# a reason. A JDK and a JRE are treated alike on purpose: the simulator needs java.exe and no compiler, so what decides is the
# Java major version (see Get-PolicyRejection) and that the runtime is 64-bit x64.

$script:AllDiscoverySources = @('Cache', 'JavaHome', 'Path', 'Registry', 'CommonDirs', 'AndroidStudio')

function New-JdkCandidate {
    param([string]$Source, [string]$JavaExe, [string]$JdkHome)
    [pscustomobject]@{ Source = $Source; JavaExe = $JavaExe; JdkHome = $JdkHome }
}

function Get-CacheCandidates {
    param([string]$CacheRoot)
    $jdks = Join-Path $CacheRoot 'jdks'
    if (-not (Test-Path -LiteralPath $jdks -PathType Container)) { return }
    $installed = Get-ChildItem -LiteralPath $jdks -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '^\.' -and $_.Name -notmatch '\.broken-' } |
        Sort-Object CreationTimeUtc -Descending
    foreach ($directory in $installed) {
        New-JdkCandidate 'Cache' (Join-Path $directory.FullName 'bin\java.exe') $directory.FullName
    }
}

function Get-JavaHomeCandidates {
    if (-not $env:JAVA_HOME) { return }
    $directory = $env:JAVA_HOME.Trim().Trim('"').TrimEnd('\')
    if ($directory) { New-JdkCandidate 'JavaHome' (Join-Path $directory 'bin\java.exe') $directory }
}

function Get-PathCandidates {
    foreach ($entry in ($env:PATH -split ';')) {
        $directory = [Environment]::ExpandEnvironmentVariables($entry.Trim().Trim('"'))
        # A relative or empty entry would resolve against the current directory, which anyone could populate.
        if (-not $directory -or -not [System.IO.Path]::IsPathRooted($directory)) { continue }
        $javaExe = Join-Path $directory 'java.exe'
        if (Test-Path -LiteralPath $javaExe -PathType Leaf) { New-JdkCandidate 'Path' $javaExe $null }
    }
}

function Get-RegistryKeyTree {
    param([string]$Path, [int]$Depth)
    $Path
    if ($Depth -le 0) { return }
    foreach ($child in @(Get-ChildItem -LiteralPath $Path -ErrorAction SilentlyContinue)) {
        Get-RegistryKeyTree $child.PSPath ($Depth - 1)
    }
}

function Get-RegistryCandidates {
    param($Manifest)
    foreach ($root in @($Manifest.discovery.registryRoots)) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($keyPath in @(Get-RegistryKeyTree $root 4)) {
            $values = Get-ItemProperty -LiteralPath $keyPath -ErrorAction SilentlyContinue
            if (-not $values) { continue }
            foreach ($name in @($Manifest.discovery.registryValueNames)) {
                $property = $values.PSObject.Properties[$name]
                if ($property -and $property.Value -is [string] -and $property.Value.Trim()) {
                    $directory = $property.Value.Trim().Trim('"').TrimEnd('\')
                    New-JdkCandidate 'Registry' (Join-Path $directory 'bin\java.exe') $directory
                }
            }
        }
    }
}

function Get-DirectoryPatternCandidates {
    param($Manifest, [string]$Source)
    foreach ($entry in @($Manifest.discovery.directoryPatterns)) {
        if ($entry.source -ne $Source) { continue }
        $pattern = [Environment]::ExpandEnvironmentVariables($entry.pattern)
        if ($pattern -match '%[^%]+%') { continue }
        foreach ($match in @(Resolve-Path -Path $pattern -ErrorAction SilentlyContinue)) {
            if (-not (Test-Path -LiteralPath $match.ProviderPath -PathType Container)) { continue }
            New-JdkCandidate $Source (Join-Path $match.ProviderPath 'bin\java.exe') $match.ProviderPath
        }
    }
}

# Candidates in priority order: the FTC-Sim cache first, then what the machine has configured.
function Get-JdkCandidates {
    param($Manifest, [string]$CacheRoot, [string[]]$Sources)
    foreach ($source in $script:AllDiscoverySources) {
        if ($Sources -notcontains $source) { continue }
        try {
            switch ($source) {
                'Cache'         { Get-CacheCandidates $CacheRoot }
                'JavaHome'      { Get-JavaHomeCandidates }
                'Path'          { Get-PathCandidates }
                'Registry'      { Get-RegistryCandidates $Manifest }
                'CommonDirs'    { Get-DirectoryPatternCandidates $Manifest 'CommonDirs' }
                'AndroidStudio' { Get-DirectoryPatternCandidates $Manifest 'AndroidStudio' }
            }
        } catch {
            Write-Verbose "Discovery source $source failed: $($_.Exception.Message)"
        }
    }
}

# Runs a program with a timeout and returns what it printed. A program that cannot be started is a result,
# not an exception, because "blocked by policy" and "corrupt executable" are expected outcomes here.
function Invoke-NativeProcess {
    param([string]$FilePath, [string]$Arguments, [int]$TimeoutSeconds)

    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    try {
        $process = [System.Diagnostics.Process]::Start($info)
    } catch [System.ComponentModel.Win32Exception] {
        return [pscustomobject]@{ Started = $false; TimedOut = $false; ExitCode = -1; StdOut = ''; StdErr = ''
            NativeError = $_.Exception.NativeErrorCode; Error = $_.Exception.Message }
    }
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            return [pscustomobject]@{ Started = $true; TimedOut = $true; ExitCode = -1; StdOut = ''; StdErr = ''; NativeError = 0; Error = '' }
        }
        $process.WaitForExit()
        [pscustomobject]@{ Started = $true; TimedOut = $false; ExitCode = $process.ExitCode
            StdOut = $stdout.Result; StdErr = $stderr.Result; NativeError = 0; Error = '' }
    } finally {
        $process.Dispose()
    }
}

# 'java -XshowSettings:properties -version' prints "    key = value" lines on stderr; continuation lines are indented deeper.
function ConvertFrom-JavaSettings {
    param([string]$Text)
    $properties = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^    (\S+) = (.*)$' -and -not $properties.ContainsKey($Matches[1])) {
            $properties[$Matches[1]] = $Matches[2].Trim()
        }
    }
    $properties
}

function ConvertTo-JavaMajor {
    param([string]$SpecificationVersion)
    if ($SpecificationVersion -match '^1\.(\d+)$') { return [int]$Matches[1] }
    if ($SpecificationVersion -match '^(\d+)') { return [int]$Matches[1] }
    0
}

function New-JdkFacts {
    param($Candidate, [string]$Rejection, [int]$NativeError = 0, $Properties = @{}, [string]$JdkHome = $null)
    $architecture = ''
    if ($Properties.ContainsKey('os.arch')) { $architecture = $Properties['os.arch'] }
    $version = ''
    if ($Properties.ContainsKey('java.version')) { $version = $Properties['java.version'] }
    $runtimeVersion = ''
    if ($Properties.ContainsKey('java.runtime.version')) { $runtimeVersion = $Properties['java.runtime.version'] }
    $vendor = ''
    if ($Properties.ContainsKey('java.vendor')) { $vendor = $Properties['java.vendor'] }
    $major = 0
    if ($Properties.ContainsKey('java.specification.version')) { $major = ConvertTo-JavaMajor $Properties['java.specification.version'] }
    [pscustomobject]@{
        Source = $Candidate.Source; JavaExe = $Candidate.JavaExe; Home = $JdkHome; Major = $major; Version = $version
        RuntimeVersion = $runtimeVersion; Vendor = $vendor; Arch = $architecture; Rejection = $Rejection; NativeError = $NativeError
    }
}

# Proves a candidate by running it. Rejection is null when it is a usable 64-bit x64 runtime; whether it is the right
# version is Get-PolicyRejection's business, and a JRE without javac is as good as a JDK.
function Get-JdkFacts {
    param($Candidate, [int]$TimeoutSeconds)

    if (-not (Test-Path -LiteralPath $Candidate.JavaExe -PathType Leaf)) {
        return New-JdkFacts $Candidate 'there is no bin\java.exe' -JdkHome $Candidate.JdkHome
    }
    $run = Invoke-NativeProcess $Candidate.JavaExe '-XshowSettings:properties -version' $TimeoutSeconds
    if (-not $run.Started) {
        return New-JdkFacts $Candidate "it cannot be started ($($run.Error))" $run.NativeError -JdkHome $Candidate.JdkHome
    }
    if ($run.TimedOut) {
        return New-JdkFacts $Candidate "it did not answer within $TimeoutSeconds seconds" -JdkHome $Candidate.JdkHome
    }
    $properties = ConvertFrom-JavaSettings $run.StdErr
    if ($run.ExitCode -ne 0 -or -not $properties.ContainsKey('java.version') -or -not $properties.ContainsKey('java.specification.version')) {
        return New-JdkFacts $Candidate "it did not report its Java properties (exit code $($run.ExitCode))" -JdkHome $Candidate.JdkHome
    }

    $jdkHome = $Candidate.JdkHome
    if (-not $jdkHome) {
        $binDirectory = Split-Path -Parent $Candidate.JavaExe
        if ((Split-Path -Leaf $binDirectory) -eq 'bin') { $jdkHome = Split-Path -Parent $binDirectory }
        elseif ($properties.ContainsKey('java.home')) { $jdkHome = $properties['java.home'] }
        else { $jdkHome = $binDirectory }
    }

    $rejection = $null
    $dataModel = ''
    if ($properties.ContainsKey('sun.arch.data.model')) { $dataModel = $properties['sun.arch.data.model'] }
    if (($properties['os.arch'] -notin @('amd64', 'x86_64')) -or ($dataModel -and $dataModel -ne '64')) {
        $rejection = "it is a $($properties['os.arch']) JVM; only 64-bit x64 is supported"
    }
    New-JdkFacts $Candidate $rejection -Properties $properties -JdkHome $jdkHome
}

# Pinned accepts exactly the manifest's Java major version (and exact build when the manifest names one); Any accepts the
# pinned major and anything newer, and only a developer asking for it with -RuntimePolicy Any ever gets it.
# The exact-build pin compares the part of java.runtime.version before any vendor suffix such as "-LTS".
function Get-PolicyRejection {
    param($Facts, [string]$Policy, $Runtime)
    if ($Facts.Rejection) { return $Facts.Rejection }
    if ($Policy -eq 'Pinned') {
        if ($Facts.Major -ne [int]$Runtime.major) {
            return "Java $($Facts.Major) is not the pinned runtime (Java $($Runtime.major))"
        }
        if ($Runtime.exactRelease) {
            $build = $Runtime.exactRelease -replace '^jdk-', ''
            if ($Facts.RuntimeVersion -notmatch ('^' + [regex]::Escape($build) + '(-.*)?$')) {
                return "build $($Facts.RuntimeVersion) is not the pinned build $build"
            }
        }
    } elseif ($Facts.Major -lt [int]$Runtime.major) {
        return "Java $($Facts.Major) is older than the minimum, Java $($Runtime.major)"
    }
    $null
}

# Evaluates candidates in priority order and stops at the first acceptable one unless -All is given.
# Nothing is ever chosen silently: Pinned accepts only the pinned runtime, Any accepts Java >= the pinned major.
function Find-Jdk {
    param($Manifest, [string]$CacheRoot, [string]$Policy, [string[]]$Sources, [switch]$All)

    $evaluated = New-Object System.Collections.ArrayList
    $seen = @{}
    $selected = $null
    foreach ($candidate in @(Get-JdkCandidates $Manifest $CacheRoot $Sources)) {
        $key = $candidate.JavaExe.ToLowerInvariant()
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        try {
            $facts = Get-JdkFacts $candidate ([int]$Manifest.probe.timeoutSeconds)
        } catch {
            $facts = New-JdkFacts $candidate "probing it failed ($($_.Exception.Message))" -JdkHome $candidate.JdkHome
        }
        # Several routes (JAVA_HOME, PATH, registry) often lead to the same installation.
        if ($facts.Home) {
            $homeKey = 'home:' + $facts.Home.ToLowerInvariant()
            if ($seen.ContainsKey($homeKey)) { continue }
            $seen[$homeKey] = $true
        }
        $rejection = Get-PolicyRejection $facts $Policy $Manifest.runtime
        [void]$evaluated.Add([pscustomobject]@{ Facts = $facts; Rejection = $rejection })
        if (-not $rejection -and -not $selected) {
            $selected = $facts
            if (-not $All) { break }
        }
    }
    [pscustomobject]@{ Selected = $selected; Evaluated = @($evaluated) }
}

function Format-JdkSummary {
    param($Facts)
    "Java $($Facts.Version) ($($Facts.Vendor), $($Facts.Arch)) at $($Facts.Home)"
}

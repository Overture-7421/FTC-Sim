# Unpacking of the downloaded JDK archive. Expand-Archive trusts entry names and is slow while antivirus scans every new
# file, so the whole central directory is checked before the first byte is written and the data is then copied in large blocks.

$script:ReservedDeviceName = '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$'

# Returns why an entry must not be unpacked, or $null for an ordinary relative file or folder.
function Get-ZipEntryProblem {
    param($Entry)

    $name = $Entry.FullName
    if (-not $name -or $name -match '[\x00-\x1f]') { return 'its name is empty or contains control characters' }
    # A colon is a drive letter ("C:\x", "C:x") or an NTFS alternate data stream ("file:stream").
    if ($name.Contains(':')) { return 'its name contains a colon (a drive letter or a data stream)' }
    # Windows reads both separators, so a backslash climbs out of a folder just like a slash does.
    $path = $name.Replace('\', '/')
    if ($path.StartsWith('/')) { return 'its name is an absolute path' }
    foreach ($segment in $path.TrimEnd('/').Split('/')) {
        if ($segment -eq '..') { return 'its name climbs out of the folder it is unpacked into' }
        if (-not $segment -or $segment -eq '.') { return 'its name is not a plain relative path' }
        if ($segment -match '[<>"|?*]') { return 'its name contains a character that Windows does not allow in file names' }
        if ($segment -match '[. ]$') { return 'a part of its name ends with a dot or a space, which Windows silently drops' }
        if ($segment -match $script:ReservedDeviceName) { return 'its name is a reserved Windows device name' }
    }
    # Only .NET Framework 4.7.2 and later report the attributes; older ones still never create links, they unpack them as files.
    $attributes = $Entry.PSObject.Properties['ExternalAttributes']
    if ($attributes) {
        $mode = [int]$attributes.Value
        # The upper half holds the Unix mode whose 0xF000 bits are the file type: 0x8000 file, 0x4000 folder, 0xA000 symbolic link.
        $type = ($mode -shr 16) -band 0xF000
        if ($type -ne 0 -and $type -ne 0x8000 -and $type -ne 0x4000) { return 'it is a link or a device, not a plain file or folder' }
        if (($mode -band 0x400) -ne 0) { return 'it is marked as a Windows reparse point (a link)' }
    }
    $null
}

# Decides everything that could make unpacking unsafe before anything is written: names, links, duplicates, counts and sizes.
function Get-ZipUnpackPlan {
    param($Zip, [string]$Root, [long]$MaxBytes, [int]$MaxEntries)

    $entries = @($Zip.Entries)
    if ($entries.Count -eq 0) { Stop-Ftc 'UnsafeArchive' 'The downloaded archive is empty.' }
    if ($entries.Count -gt $MaxEntries) {
        Stop-Ftc 'UnsafeArchive' "The downloaded archive holds $($entries.Count) entries; FTC-Sim accepts at most $MaxEntries."
    }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $declared = 0L
    foreach ($entry in $entries) {
        $problem = Get-ZipEntryProblem $entry
        if ($problem) { Stop-Ftc 'UnsafeArchive' "The archive entry '$($entry.FullName)' was rejected: $problem." }
        $path = $Root + $entry.FullName.Replace('/', '\').TrimEnd('\')
        if (-not $seen.Add($path)) { Stop-Ftc 'UnsafeArchive' "The archive lists '$($entry.FullName)' more than once." }
        $declared += $entry.Length
        [pscustomobject]@{ Entry = $entry; Path = $path; IsDirectory = ($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('\')) }
    }
    if ($declared -gt $MaxBytes) {
        Stop-Ftc 'UnsafeArchive' "The downloaded archive would unpack to $(Format-Megabytes $declared), more than the $(Format-Megabytes $MaxBytes) FTC-Sim accepts."
    }
}

# Copies one entry, refusing to write more than the entry declares or than the remaining budget allows. Returns the bytes written.
function Copy-ZipEntry {
    param($Entry, [string]$Path, [byte[]]$Buffer, [long]$Budget)

    $copied = 0L
    $source = $Entry.Open()
    try {
        $target = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None, 4096, [System.IO.FileOptions]::SequentialScan)
        try {
            while (($read = $source.Read($Buffer, 0, $Buffer.Length)) -gt 0) {
                $copied += $read
                if ($copied -gt $Entry.Length -or $copied -gt $Budget) {
                    Stop-Ftc 'UnsafeArchive' "The archive entry '$($Entry.FullName)' holds more data than it declares or than FTC-Sim accepts."
                }
                $target.Write($Buffer, 0, $read)
            }
        } finally {
            $target.Dispose()
        }
    } finally {
        $source.Dispose()
    }
    if ($copied -ne $Entry.Length) {
        Stop-Ftc 'UnsafeArchive' "The archive entry '$($Entry.FullName)' is damaged: it holds $copied bytes but declares $($Entry.Length)."
    }
    $copied
}

# Disk problems keep their own exit codes; anything else that goes wrong while unpacking means the archive cannot be trusted.
function Stop-OnUnpackError {
    param($ErrorRecord, [string]$Destination)
    if ((Get-FtcKind $ErrorRecord) -ne 'Unexpected') { throw $ErrorRecord }
    $failure = Get-BaseException $ErrorRecord.Exception
    try { Stop-OnDiskError $failure $Destination } catch { if ((Get-FtcKind $_) -ne 'Unexpected') { throw } }
    Stop-Ftc 'UnsafeArchive' "The downloaded archive could not be unpacked safely: $($failure.Message)" $failure
}

# Unpacks the open archive $Archive into the new, empty folder $Destination. The stream stays open and is read only, so the
# bytes that were checksummed are the bytes that are unpacked. Returns how many files and bytes were written.
function Expand-ZipSafely {
    param([System.IO.Stream]$Archive, [string]$Destination, [long]$MaxBytes, [int]$MaxEntries)

    Add-Type -AssemblyName System.IO.Compression
    $root = [System.IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $buffer = New-Object byte[] 1048576
    $files = 0
    $total = 0L
    try {
        $zip = New-Object System.IO.Compression.ZipArchive($Archive, [System.IO.Compression.ZipArchiveMode]::Read, $true)
        try {
            foreach ($item in @(Get-ZipUnpackPlan $zip $root $MaxBytes $MaxEntries)) {
                if ($item.IsDirectory) {
                    [void][System.IO.Directory]::CreateDirectory((ConvertTo-ExtendedPath $item.Path))
                    continue
                }
                [void][System.IO.Directory]::CreateDirectory((ConvertTo-ExtendedPath $item.Path.Substring(0, $item.Path.LastIndexOf('\'))))
                $total += Copy-ZipEntry $item.Entry (ConvertTo-ExtendedPath $item.Path) $buffer ($MaxBytes - $total)
                $files++
            }
        } finally {
            $zip.Dispose()
        }
    } catch {
        Stop-OnUnpackError $_ $Destination
    }
    [pscustomobject]@{ Files = $files; Bytes = $total }
}

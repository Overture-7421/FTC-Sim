# Maintainer tool, not part of the distribution: pins the Java runtime that FTC-Sim downloads and accepts to one exact release.
#
# It asks the release service named in jdk-manifest.json (source.apiBase) for a release, the newest of the pinned Java major
# version or the one named with -Release, downloads the archive and checks the bytes against the SHA-256 the service published.
# Then it shows the two values the manifest pins, runtime.exactRelease and runtime.exactSha256, and with -Apply writes them
# into the manifest, changing nothing else. Afterwards run the launcher and app tests; see docs\architecture\distribution.md.
#
#   .\launcher\tools\Update-JdkPin.ps1                         the newest release: show it
#   .\launcher\tools\Update-JdkPin.ps1 -Release jdk-25.0.6+7   that release: show it
#   .\launcher\tools\Update-JdkPin.ps1 -Apply                  and write it into launcher\scripts\jdk-manifest.json
#
# -ManifestPath and -ProxyUrl are as in FTC-Sim.ps1. Exit code 0 when the release was found and verified (and written), else 1.
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Release,
    [string]$ManifestPath,
    [switch]$Apply,
    [string]$ProxyUrl
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$scripts = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts'
. (Join-Path $scripts 'ftcsim\Messages.ps1')
Set-StrictMode -Version Latest
foreach ($library in @(Get-ChildItem -LiteralPath (Join-Path $scripts 'ftcsim') -Filter '*.ps1' | Where-Object { $_.Name -ne 'Messages.ps1' } | Sort-Object Name)) {
    . $library.FullName
}

# Replaces the two values in place, so the layout of the manifest and everything else in it stay as they are.
function Set-ManifestPin {
    param([string]$Path, [string]$Name, [string]$Sha256)
    $text = [System.IO.File]::ReadAllText($Path)
    foreach ($pin in @(@('exactRelease', $Name), @('exactSha256', $Sha256))) {
        $pattern = '("' + $pin[0] + '"\s*:\s*)(null|"[^"]*")'
        if ($text -notmatch $pattern) { Stop-Ftc 'MetadataInvalid' "The manifest has no $($pin[0]) line to update: $Path" }
        $value = $pin[1]
        $text = [regex]::Replace($text, $pattern, { param($match) $match.Groups[1].Value + '"' + $value + '"' }.GetNewClosure())
    }
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

try {
    if (-not $ManifestPath) { $ManifestPath = Join-Path $scripts 'jdk-manifest.json' }
    $manifest = Read-RuntimeManifest $ManifestPath
    Initialize-Networking $ProxyUrl

    # The pin that is there now is what this tool replaces, so the service is asked without it.
    $manifest.runtime.exactRelease = $Release
    $manifest.runtime.exactSha256 = $null
    $found = Get-JdkRelease $manifest
    Write-Host "Release:  $($found.Name) ($($found.Version))"
    Write-Host "Archive:  $($found.FileName), $(Format-Megabytes $found.Size)"
    Write-Host "Link:     $($found.Url.AbsoluteUri)"

    $work = Join-Path ([System.IO.Path]::GetTempPath()) ('ftcsim-pin-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    [void][System.IO.Directory]::CreateDirectory($work)
    try {
        $archive = Join-Path $work $found.FileName
        Write-Host 'Downloading the archive to check the checksum that the service published...'
        [void](Save-AllowedFile $manifest $found.Url $archive $found.Size $found.FileName)
        $stream = [System.IO.File]::OpenRead($archive)
        try { $actual = Get-StreamSha256 $stream } finally { $stream.Dispose() }
    } finally {
        Remove-PathQuietly $work
    }
    if ($actual -ne $found.Sha256) {
        Stop-Ftc 'ChecksumMismatch' "The downloaded archive has the SHA-256 $actual, but the service published $($found.Sha256). Nothing was changed."
    }
    Write-Host "SHA-256:  $actual (the service's checksum and the downloaded bytes agree)"
    Write-Host ''
    Write-Host "exactRelease: $($found.Name)"
    Write-Host "exactSha256:  $($found.Sha256)"

    if ($Apply) {
        Set-ManifestPin $ManifestPath $found.Name $found.Sha256
        [void](Read-RuntimeManifest $ManifestPath)
        Write-Host "Written to $ManifestPath. Next: .\gradlew.bat :launcher:check :app:check, then run FTC-Sim.cmd --self-test once."
    } else {
        Write-Host 'Nothing was changed. Run again with -Apply to write these two values into the manifest.'
    }
    exit 0
} catch {
    Write-Host ('Update-JdkPin failed [' + (Get-FtcKind $_) + ']: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}

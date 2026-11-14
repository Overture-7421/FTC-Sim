# Loads and validates jdk-manifest.json: the pinned runtime, the Adoptium API shape, the HTTPS origins that
# may be contacted, and the discovery locations. All of it is data so it can change without touching code.

$script:ManifestRequired = [ordered]@{
    runtime   = @('vendor', 'major', 'os', 'architecture', 'imageType', 'extractedSizeEstimateBytes')
    source    = @('apiBase', 'latestPath', 'releasePath', 'allowedOrigins')
    network   = @('attempts', 'retryDelaysSeconds', 'connectTimeoutSeconds', 'readTimeoutSeconds', 'maxRedirects', 'lockWaitSeconds')
    limits    = @('maxExtractedBytes', 'maxEntries')
    probe     = @('timeoutSeconds')
    discovery = @('registryRoots', 'registryValueNames', 'directoryPatterns')
    application = @('jvmArguments')
}

function ConvertTo-AllowedOrigin {
    param([string]$Text)
    $uri = $null
    $valid = [Uri]::TryCreate($Text, [UriKind]::Absolute, [ref]$uri)
    if (-not $valid -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.UserInfo) {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest lists an origin that is not scheme://host[:port]: $Text"
    }
    # The checksum comes from the same service as the download, so a plain-http origin would defeat it.
    if ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback)) {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest allows a non-HTTPS origin: $Text"
    }
    $uri
}

function Read-RuntimeManifest {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest is missing: $Path"
    }
    try {
        $manifest = [System.IO.File]::ReadAllText($Path) | ConvertFrom-Json
    } catch {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest is not valid JSON: $Path ($($_.Exception.Message))" $_.Exception
    }

    if ($manifest.PSObject.Properties['schemaVersion'] -eq $null -or $manifest.schemaVersion -ne 1) {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest has an unsupported schemaVersion: $Path"
    }
    foreach ($section in $script:ManifestRequired.Keys) {
        $node = $manifest.PSObject.Properties[$section]
        if ($node -eq $null) { Stop-Ftc 'MetadataInvalid' "The runtime manifest has no '$section' section: $Path" }
        foreach ($name in $script:ManifestRequired[$section]) {
            if ($node.Value.PSObject.Properties[$name] -eq $null) {
                Stop-Ftc 'MetadataInvalid' "The runtime manifest section '$section' has no '$name': $Path"
            }
        }
    }
    foreach ($optional in 'exactRelease', 'exactSha256') {
        if ($manifest.runtime.PSObject.Properties[$optional] -eq $null) {
            Add-Member -InputObject $manifest.runtime -NotePropertyName $optional -NotePropertyValue $null
        }
    }
    if ($manifest.runtime.os -ne 'windows' -or $manifest.runtime.architecture -ne 'x64') {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest must target windows/x64: $Path"
    }
    if ($manifest.runtime.exactSha256 -and $manifest.runtime.exactSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest exactSha256 is not a SHA-256 value: $Path"
    }
    foreach ($option in @($manifest.application.jvmArguments)) {
        if ($option -isnot [string] -or $option -notmatch '^-') {
            Stop-Ftc 'MetadataInvalid' "The runtime manifest application.jvmArguments must be a list of Java options: $Path"
        }
    }

    $origins = @()
    foreach ($text in @($manifest.source.allowedOrigins)) { $origins += ConvertTo-AllowedOrigin $text }
    if ($origins.Count -eq 0) { Stop-Ftc 'MetadataInvalid' "The runtime manifest allows no origins: $Path" }
    Add-Member -InputObject $manifest -NotePropertyName AllowedOriginUris -NotePropertyValue $origins

    if (-not (Test-OriginAllowed $manifest ([Uri]$manifest.source.apiBase))) {
        Stop-Ftc 'MetadataInvalid' "The runtime manifest apiBase is not in its own allowedOrigins: $($manifest.source.apiBase)"
    }
    $manifest
}

function Test-OriginAllowed {
    param($Manifest, [Uri]$Uri)
    if (-not $Uri.IsAbsoluteUri -or $Uri.UserInfo) { return $false }
    foreach ($origin in $Manifest.AllowedOriginUris) {
        if ($origin.Scheme -eq $Uri.Scheme -and $origin.Host -eq $Uri.Host -and $origin.Port -eq $Uri.Port) { return $true }
    }
    $false
}

function Get-AllowedHostList {
    param($Manifest)
    ($Manifest.AllowedOriginUris | ForEach-Object { $_.GetLeftPart([UriPartial]::Authority) }) -join ', '
}

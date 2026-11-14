# HTTP access for stage 0. Every request, including each redirect hop, must target an origin from the
# manifest; the system proxy is honoured with the user's own credentials; downloads resume with Range requests.

function Get-RequestProxy {
    param([string]$ProxyUrl)
    if ($ProxyUrl) {
        try {
            $proxy = New-Object System.Net.WebProxy($ProxyUrl, $false)
        } catch {
            Stop-Ftc 'BadArguments' "The proxy address '$ProxyUrl' is not valid; use a form such as http://proxy.example.org:8080." $_.Exception
        }
    } else {
        $proxy = [System.Net.WebRequest]::GetSystemWebProxy()
    }
    $proxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
    $proxy
}

function Initialize-Networking {
    param([string]$ProxyUrl)
    # Older .NET Framework defaults still negotiate SSL3/TLS 1.0, which GitHub refuses. SystemDefault leaves the choice to
    # Windows, which already means TLS 1.2 or newer, so only an explicit older list needs TLS 1.2 added.
    $protocols = [System.Net.ServicePointManager]::SecurityProtocol
    if ($protocols -ne [System.Net.SecurityProtocolType]::SystemDefault) {
        [System.Net.ServicePointManager]::SecurityProtocol = $protocols -bor [System.Net.SecurityProtocolType]::Tls12
    }
    [System.Net.ServicePointManager]::Expect100Continue = $false
    $script:RequestProxy = Get-RequestProxy $ProxyUrl
}

function Get-BaseException {
    param([System.Exception]$Exception)
    while (($Exception -is [System.Management.Automation.MethodInvocationException] -or $Exception -is [System.Management.Automation.RuntimeException]) -and $Exception.InnerException) {
        $Exception = $Exception.InnerException
    }
    $Exception
}

function Get-HttpStatus {
    param([System.Exception]$Exception)
    $web = Get-BaseException $Exception
    if ($web -is [System.Net.WebException] -and $web.Response -is [System.Net.HttpWebResponse]) { return [int]$web.Response.StatusCode }
    0
}

# Transient: worth retrying. Unreachable: nothing answered at all, which the user experiences as "offline".
function Get-NetworkFailure {
    param([System.Exception]$Exception)

    $base = Get-BaseException $Exception
    $failure = [pscustomobject]@{ Transient = $false; Unreachable = $false; Description = $base.Message }
    if ($base.Data -and $base.Data.Contains('FtcTransient')) { $failure.Transient = $true; return $failure }
    if ($base -is [System.Net.WebException]) {
        $http = Get-HttpStatus $base
        switch ($base.Status.ToString()) {
            { $_ -in 'NameResolutionFailure', 'ProxyNameResolutionFailure', 'ConnectFailure' } { $failure.Transient = $true; $failure.Unreachable = $true }
            'Timeout' { $failure.Transient = $true; $failure.Unreachable = $true }
            { $_ -in 'ConnectionClosed', 'ReceiveFailure', 'SendFailure', 'KeepAliveFailure', 'PipelineFailure', 'UnknownError' } { $failure.Transient = $true }
            'ProtocolError' {
                $failure.Description = "HTTP $http $($base.Response.StatusDescription)"
                if ($http -eq 407) { $failure.Description = 'the proxy server requires credentials (HTTP 407)' }
                elseif (($http -in 408, 429) -or ($http -ge 500)) { $failure.Transient = $true }
            }
            { $_ -in 'TrustFailure', 'SecureChannelFailure' } {
                $failure.Description = "the secure connection could not be trusted ($($base.Message)); a network filter may be intercepting HTTPS"
            }
        }
    } elseif ($base -is [System.IO.IOException]) {
        $failure.Transient = $true
    }
    $failure
}

function Stop-OnNetworkFailure {
    param($Manifest, $Failure, [string]$What, $Inner)
    $addresses = Get-AllowedHostList $Manifest
    if ($Failure.Unreachable) {
        Stop-Ftc 'Offline' "$What failed because nothing could be reached ($($Failure.Description)). FTC-Sim contacts: $addresses." $Inner
    }
    Stop-Ftc 'DownloadFailed' "$What failed ($($Failure.Description)). FTC-Sim contacts: $addresses." $Inner
}

# Runs $Action($attempt) until it succeeds, retrying transient failures with the manifest's delays.
# Failures that are already typed (disk full, unsafe archive, ...) are final.
function Invoke-WithRetry {
    param($Manifest, [string]$What, [scriptblock]$Action)

    $attempts = [int]$Manifest.network.attempts
    $delays = @($Manifest.network.retryDelaysSeconds)
    for ($attempt = 1; ; $attempt++) {
        try {
            return (& $Action $attempt)
        } catch {
            $exception = $_.Exception
            $failure = Get-NetworkFailure $exception
            $typed = $exception.Data -and $exception.Data.Contains('FtcKind')
            if ($typed -and -not $failure.Transient) { throw }
            if (-not $failure.Transient -or $attempt -ge $attempts) { Stop-OnNetworkFailure $Manifest $failure $What $exception }
            $delay = 0
            if ($delays.Count -gt 0) { $delay = [int]$delays[[Math]::Min($attempt - 1, $delays.Count - 1)] }
            Write-FtcInfo "  $What did not work ($($failure.Description)); trying again in $delay s (attempt $($attempt + 1) of $attempts)."
            if ($delay -gt 0) { Start-Sleep -Seconds $delay }
        }
    }
}

# Opens a response for $Uri, following redirects by hand so each hop can be checked against the allowlist.
function Open-AllowedResponse {
    param($Manifest, [Uri]$Uri, [long]$RangeStart = 0, [string]$Accept = $null)

    $current = $Uri
    for ($hop = 0; $hop -le [int]$Manifest.network.maxRedirects; $hop++) {
        if (-not (Test-OriginAllowed $Manifest $current)) {
            Stop-Ftc 'DownloadFailed' "Refusing to contact $($current.GetLeftPart([UriPartial]::Authority)): it is not one of the allowed origins ($(Get-AllowedHostList $Manifest))."
        }
        $request = [System.Net.HttpWebRequest][System.Net.WebRequest]::Create($current)
        $request.AllowAutoRedirect = $false
        $request.UserAgent = 'FTC-Sim-Launcher/1 (Windows PowerShell)'
        $request.Timeout = [int]$Manifest.network.connectTimeoutSeconds * 1000
        $request.ReadWriteTimeout = [int]$Manifest.network.readTimeoutSeconds * 1000
        $request.Proxy = $script:RequestProxy
        if ($Accept) { $request.Accept = $Accept }
        if ($RangeStart -gt 0) { $request.AddRange([long]$RangeStart) }

        $response = [System.Net.HttpWebResponse]$request.GetResponse()
        if ([int]$response.StatusCode -notin 301, 302, 303, 307, 308) { return $response }
        $location = $response.Headers['Location']
        $response.Close()
        if (-not $location) { Stop-Ftc 'DownloadFailed' "The server at $($current.Host) redirected without saying where to." }
        $current = New-Object System.Uri($current, $location)
    }
    Stop-Ftc 'DownloadFailed' "Too many redirects while contacting $($Uri.Host)."
}

function Get-JsonDocument {
    param($Manifest, [Uri]$Uri, [string]$NotFoundMessage)
    Invoke-WithRetry $Manifest "Asking $($Uri.Host) for the runtime release" {
        param($attempt)
        try {
            $response = Open-AllowedResponse $Manifest $Uri 0 'application/json'
        } catch {
            # An answer that will not change is no network trouble, and saying so sends a student to the wrong place.
            if ($NotFoundMessage -and (Get-HttpStatus $_.Exception) -eq 404) { Stop-Ftc 'MetadataInvalid' $NotFoundMessage $_.Exception }
            throw
        }
        try {
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally {
            $response.Close()
        }
        try { $text | ConvertFrom-Json } catch { Stop-Ftc 'MetadataInvalid' "$($Uri.Host) did not return JSON: $($_.Exception.Message)" $_.Exception }
    }
}

function Format-Megabytes {
    param([long]$Bytes)
    ('{0:N1} MB' -f ($Bytes / 1MB))
}

# Console: one self-updating line. Redirected output (logs, tests): one line per 10 percent.
function New-DownloadProgress {
    param([string]$Label, [long]$Total)
    @{ Label = $Label; Total = $Total; LastTick = 0; LastDecile = -1; Clock = [System.Diagnostics.Stopwatch]::StartNew()
       Redirected = [Console]::IsOutputRedirected; Open = $false }
}

function Update-DownloadProgress {
    param($Progress, [long]$Done)
    $percent = [int](100 * $Done / $Progress.Total)
    if ($Progress.Redirected) {
        $decile = [int][Math]::Floor($percent / 10)
        if ($decile -ne $Progress.LastDecile) {
            $Progress.LastDecile = $decile
            Write-FtcInfo ('  {0}: {1} of {2} ({3}%)' -f $Progress.Label, (Format-Megabytes $Done), (Format-Megabytes $Progress.Total), $percent)
        }
        return
    }
    if ($Progress.Clock.ElapsedMilliseconds - $Progress.LastTick -lt 250 -and $Done -lt $Progress.Total) { return }
    $Progress.LastTick = $Progress.Clock.ElapsedMilliseconds
    $Progress.Open = $true
    Write-Host -NoNewline ("`r  {0}: {1} of {2} ({3}%)   " -f $Progress.Label, (Format-Megabytes $Done), (Format-Megabytes $Progress.Total), $percent)
}

function Complete-DownloadProgress {
    param($Progress)
    if ($Progress.Open) { Write-Host '' }
}

function Stop-OnDiskError {
    param([System.Exception]$Exception, [string]$Path)
    # ERROR_HANDLE_DISK_FULL (39) and ERROR_DISK_FULL (112)
    if ($Exception -is [System.IO.IOException] -and (($Exception.HResult -band 0xFFFF) -in 39, 112)) {
        Stop-Ftc 'DiskFull' "The disk is full while writing $Path." $Exception
    }
    if ($Exception -is [System.UnauthorizedAccessException]) {
        Stop-Ftc 'AccessDenied' "Access to $Path was denied." $Exception
    }
    throw $Exception
}

# One attempt at filling $Destination up to $ExpectedSize, continuing from what is already there.
function Receive-FileSegment {
    param($Manifest, [Uri]$Uri, [string]$Destination, [long]$ExpectedSize, [string]$Label, $State)

    $existing = 0
    if (Test-Path -LiteralPath $Destination -PathType Leaf) { $existing = (Get-Item -LiteralPath $Destination).Length }
    if ($existing -gt $ExpectedSize) { Remove-Item -LiteralPath $Destination -Force; $existing = 0 }
    if ($existing -eq $ExpectedSize) { return }

    try {
        $response = Open-AllowedResponse $Manifest $Uri $existing
    } catch [System.Net.WebException] {
        # 416: the partial file does not fit the file the server has now, so start again.
        if ($existing -le 0 -or (Get-HttpStatus $_.Exception) -ne 416) { throw }
        Remove-Item -LiteralPath $Destination -Force
        $existing = 0
        $response = Open-AllowedResponse $Manifest $Uri 0
    }
    try {
        $status = [int]$response.StatusCode
        if ($existing -gt 0 -and $status -eq 206) {
            if ($response.Headers['Content-Range'] -notmatch "^bytes $existing-\d+/(\d+|\*)$") {
                # A proxy or server that mangles ranges must not leave a partial file that fails the same way on every later run.
                Remove-Item -LiteralPath $Destination -Force
                $wrong = New-FtcException 'DownloadFailed' "The server resumed at the wrong position ($($response.Headers['Content-Range']))."
                $wrong.Data['FtcTransient'] = $true
                throw $wrong
            }
            $State.Resumed = $true
        } elseif ($existing -gt 0) {
            $existing = 0
        }
        if ($existing -eq 0 -and $response.ContentLength -ge 0 -and $response.ContentLength -ne $ExpectedSize) {
            Stop-Ftc 'DownloadFailed' "The server announced $($response.ContentLength) bytes for $Label but the release metadata says $ExpectedSize."
        }

        $progress = New-DownloadProgress $Label $ExpectedSize
        try {
            $file = New-Object System.IO.FileStream($Destination, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
        } catch {
            Stop-OnDiskError (Get-BaseException $_.Exception) $Destination
        }
        try {
            [void]$file.Seek($existing, [System.IO.SeekOrigin]::Begin)
            $file.SetLength($existing)
            $stream = $response.GetResponseStream()
            $buffer = New-Object byte[] 262144
            $written = $existing
            while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                try { $file.Write($buffer, 0, $read) } catch { Stop-OnDiskError (Get-BaseException $_.Exception) $Destination }
                $written += $read
                Update-DownloadProgress $progress $written
            }
            Complete-DownloadProgress $progress
        } finally {
            $file.Dispose()
        }
        if ($written -ne $ExpectedSize) {
            $cut = New-FtcException 'DownloadFailed' "The connection ended after $written of $ExpectedSize bytes."
            $cut.Data['FtcTransient'] = $true
            throw $cut
        }
    } finally {
        $response.Close()
    }
}

# Downloads to $Destination (a .part file that survives failures so the next run resumes it).
# Returns $true when any bytes of an earlier attempt were reused, so the caller can tell a bad resume
# from a bad download if the checksum does not match.
function Save-AllowedFile {
    param($Manifest, [Uri]$Uri, [string]$Destination, [long]$ExpectedSize, [string]$Label)
    $state = @{ Resumed = $false }
    Invoke-WithRetry $Manifest "Downloading $Label" {
        param($attempt)
        Receive-FileSegment $Manifest $Uri $Destination $ExpectedSize $Label $state
    } | Out-Null
    $state.Resumed
}

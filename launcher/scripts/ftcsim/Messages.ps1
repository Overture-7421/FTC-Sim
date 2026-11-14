# Messages and the failure taxonomy of stage 0.
#
# Every failure has one kind, one exit code and one piece of advice, so the .cmd wrapper, the tests and
# support agree on what a code means. Exit codes below 79 belong to the application that stage 0 starts (the
# application's own exit code is passed on as it is); 79 is reported by FTC-Sim.cmd itself when this script
# could not be started at all.
#
# This file must stay restricted-language safe: top level code is a hash table literal and the functions
# below only use cmdlets, so a locked-down PowerShell can still show a readable message.

$script:FtcFailures = @{
    UnsupportedOs    = @{ Code = 80; Advice = @(
        'FTC-Sim runs on 64-bit Windows 10 or 11 with an Intel or AMD (x64) processor.',
        'Please use a different PC. Nothing was installed or changed on this one.') }
    Restricted       = @{ Code = 81; Advice = @(
        'A security setting of this PC (for example Smart App Control or a school or organization policy) limits PowerShell.',
        'That is not something you did, and you do not need to install anything.',
        'Please show this window to your IT administrator or team mentor.',
        'For the IT administrator: allow the FTC-Sim setup script (FTC-Sim.ps1 and the ftcsim folder next to it) to run,',
        'or install, for all users (Program Files), the Eclipse Temurin JDK that runtime.exactRelease names in jdk-manifest.json (next to FTC-Sim.ps1);',
        'FTC-Sim finds exactly that build automatically and then needs no download. Another Java is not used.') }
    NoRuntime        = @{ Code = 82; Advice = @(
        'No compatible Java runtime is available and automatic setup was switched off (-NoProvision).') }
    Offline          = @{ Code = 83; Advice = @(
        'FTC-Sim downloads its Java runtime once (about 140 MB) and could not reach the internet.',
        'Check the network connection, then run FTC-Sim again: the download continues where it stopped.',
        'FTC-Sim uses the proxy settings of Windows. On a school or work network, the proxy or firewall must allow the addresses listed above.') }
    DownloadFailed   = @{ Code = 84; Advice = @(
        'Run FTC-Sim again to retry: the download continues where it stopped.',
        'If it keeps failing, ask your IT administrator whether this network blocks the addresses listed above.') }
    ChecksumMismatch = @{ Code = 85; Advice = @(
        'The downloaded file did not match its published checksum, so FTC-Sim discarded it and did not use it.',
        'Networks that alter downloads (content filters, TLS inspection) can cause this. Run FTC-Sim again;',
        'if it repeats, ask your IT administrator to allow downloads from the addresses above unmodified.') }
    MetadataInvalid  = @{ Code = 86; Advice = @(
        'The download service or the local runtime manifest returned something FTC-Sim does not understand.',
        'Try again later; if it keeps happening FTC-Sim needs an update.') }
    DiskFull         = @{ Code = 87; Advice = @(
        'Free some disk space and run FTC-Sim again. The Java runtime is installed once and only needs the space once.') }
    UnsafeArchive    = @{ Code = 88; Advice = @(
        'The downloaded archive was rejected for safety and nothing was installed.',
        'Run FTC-Sim again; if it repeats, report this message to the FTC-Sim maintainers.') }
    RuntimeBlocked   = @{ Code = 89; Advice = @(
        'Windows security (AppLocker or Windows Defender Application Control, antivirus, or a school policy) is probably blocking programs in that folder.',
        'You do not need to install anything. Please show this window to your IT administrator or team mentor.',
        'For the IT administrator: allow programs from the folder named above, or install, for all users (Program Files), the Eclipse Temurin JDK',
        'that runtime.exactRelease names in jdk-manifest.json (next to FTC-Sim.ps1); FTC-Sim finds exactly that build automatically.') }
    LockTimeout      = @{ Code = 90; Advice = @(
        'Close any other FTC-Sim windows and run FTC-Sim again.') }
    AccessDenied     = @{ Code = 91; Advice = @(
        'FTC-Sim could not write to its own data folder. Check that the folder is on a normal local disk that your account can write to.') }
    LaunchFailed     = @{ Code = 92; Advice = @(
        'FTC-Sim could not start its own program with the Java runtime, which was found.',
        'Unzip the FTC-Sim download again into a new folder and start FTC-Sim.cmd from there; do not move FTC-Sim.cmd out of its folder.',
        'If it still fails, show this window to your team mentor or IT administrator.') }
    BadArguments     = @{ Code = 93; Advice = @(
        'Check the command line options.') }
    Unexpected       = @{ Code = 94; Advice = @(
        'This is a bug in FTC-Sim. Please report this message to the FTC-Sim maintainers.') }
}

function Write-FtcInfo {
    param([string]$Text)
    Write-Host $Text
}

function New-FtcException {
    param([string]$Kind, [string]$Message, $Inner = $null)
    $exception = [System.Exception]::new($Message, $Inner)
    $exception.Data['FtcKind'] = $Kind
    $exception
}

function Stop-Ftc {
    param([string]$Kind, [string]$Message, $Inner = $null)
    throw (New-FtcException $Kind $Message $Inner)
}

function Get-FtcKind {
    param($ErrorRecord)
    $data = $ErrorRecord.Exception.Data
    if ($data -and $data.Contains('FtcKind')) { return [string]$data['FtcKind'] }
    'Unexpected'
}

# Prints the failure and returns the exit code to use.
function Write-FtcFailure {
    param([string]$Kind, [string]$Message)
    $failure = $script:FtcFailures[$Kind]
    if (-not $failure) { $failure = $script:FtcFailures['Unexpected'] }
    Write-Host ''
    Write-Host ('FTC-Sim could not start: ' + $Message) -ForegroundColor Red
    foreach ($line in $failure.Advice) { Write-Host ('  ' + $line) }
    Write-Host ('  [FTCSIM-' + $Kind.ToUpperInvariant() + ', exit code ' + $failure.Code + ']')
    $failure.Code
}

# Starts the FTC-Sim application on the Java runtime that was found or downloaded, keeps everything it writes in a log file
# a student can send, and hands its exit code back.
#
# The application runs as 'javaw.exe -jar lib\app.jar' (the jar's manifest names the other jars of lib, so the command line
# stays short). It is started through a hidden cmd.exe because cmd gives the program one shared handle for standard output and
# standard error: its output and the Java runtime's own messages, including a crash report (-XX:+ErrorFileToStdout), land in
# the log in the order they were written, and there is no console window. The exit code of cmd /c is the application's.

$script:ApplicationLogsKept = 20

# Windows' own rules for %LOCALAPPDATA%, as the application applies them: the variable of this process wins, so a test or a
# roaming profile can move the folder, and the profile's folder is the fallback.
function Get-LocalAppDataFolder {
    $value = $env:LOCALAPPDATA
    if ($value) { $value = $value.Trim().Trim('"') }
    if ($value) { return $value }
    [Environment]::GetFolderPath('LocalApplicationData')
}

# javaw.exe has no console of its own; a runtime without it is run with java.exe, whose console is hidden anyway. The
# runtime's own folder is preferred to the folder the candidate was found in, which can be a launcher shim such as
# Oracle's Common Files\Oracle\Java\javapath.
function Get-ApplicationLauncher {
    param($Facts)
    $candidates = @(Join-Path (Split-Path -Parent $Facts.JavaExe) 'javaw.exe')
    if ($Facts.Home) { $candidates = @(Join-Path $Facts.Home 'bin\javaw.exe') + $candidates }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $Facts.JavaExe
}

# One argument of the command line cmd.exe is given. Inside double quotes cmd treats & | < > ^ ( ) literally, but a quote or a
# percent sign would still be interpreted by cmd, so those are refused rather than passed on wrongly.
function ConvertTo-CmdArgument {
    param([string]$Text)
    if ($Text -match '[%"\r\n]') {
        Stop-Ftc 'BadArguments' "FTC-Sim cannot pass '$Text' on to the application: names and options must not contain a quote or a percent sign."
    }
    '"' + ($Text -replace '(\\+)$', '$1$1') + '"'
}

function Get-ApplicationCommandLine {
    param([string]$Launcher, [string[]]$JvmArguments, [string]$Jar, [string[]]$UserArguments, [string]$Log)
    $arguments = @($Launcher) + @($JvmArguments) + @(
        '-XX:+ErrorFileToStdout', '-XX:-CreateCoredumpOnCrash', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $Jar)
    if ($UserArguments) { $arguments += @($UserArguments) }
    $program = ($arguments | ForEach-Object { ConvertTo-CmdArgument $_ }) -join ' '
    # /s removes the outermost pair of quotes and keeps the ones inside.
    '/d /v:off /s /c "' + $program + ' > ' + (ConvertTo-CmdArgument $Log) + ' 2>&1"'
}

# Creates an empty log whose name has not been used, so that two launches in the same second keep their own.
function New-ApplicationLog {
    param([string]$Directory)
    $stamp = [DateTime]::Now.ToString('yyyyMMdd-HHmmss', [System.Globalization.CultureInfo]::InvariantCulture)
    for ($attempt = 0; ; $attempt++) {
        $suffix = ''
        if ($attempt -gt 0) { $suffix = "-$attempt" }
        $path = Join-Path $Directory "app-$stamp$suffix.log"
        try {
            [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite).Dispose()
            return $path
        } catch [System.IO.IOException] {
            if ($attempt -ge 50) { throw }
        }
    }
}

# The newest logs stay; the worker's own logs in the same folder are kept by the application.
function Remove-OldApplicationLogs {
    param([string]$Directory)
    $old = @(Get-ChildItem -LiteralPath $Directory -Filter 'app-*.log' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -Skip ($script:ApplicationLogsKept - 1))
    foreach ($file in $old) { Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue }
}

function Start-ApplicationProcess {
    param([string]$Arguments)
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $env:SystemRoot 'System32\cmd.exe'
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    try {
        [System.Diagnostics.Process]::Start($info)
    } catch [System.ComponentModel.Win32Exception] {
        Stop-Ftc 'LaunchFailed' "Windows would not start the application ($($_.Exception.Message))." $_.Exception
    }
}

# Shows what the application writes while it runs, so that a terminal sees what the log file gets. Returns whether the
# application ended; with a watch time it stops waiting after that long, which is how a double-click lets its console close.
function Wait-Application {
    param($Process, [string]$Log, [int]$WatchSeconds)
    $share = [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
    $stream = [System.IO.FileStream]::new($Log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, $share)
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($WatchSeconds)
        $buffer = New-Object 'char[]' 4096
        while ($true) {
            $ended = $Process.WaitForExit(100)
            while (($count = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) { Write-Host ([string]::new($buffer, 0, $count)) -NoNewline }
            if ($ended) { return $true }
            if ($WatchSeconds -gt 0 -and [DateTime]::UtcNow -ge $deadline) { return $false }
        }
    } finally {
        $reader.Dispose()
    }
}

# Sets $script:ExitCode to the application's own exit code; with -Detach it leaves the application running once it has
# survived the watch time, and reports success.
function Start-Application {
    param($Manifest, $Resolution, [string]$CacheRoot, [string]$Jar, [string[]]$UserArguments, [bool]$Detach, [int]$WatchSeconds)

    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
        Stop-Ftc 'LaunchFailed' "FTC-Sim's program file is missing: $Jar"
    }
    # Refuses what cannot be put on the command line safely before anything is created.
    foreach ($text in @($Jar, $CacheRoot) + @($UserArguments)) { [void](ConvertTo-CmdArgument $text) }
    $logDirectory = Join-Path $CacheRoot 'logs'
    try {
        [void][System.IO.Directory]::CreateDirectory($logDirectory)
        Remove-OldApplicationLogs $logDirectory
        $log = New-ApplicationLog $logDirectory
    } catch [System.UnauthorizedAccessException], [System.IO.IOException] {
        Stop-Ftc 'AccessDenied' "FTC-Sim could not write its log in $logDirectory ($($_.Exception.Message))." $_.Exception
    }

    $command = Get-ApplicationCommandLine (Get-ApplicationLauncher $Resolution.Facts) @($Manifest.application.jvmArguments) $Jar $UserArguments $log
    Write-FtcInfo "Starting FTC-Sim. Its log is $log"
    $process = Start-ApplicationProcess $command
    $watch = 0
    if ($Detach) { $watch = $WatchSeconds }
    if (-not (Wait-Application $process $log $watch)) {
        $script:ExitCode = 0
        return
    }
    $script:ExitCode = $process.ExitCode
    if ($script:ExitCode -ne 0) {
        Write-FtcInfo ''
        Write-FtcInfo "FTC-Sim stopped with exit code $($script:ExitCode). What it wrote is above and in $log; please give that file to whoever is helping you."
    }
}

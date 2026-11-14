# FTC-Sim stage 0: runs on a PC with no Java installed (Windows PowerShell 5.1 only).
# Finds a usable 64-bit Java runtime or downloads the pinned one into %LOCALAPPDATA%\FTC-Sim, then starts the application
# (lib\app.jar) with it and passes its exit code on. It never changes JAVA_HOME, PATH, the registry or any machine-wide
# setting, and needs no administrator rights.
#
# Which runtime the simulator gets: exactly the Java major version pinned in jdk-manifest.json (runtime.major, currently 25) on
# 64-bit x64 Windows and, because the shipped manifest also pins a release (runtime.exactRelease and exactSha256), exactly that
# build, so that FTC-Sim behaves the same on every PC. Any other version (21, 17, 26, or another build of 25) is never used
# silently, however it was found: JAVA_HOME, the first java on PATH, the registry, Program Files, Android Studio's JBR or an
# editor's bundled JRE are all candidates and all are judged the same way. A candidate that is passed over is reported (JAVA_HOME
# while starting, every candidate with -ListJdks) and the pinned Temurin JDK is downloaded instead, by its release name, and
# refused unless its SHA-256 is the pinned one. A JDK and a JRE both qualify, from any vendor: the simulator needs java.exe and
# no compiler, so javac is not required. Importing a team's project runs that project's own Gradle build on a JDK this script
# does not choose.
#
# The application is started with javaw.exe and the options of the manifest's application.jvmArguments. Whatever it writes to
# standard output and standard error, and a crash report of the Java runtime, goes to %LOCALAPPDATA%\FTC-Sim\logs\app-<time>.log
# and is shown here while it runs. Options this script does not know are the application's and are passed on unchanged.
#
#   (no option)             wait for the application and return its exit code
#   -Detach                 return 0 once the application has run for -StartupWatchSeconds without ending, so that the console
#                           of a double-click closes; an application that ends sooner is reported with its exit code (FTC-Sim.cmd
#                           passes this when it was started by double-click without options)
#   -RuntimePolicy Pinned   (default, what the product uses) only the pinned runtime (major version, and release when the manifest
#                           pins one) is acceptable; anything else is ignored and the pinned JDK is downloaded
#   -RuntimePolicy Any      developer diagnostic, never a default and never passed by the product: the pinned major version
#                           and newer, of any build, are acceptable; the pinned JDK is downloaded only when none is found
#   -ResolveOnly            print the result as one 'FTCSIM-RESULT {json}' line and do not start the application
#   -ListJdks               print every candidate with the reason it was accepted or rejected
#   -NoProvision            never download; fail when nothing usable is found
#   -CacheRoot, -ManifestPath, -AppJar, -ProxyUrl, -DiscoverySources, -IgnoreSystemJdks  overrides, mainly for tests and diagnostics
#
# Exit codes: the application's own exit code when it was started (the self test uses 0 and 1, the window 0); 79 means
# FTC-Sim.cmd could not start this script; 80-94 are the failures in ftcsim\Messages.ps1.
#
# Positional binding is off so that arguments meant for the application are never absorbed by -ProxyUrl or -AppJar.
[CmdletBinding(PositionalBinding = $false)]
param(
    [ValidateSet('Pinned', 'Any')] [string]$RuntimePolicy = 'Pinned',
    [string]$CacheRoot,
    [string]$ManifestPath,
    [string]$DiscoverySources,
    [switch]$IgnoreSystemJdks,
    [switch]$NoProvision,
    [switch]$ResolveOnly,
    [switch]$ListJdks,
    [switch]$NonInteractive,
    [switch]$Detach,
    [int]$StartupWatchSeconds = 6,
    [string]$ProxyUrl,
    [string]$AppJar,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]]$AppArguments
)

# FTC-Sim.cmd creates this file before starting us; deleting it tells the wrapper the script really ran.
if ($env:FTCSIM_STAGE0_MARK) { Remove-Item -LiteralPath $env:FTCSIM_STAGE0_MARK -Force -ErrorAction SilentlyContinue }

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. (Join-Path $PSScriptRoot 'ftcsim\Messages.ps1')

# A restricted PowerShell cannot run this script's .NET calls. Detect it and explain; never try to get around it.
if ($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage') {
    exit (Write-FtcFailure 'Restricted' "PowerShell is running in '$($ExecutionContext.SessionState.LanguageMode)' mode, which does not let FTC-Sim's setup script run.")
}
Set-StrictMode -Version Latest

# Every other file in ftcsim\ only defines functions; they are loaded in name order.
foreach ($library in @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'ftcsim') -Filter '*.ps1' | Where-Object { $_.Name -ne 'Messages.ps1' } | Sort-Object Name)) {
    . $library.FullName
}

$script:ExitCode = 0

function Get-SelectedSources {
    if ($IgnoreSystemJdks) { return @('Cache') }
    if (-not $DiscoverySources) { return $script:AllDiscoverySources }
    $names = @($DiscoverySources -split '[,;\s]+' | Where-Object { $_ })
    foreach ($name in $names) {
        if ($script:AllDiscoverySources -notcontains $name) {
            Stop-Ftc 'BadArguments' "Unknown discovery source '$name'. Choose from: $($script:AllDiscoverySources -join ', ')."
        }
    }
    $names
}

function Read-InstallRecord {
    param([string]$JdkHome)
    $path = Join-Path $JdkHome 'ftcsim-install.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { [System.IO.File]::ReadAllText($path) | ConvertFrom-Json } catch { $null }
}

function Write-ResolveResult {
    param($Manifest, $Resolution)
    $facts = $Resolution.Facts
    $result = [ordered]@{
        home = $facts.Home; javaExe = $facts.JavaExe; version = $facts.Version; runtimeVersion = $facts.RuntimeVersion
        vendor = $facts.Vendor; arch = $facts.Arch; major = $facts.Major; source = $facts.Source; policy = $RuntimePolicy
        pinnedMatch = ($null -eq (Get-PolicyRejection $facts 'Pinned' $Manifest.runtime)); installedNow = $Resolution.Installed
        install = (Read-InstallRecord $facts.Home)
    }
    Write-Output ('FTCSIM-RESULT ' + ($result | ConvertTo-Json -Compress -Depth 4))
}

function Write-CandidateList {
    param($Search)
    if ($Search.Evaluated.Count -eq 0) { Write-FtcInfo 'No Java installation was found in the selected places.'; return }
    foreach ($entry in $Search.Evaluated) {
        $facts = $entry.Facts
        $verdict = 'accepted'
        if ($entry.Rejection) { $verdict = 'rejected: ' + $entry.Rejection }
        Write-FtcInfo ('{0} | {1} | Java {2} {3} {4} | {5}' -f $facts.Source, $facts.JavaExe, $facts.Version, $facts.Vendor, $facts.Arch, $verdict)
    }
}

function Test-InteractiveConsole {
    (-not $NonInteractive) -and [Environment]::UserInteractive -and (-not [Console]::IsInputRedirected) -and (-not [Console]::IsOutputRedirected)
}

# Waiting for a key lets someone who double-clicked the launcher retry after fixing the network.
function Read-RetryChoice {
    Write-Host '  Press R to try again, or any other key to close this window.'
    ([Console]::ReadKey($true)).Key -eq 'R'
}

function Invoke-Stage0 {
    Assert-SupportedPlatform
    if (-not $ManifestPath) { $ManifestPath = Join-Path $PSScriptRoot 'jdk-manifest.json' }
    if (-not $CacheRoot) { $CacheRoot = Join-Path (Get-LocalAppDataFolder) 'FTC-Sim' }
    try {
        $CacheRoot = [System.IO.Path]::GetFullPath($CacheRoot)
    } catch {
        Stop-Ftc 'BadArguments' "The data folder '$CacheRoot' is not a valid Windows path ($((Get-BaseException $_.Exception).Message))." $_.Exception
    }
    if (-not $AppJar) { $AppJar = Join-Path $PSScriptRoot 'lib\app.jar' }

    $manifest = Read-RuntimeManifest $ManifestPath
    Initialize-Networking $ProxyUrl
    $sources = Get-SelectedSources

    if ($ListJdks) {
        Write-CandidateList (Find-Jdk $manifest $CacheRoot $RuntimePolicy $sources -All)
        return
    }

    while ($true) {
        try {
            $resolution = Resolve-Runtime $manifest $CacheRoot $RuntimePolicy $sources (-not $NoProvision)
            break
        } catch {
            $kind = Get-FtcKind $_
            if (($kind -ne 'Offline' -and $kind -ne 'DownloadFailed') -or -not (Test-InteractiveConsole)) { throw }
            $script:ExitCode = Write-FtcFailure $kind $_.Exception.Message
            if (-not (Read-RetryChoice)) { return }
        }
    }

    Write-FtcInfo ('Using ' + (Format-JdkSummary $resolution.Facts) + " [$($resolution.Facts.Source)]")
    if ($ResolveOnly) { Write-ResolveResult $manifest $resolution; return }
    Start-Application $manifest $resolution $CacheRoot $AppJar $AppArguments $Detach $StartupWatchSeconds
}

try {
    Invoke-Stage0
} catch {
    $kind = Get-FtcKind $_
    $message = $_.Exception.Message
    if ($kind -eq 'Unexpected') { $message = "$($_.Exception.GetType().Name): $message at $($_.InvocationInfo.PositionMessage -replace '\s+', ' ')" }
    $script:ExitCode = Write-FtcFailure $kind $message
}
exit $script:ExitCode

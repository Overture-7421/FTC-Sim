# What this PC is. FTC-Sim supports 64-bit Windows 10 or 11 on Intel or AMD (x64) processors and says so up front
# instead of failing later with a download or crash that does not explain anything.

function Get-MachineArchitecture {
    try { return [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() } catch { Write-Verbose 'RuntimeInformation is not available.' }
    # .NET Framework before 4.7.1 has no RuntimeInformation; a 32-bit process reports the real architecture in PROCESSOR_ARCHITEW6432.
    if ($env:PROCESSOR_ARCHITEW6432) { return $env:PROCESSOR_ARCHITEW6432 }
    $env:PROCESSOR_ARCHITECTURE
}

function Assert-SupportedPlatform {
    $architecture = Get-MachineArchitecture
    if ($architecture -notin 'X64', 'AMD64') {
        Stop-Ftc 'UnsupportedOs' "This PC has a $architecture processor or a 32-bit Windows; FTC-Sim needs 64-bit Windows on an Intel or AMD (x64) processor."
    }
    $windows = [Environment]::OSVersion.Version
    if ($windows.Major -lt 10) {
        Stop-Ftc 'UnsupportedOs' "This PC runs Windows $($windows.Major).$($windows.Minor); FTC-Sim needs Windows 10 or 11."
    }
}

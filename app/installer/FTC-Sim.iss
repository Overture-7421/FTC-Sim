; The installer of FTC-Sim: wraps the distribution folder (build\dist\FTC-Sim, made by :app:dist) in a setup program for one
; Windows user. Compile it with Inno Setup 6 (the release workflow does):
;
;   ISCC.exe app\installer\FTC-Sim.iss /DAppVersion=1.2.3 /DSourceDir=<...>\app\build\dist\FTC-Sim /DOutputDir=<folder>
;
; It installs for the current user only (no administrator rights, nothing outside the user's profile), puts FTC-Sim in the
; Start menu, can put it on the desktop, lists it in "Apps & features" with an uninstaller, and, run with /SILENT, replaces an
; installation by the new version and starts it again: that is how FTC-Sim updates itself (docs\architecture\updates.md).
;
; The shortcuts start the bundled Java directly (javaw, so no console window) with the options FTC-Sim.cmd uses: they are in
; runtime-options, and the crash report of the Java goes to the logs folder of the user like the launcher's.
;
; The installer's name, FTC-Sim-<version>-Setup.exe, is what the application looks for in a release (GitHubUpdater.INSTALLER_NAME).

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\dist\FTC-Sim"
#endif
#ifndef OutputDir
  #define OutputDir "..\build\installer"
#endif

#define AppName "FTC-Sim"
; The Java arguments of every shortcut, as the text of a quoted [Icons]/[Run] Parameters field: each quote of the command line is
; written twice for that field, and each of those twice again for the string of this define. A Windows shortcut loses parameters
; that begin with "@", so the argument file of runtime-options does not come first.
#define JavaArguments "-XX:-CreateCoredumpOnCrash @""""{app}\runtime-options"""" """"-XX:ErrorFile={localappdata}\FTC-Sim\logs\hs_err_pid%p.log"""" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar """"{app}\lib\app.jar"""""

[Setup]
; The identity of the program for Windows: an installer with the same AppId upgrades the installation of an older one.
AppId={{A4D5C3F2-7B1E-4B8E-9C3A-5E2F6D7A8B91}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Overture 7421
AppPublisherURL=https://github.com/Overture-7421/FTC-Sim
AppSupportURL=https://github.com/Overture-7421/FTC-Sim/issues
AppUpdatesURL=https://github.com/Overture-7421/FTC-Sim/releases
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=FTC-Sim-{#AppVersion}-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=FTC-Sim.ico
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\FTC-Sim.ico
; A running FTC-Sim holds its files: the installer asks the running program to close (Restart Manager) instead of failing.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "FTC-Sim.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArguments}"; WorkingDir: "{app}"; IconFilename: "{app}\FTC-Sim.ico"; Comment: "FTC robot code simulator"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArguments}"; WorkingDir: "{app}"; IconFilename: "{app}\FTC-Sim.ico"; Tasks: desktopicon

[Run]
; Offered at the end of an interactive install, and done at the end of a silent one, which is how an update restarts FTC-Sim.
; /NORUN=1 leaves it out, for a test that installs without starting a window.
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArguments}"; WorkingDir: "{app}"; Description: "Start {#AppName}"; Flags: nowait postinstall; Check: StartsAfterInstall

[UninstallDelete]
; What FTC-Sim keeps for the user (%LOCALAPPDATA%\FTC-Sim: logs, prepared programs, settings) is theirs and stays; only the
; installers it fetched for updating go.
Type: filesandordirs; Name: "{localappdata}\FTC-Sim\updates"

[Code]
function StartsAfterInstall: Boolean;
begin
  Result := ExpandConstant('{param:NORUN|0}') <> '1';
end;

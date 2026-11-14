@echo off
rem FTC-Sim stage 0 entry point. Needs no Java: it starts the PowerShell bootstrap (FTC-Sim.ps1), which finds or
rem downloads a Java runtime and then starts the application. Options are passed through to the application, for example:
rem FTC-Sim.cmd --self-test (checks this installation, no window) or FTC-Sim.cmd --no-focus --exit-after=8. The bootstrap's
rem own options work too, for example: FTC-Sim.cmd -ListJdks. The exit code is the application's.
rem Started by double-click (no options), the console closes once the window has started; a failure keeps it open.
rem The window stays open after a failure when started by double-click; set FTCSIM_NO_PAUSE=1 to disable that and to
rem make a start without options wait for the application, as a script wants.
setlocal
if exist "%~dp0FTC-Sim.ps1" goto :start
echo.
echo FTC-Sim.cmd cannot find the rest of the FTC-Sim folder: FTC-Sim.ps1 should be next to it.
echo If you opened it from inside the zip file, close this window, right-click the zip file, choose Extract All,
echo and start FTC-Sim.cmd from the folder that was created. FTC-Sim.cmd cannot be moved out of that folder.
echo [FTCSIM-NOTUNZIPPED, exit code 79]
set "FTCSIM_RC=79"
goto :finish
:start
set "FTCSIM_STAGE0_MARK=%TEMP%\ftcsim-stage0-%RANDOM%%RANDOM%.tmp"
type nul > "%FTCSIM_STAGE0_MARK%" 2>nul
set "FTCSIM_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if defined PROCESSOR_ARCHITEW6432 set "FTCSIM_POWERSHELL=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
set "FTCSIM_DETACH="
rem The Windows find.exe is named in full: a find.exe of another toolset (Git for Windows, for one) can come first on the PATH.
if "%~1"=="" if not defined FTCSIM_NO_PAUSE echo %cmdcmdline% | "%WINDIR%\System32\find.exe" /i " /c " >nul && set "FTCSIM_DETACH=-Detach"
"%FTCSIM_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0FTC-Sim.ps1" %FTCSIM_DETACH% %*
set "FTCSIM_RC=%ERRORLEVEL%"
if exist "%FTCSIM_STAGE0_MARK%" del /q "%FTCSIM_STAGE0_MARK%" >nul 2>&1 & set "FTCSIM_RC=79" & echo. & echo FTC-Sim could not run its PowerShell setup script on this PC. & echo Windows PowerShell 5.1 is missing or a security policy blocks the script, for example an execution policy, AppLocker or Windows Defender Application Control. & echo Nothing was installed or changed, and you do not need to install anything. & echo Please show this window to your IT administrator or team mentor and ask them to allow "%~dp0FTC-Sim.ps1". & echo [FTCSIM-SCRIPTBLOCKED, exit code 79]
:finish
if not "%FTCSIM_RC%"=="0" if not defined FTCSIM_NO_PAUSE echo %cmdcmdline% | "%WINDIR%\System32\find.exe" /i " /c " >nul && pause
exit /b %FTCSIM_RC%

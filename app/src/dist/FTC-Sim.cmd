@echo off
rem FTC-Sim entry point. Needs nothing installed: it starts the application on the Java runtime that comes with it (the
rem runtime folder) with the options of runtime-options. Options are passed through to the application, for example:
rem FTC-Sim.cmd --self-test (checks this installation, no window) or FTC-Sim.cmd --no-focus --exit-after=8. The exit code is
rem the application's. The application writes what it prints to %LOCALAPPDATA%\FTC-Sim\logs\app-<time>.log as well, and the
rem report of a crash of the Java runtime goes to a file in the same folder.
rem The window stays open after a failure when started by double-click; set FTCSIM_NO_PAUSE=1 to disable that.
setlocal
set "FTCSIM_HOME=%~dp0"
if not exist "%FTCSIM_HOME%runtime\bin\java.exe" goto :notunzipped
if not exist "%FTCSIM_HOME%lib\app.jar" goto :notunzipped
if not exist "%FTCSIM_HOME%runtime-options" goto :notunzipped
set "FTCSIM_LOGS=%USERPROFILE%\AppData\Local\FTC-Sim\logs"
if defined LOCALAPPDATA set "FTCSIM_LOGS=%LOCALAPPDATA%\FTC-Sim\logs"
md "%FTCSIM_LOGS%" >nul 2>&1
rem The Java crash report is written into the logs folder, never beside the program or into the folder it was started from.
"%FTCSIM_HOME%runtime\bin\java.exe" @"%FTCSIM_HOME%runtime-options" "-XX:ErrorFile=%FTCSIM_LOGS%\hs_err_pid%%p.log" -XX:-CreateCoredumpOnCrash -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%FTCSIM_HOME%lib\app.jar" %*
set "FTCSIM_RC=%ERRORLEVEL%"
if "%FTCSIM_RC%"=="0" goto :finish
echo.
echo FTC-Sim stopped with exit code %FTCSIM_RC%. What it wrote is above and in %FTCSIM_LOGS%; please give the newest files of that folder to whoever is helping you.
echo If Windows or your antivirus program blocked the runtime folder of FTC-Sim, extract the zip file again, or ask for it to be allowed.
goto :finish
:notunzipped
echo.
echo FTC-Sim.cmd cannot find the rest of the FTC-Sim folder: runtime, lib and runtime-options should be next to it.
echo If you opened it from inside the zip file, close this window, right-click the zip file, choose Extract All,
echo and start FTC-Sim.cmd from the folder that was created. FTC-Sim.cmd cannot be moved out of that folder.
echo [FTCSIM-NOTUNZIPPED, exit code 79]
set "FTCSIM_RC=79"
:finish
rem The Windows find.exe is named in full: a find.exe of another toolset (Git for Windows, for one) can come first on the PATH.
if not "%FTCSIM_RC%"=="0" if not defined FTCSIM_NO_PAUSE echo %cmdcmdline% | "%WINDIR%\System32\find.exe" /i " /c " >nul && pause
exit /b %FTCSIM_RC%

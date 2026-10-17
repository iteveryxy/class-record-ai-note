@echo off
setlocal
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\push.ps1"
) else (
    pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\push.ps1"
)
set "CLASSNOTES_PUSH_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %CLASSNOTES_PUSH_EXIT%

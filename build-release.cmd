@echo off
setlocal
set "pwsh_path="
where pwsh.exe >nul 2>nul && set "pwsh_path=pwsh.exe"
if not defined pwsh_path if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "pwsh_path=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined pwsh_path if exist "%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe" set "pwsh_path=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"
if not defined pwsh_path (
    echo [Error] PowerShell 7 or later is required. Install it from https://aka.ms/powershell-release?tag=stable
    pause
    exit /b 1
)
"%pwsh_path%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
set "exit_code=%ERRORLEVEL%"
if not "%exit_code%"=="0" pause
exit /b %exit_code%

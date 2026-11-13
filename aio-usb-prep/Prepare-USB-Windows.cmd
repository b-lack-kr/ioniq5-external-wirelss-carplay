@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Prepare-USB.ps1"
set "prep_result=%errorlevel%"
pause
exit /b %prep_result%

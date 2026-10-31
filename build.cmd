@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set RC=%ERRORLEVEL%
if not "%RC%"=="0" echo.& echo BUILD FAILED (exit %RC%)
pause
exit /b %RC%

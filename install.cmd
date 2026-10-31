@echo off
rem .Neo-Client installer for Windows: copies the .Neo-Client folder into .minecraft\versions
setlocal
set "MC=%APPDATA%\.minecraft"
set "SRC=%~dp0.Neo-Client"
if not exist "%MC%\" (echo .minecraft not found: %MC%& goto fail)
if not exist "%SRC%\.Neo-Client.json" (echo missing: %SRC%\.Neo-Client.json& goto fail)
tasklist /fi "imagename eq MinecraftLauncher.exe" 2>nul | find /i "MinecraftLauncher.exe" >nul && (echo Minecraft Launcher is running. Close it completely and run again.& goto fail)
xcopy "%SRC%" "%MC%\versions\.Neo-Client\" /e /i /y /q >nul || goto fail
if not exist "%MC%\versions\.Neo-Client\.Neo-Client.json" goto fail
echo OK: %MC%\versions\.Neo-Client\.Neo-Client.json
pause
exit /b 0
:fail
echo INSTALL FAILED
pause
exit /b 1

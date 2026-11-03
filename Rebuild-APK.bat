@echo off
setlocal
title Youtubly - rebuild APK
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup\Setup-Youtubly.ps1" -Mode rebuild
echo.
pause

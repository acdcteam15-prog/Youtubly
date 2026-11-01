@echo off
setlocal
title Youtubly - setup
cd /d "%~dp0"
echo.
echo  Youtubly setup wizard. Keep this window open.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup\Setup-Youtubly.ps1" -Mode menu
echo.
pause

@echo off
setlocal
title Remoty - build the APK
cd /d "%~dp0"
rem Double-click. First time it installs Git + GitHub CLI and asks you to sign in to GitHub once; after that it needs nothing from you.
rem Optional: Rebuild-APK.bat what changed this time
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup\Build-Remoty.ps1" -Message "%*"
echo.
pause

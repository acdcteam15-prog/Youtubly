@echo off
rem Double-click this file. It does the whole laptop setup (asks for admin once) and starts the agent.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"

@echo off
cd /d "%~dp0"
if exist .venv\Scripts\python.exe (
  .venv\Scripts\python.exe remoty_agent.py
) else (
  python remoty_agent.py
)
pause

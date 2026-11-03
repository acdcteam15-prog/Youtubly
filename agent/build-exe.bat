@echo off
rem Builds RemotyAgent.exe (a window + tray app that can run hidden in the background). Double-click this file.
setlocal
cd /d "%~dp0"
echo === Building RemotyAgent.exe (takes a few minutes the first time) ===

set PY=
py -3.12 --version >nul 2>&1 && set PY=py -3.12
if not defined PY python --version >nul 2>&1 && set PY=python
if not defined PY (
  echo Python was not found. Install Python 3.12 from python.org ^(tick "Add python.exe to PATH"^) and run this again.
  pause & exit /b 1
)
echo Using: %PY%

%PY% -m venv .buildvenv || goto :fail
call .buildvenv\Scripts\activate.bat
python -m pip install --quiet --upgrade pip || goto :fail
python -m pip install --quiet -r requirements.txt pyinstaller || goto :fail
python make_icon.py || goto :fail

pyinstaller --noconfirm --clean --onefile --noconsole --name RemotyAgent --icon remoty.ico ^
  --collect-submodules uvicorn --collect-submodules mss --collect-submodules anyio --collect-submodules h11 ^
  --hidden-import pystray._win32 ^
  remoty_tray.py || goto :fail

copy /y dist\RemotyAgent.exe RemotyAgent.exe >nul
echo.
echo === Done: %~dp0RemotyAgent.exe ===
echo Double-click RemotyAgent.exe. Tick "Start with Windows", then click "Run in background".
pause
exit /b 0

:fail
echo.
echo *** The build failed. Read the message above. If it mentions Python 3.14, install Python 3.12 and run this again. ***
pause
exit /b 1

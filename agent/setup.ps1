# Remoty Agent setup for Windows 11.  Right-click > "Run with PowerShell" (it asks for admin once, for the firewall rule).
$ErrorActionPreference = "Stop"
trap { Write-Host "ERROR: $_" -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`""
    exit
}

Write-Host "1/5  Checking Python..." -ForegroundColor Cyan
$py = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $py) { Write-Host "Python 3.10+ is required: install it from python.org (tick 'Add to PATH') and run this again." -ForegroundColor Red; Read-Host "Enter to close"; exit 1 }

Write-Host "2/5  Creating the virtual environment and installing packages..." -ForegroundColor Cyan
if (-not (Test-Path .venv)) { python -m venv .venv }
& .\.venv\Scripts\python.exe -m pip install --quiet --upgrade pip
& .\.venv\Scripts\python.exe -m pip install --quiet -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "pip install failed (see the message above). If it mentions Python 3.14, install Python 3.12 from python.org and run setup.bat again." }

Write-Host "3/5  Finding the ZeroTier address of this laptop..." -ForegroundColor Cyan
$zt = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceAlias -like "ZeroTier*" } | Select-Object -First 1
$bind = "0.0.0.0"
if ($zt) { $bind = $zt.IPAddress; Write-Host "     ZeroTier address: $bind  (type this into the phone app)" -ForegroundColor Green }
else { Write-Host "     No ZeroTier adapter found. Install ZeroTier One on the laptop and join your network first. Using 0.0.0.0 for now." -ForegroundColor Yellow }

Write-Host "4/5  Writing config.json..." -ForegroundColor Cyan
& .\.venv\Scripts\python.exe -c "import remoty_agent" | Out-Null   # creates config.json with a fresh token
$cfg = Get-Content config.json -Raw | ConvertFrom-Json
$cfg.bind = $bind
[System.IO.File]::WriteAllText("$here\config.json", ($cfg | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding $false))   # UTF-8 without BOM
New-NetFirewallRule -DisplayName "Remoty Agent" -Direction Inbound -Protocol TCP -LocalPort $cfg.port -Action Allow -Profile Any -RemoteAddress LocalSubnet -ErrorAction SilentlyContinue | Out-Null

Write-Host "5/5  Starting the agent automatically when you sign in..." -ForegroundColor Cyan
$action  = New-ScheduledTaskAction -Execute "$here\.venv\Scripts\python.exe" -Argument "`"$here\remoty_agent.py`"" -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$set     = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName "RemotyAgent" -Action $action -Trigger $trigger -Settings $set -Force | Out-Null

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Laptop ZeroTier address : $bind"
Write-Host "  Port                    : $($cfg.port)"
Write-Host "  Pairing token           : $($cfg.token)"
Write-Host "  Allowed folders         : $($cfg.roots -join ', ')   (edit config.json to add more)"
Write-Host ""
Write-Host "Starting the agent now in a new window. Keep that window open."
Start-Process "$here\run-agent.bat"
Read-Host "Press Enter to close this window"

# Builds the Remoty APK on GitHub (free runners) and downloads it into the APK folder. ASCII only on purpose (Windows PowerShell 5.1).
param([string]$Message = "")
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
$RepoName = "Remoty"

function Say($m, $c = "Gray") { Write-Host $m -ForegroundColor $c }
function Step($m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Die($m) { Write-Host ""; Write-Host "[X] $m" -ForegroundColor Red; exit 1 }
function Refresh-Path { $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User") }
function Need($cmd, $wingetId, $label) {
    if (Get-Command $cmd -ErrorAction SilentlyContinue) { return }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { Die "$label is missing and winget is not available. Install $label by hand, then run this again." }
    Step "Installing $label (Windows may ask permission once)"
    winget install --id $wingetId -e --source winget --accept-package-agreements --accept-source-agreements
    Refresh-Path
    foreach ($p in @("C:\Program Files\Git\cmd", "C:\Program Files\GitHub CLI")) { if (Test-Path $p) { $env:Path += ";$p" } }
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { Die "$label was installed but is not visible yet. Close this window and run the file again." }
}

Remove-Item (Join-Path $Root "build-error.txt") -Force -ErrorAction SilentlyContinue

# ---- 0. is this really the project folder?
$need = @("gradlew", "settings.gradle.kts", "build.gradle.kts", "app\build.gradle.kts", "app\src\main\AndroidManifest.xml", ".github\workflows\build.yml")
$missing = @($need | Where-Object { -not (Test-Path (Join-Path $Root $_)) })
if ($missing.Count -gt 0) { Die "This is not the Remoty project folder ($Root). Missing: $($missing -join ', '). Unzip Remoty.zip so that settings.gradle.kts and the app folder sit next to Rebuild-APK.bat." }
$rootGradle = Join-Path $Root "build.gradle.kts"
if ((Get-Content $rootGradle).Count -gt 30) {
    Say "   The root build.gradle.kts holds the app module's settings (wrong file). Repairing it..." "Yellow"
    $fixed = @'
// AGP 9 has Kotlin built in (same setup as FamilySafeTube); the Compose plugin pins the Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
'@
    [IO.File]::WriteAllText($rootGradle, ($fixed -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding $false))
}
if ((Get-Content (Join-Path $Root "app\build.gradle.kts")).Count -lt 40) { Die "app\build.gradle.kts is too short to be the app module's file. Unzip Remoty.zip again over this folder (replace all files)." }

# ---- 1. tools
Step "Checking Git and the GitHub helper"
Need "git" "Git.Git" "Git"
Need "gh" "GitHub.cli" "GitHub CLI"

# ---- 2. GitHub sign-in (first time only)
Step "Checking the GitHub sign-in"
gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    Say "A web page opens. Sign in to GitHub and type the short code shown in this window." "Yellow"
    gh auth login --hostname github.com --git-protocol https --web --scopes "repo,workflow,read:org"
    if ($LASTEXITCODE -ne 0) { Die "GitHub sign-in did not finish. Run this file again." }
} else {
    $st = (gh auth status 2>&1 | Out-String)
    if ($st -notmatch "workflow") {
        Say "GitHub needs one more permission (workflow) to run the build. Follow the code on screen." "Yellow"
        gh auth refresh --hostname github.com --scopes workflow
        if ($LASTEXITCODE -ne 0) { Die "Could not add the workflow permission." }
    }
}
gh auth setup-git *> $null
$login = ("" + (gh api user --jq .login)).Trim()
$uid = ("" + (gh api user --jq .id)).Trim()
if (-not $login) { Die "Could not read your GitHub user name. Check the internet connection." }
Say "   Signed in as $login" "Green"

# ---- 3. local git repository
Step "Preparing the project"
if (-not (Test-Path (Join-Path $Root ".git"))) { git init -b main *> $null }
git branch -M main *> $null
if (-not (git config user.name)) { git config user.name $login }
if (-not (git config user.email)) { git config user.email "$uid+$login@users.noreply.github.com" }

$remote = ("" + (git remote get-url origin 2>$null)).Trim()
if (-not $remote) {
    gh repo view "$login/$RepoName" *> $null
    if ($LASTEXITCODE -ne 0) {
        Step "Creating the private repository $login/$RepoName"
        gh repo create "$login/$RepoName" --private --description "Remoty Android app"
        if ($LASTEXITCODE -ne 0) { Die "Could not create the repository." }
    }
    git remote add origin "https://github.com/$login/$RepoName.git"
}
$repo = ("" + (gh repo view --json nameWithOwner --jq .nameWithOwner)).Trim()
if (-not $repo) { Die "The 'origin' remote does not point to a GitHub repository you can use." }
Say "   Repository: $repo" "Green"

# ---- 4. save and upload
Step "Saving and uploading your project"
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    if (-not $Message.Trim()) { $Message = "Remoty build " + (Get-Date -Format "yyyy-MM-dd HH:mm") }
    git commit -q -m $Message
    Say "   Saved: $Message"
} else {
    Say "   No file changes since the last upload."
}
git push -u origin main 2>&1 | ForEach-Object { Say "   $_" }
if ($LASTEXITCODE -ne 0) {
    Say "   The upload was refused, trying to merge with what is on GitHub..." "Yellow"
    git pull origin main --allow-unrelated-histories --no-edit 2>&1 | ForEach-Object { Say "   $_" }
    git push -u origin main 2>&1 | ForEach-Object { Say "   $_" }
    if ($LASTEXITCODE -ne 0) { Die "Could not upload to GitHub. Copy the lines above and send them to Claude." }
}

# ---- 5. start the build
Step "Starting the build on GitHub"
$since = (Get-Date).ToUniversalTime().AddSeconds(-20)
$started = $false
for ($i = 0; $i -lt 8 -and -not $started; $i++) {
    gh workflow run build.yml --ref main *> $null
    if ($LASTEXITCODE -eq 0) { $started = $true } else { Start-Sleep -Seconds 5 }   # right after the first upload GitHub needs a moment
}
if (-not $started) { Die "Could not start the build workflow (build.yml). Check that .github/workflows was uploaded." }

$id = $null
for ($i = 0; $i -lt 40 -and -not $id; $i++) {
    Start-Sleep -Seconds 3
    $runs = "" + (gh run list --workflow build.yml --branch main --event workflow_dispatch --limit 5 --json databaseId,createdAt)
    if ($runs.Trim()) {
        foreach ($r in ($runs | ConvertFrom-Json)) {
            if (([DateTimeOffset]::Parse($r.createdAt)).UtcDateTime -ge $since) { $id = $r.databaseId; break }
        }
    }
}
if (-not $id) { Die "The build was requested but I could not find it. Open https://github.com/$repo/actions" }
$url = "https://github.com/$repo/actions/runs/$id"
Say "   Build page: $url"
Step "Building (the first time takes 20-30 minutes because it compiles ZeroTier; later builds take about 10). You can leave this window open."
gh run watch $id --exit-status --interval 10
$ok = ($LASTEXITCODE -eq 0)

# ---- 6. result
if (-not $ok) {
    $raw = @(gh run view $id --log-failed 2>&1 | ForEach-Object { ("" + $_) -replace '^[^\t]*\t[^\t]*\t\d{4}-\d{2}-\d{2}T[\d:\.]+Z ?', '' })
    $errs = @($raw | Where-Object { $_ -match '##\[error\]|error:|FAILED|Exception|not found|No such' } | Select-Object -First 30)
    $tail = @($raw | Where-Object { $_.Trim() } | Select-Object -Last 60)
    $text = "Remoty build failed. Run: $url`r`n`r`n--- error lines ---`r`n" + ($errs -join "`r`n") + "`r`n`r`n--- end of the log ---`r`n" + ($tail -join "`r`n")
    [IO.File]::WriteAllText((Join-Path $Root "build-error.txt"), $text, (New-Object System.Text.UTF8Encoding $false))
    Say ""
    Say "[X] The build failed. The error was saved in build-error.txt (opening it now)." "Red"
    Say "    Copy everything in it and send it to Claude." "Yellow"
    Start-Process notepad.exe (Join-Path $Root "build-error.txt")
    exit 1
}

Step "Downloading the APK"
$num = ("" + (gh run view $id --json number --jq .number)).Trim()
$out = Join-Path $Root "APK"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Filter *.apk -ErrorAction SilentlyContinue | Remove-Item -Force
$tmp = Join-Path $env:TEMP "remoty-dl-$id"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
gh run download $id --name remoty-debug-apk --dir $tmp
$apk = Get-ChildItem $tmp -Filter *.apk -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { Die "The build worked but the APK download failed. Open $url and download remoty-debug-apk there." }
$dest = Join-Path $out ("Remoty-build$num.apk")
Copy-Item $apk.FullName $dest -Force
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Say ""
Say "[OK] Your APK: $dest" "Green"
Start-Process explorer.exe $out

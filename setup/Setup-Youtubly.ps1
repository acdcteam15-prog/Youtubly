<#
  Youtubly setup wizard (no Android Studio, no command line needed).
  Started by START-HERE.bat (menu) or Rebuild-APK.bat (rebuild).
  Everything is ASCII on purpose: Windows PowerShell 5.1 mangles other characters.
#>
param([ValidateSet('menu', 'rebuild', 'everything')][string]$Mode = 'menu')

# Native tools (git, gh) write progress to stderr; we check exit codes ourselves instead of letting PowerShell throw.
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$OutDir  = Join-Path $Root 'APK'
$ErrFile = Join-Path $Root 'build-error.txt'
$KeyDir  = Join-Path $env:USERPROFILE 'Youtubly-signing'
$script:Repo = $null
$script:User = $null

# ---------------------------------------------------------------- small helpers
function Say([string]$t, [string]$c = 'Gray') { Write-Host $t -ForegroundColor $c }
function Step([string]$t) { Write-Host ''; Write-Host "== $t" -ForegroundColor Cyan }
function Ok([string]$t) { Write-Host "   OK  $t" -ForegroundColor Green }

function Ask([string]$q, [string]$default = '') {
    $suffix = ''
    if ($default) { $suffix = " [$default]" }
    $a = Read-Host "$q$suffix"
    if ([string]::IsNullOrWhiteSpace($a)) { return $default }
    return $a.Trim()
}

function YesNo([string]$q, [bool]$defaultYes = $true) {
    $hint = 'y/N'
    if ($defaultYes) { $hint = 'Y/n' }
    $a = Read-Host "$q ($hint)"
    if ([string]::IsNullOrWhiteSpace($a)) { return $defaultYes }
    return $a.Trim().ToLower().StartsWith('y')
}

function Refresh-Path {
    $m = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $u = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$m;$u"
    foreach ($d in @("$env:ProgramFiles\Git\cmd", "$env:ProgramFiles\GitHub CLI")) {
        if ((Test-Path $d) -and ($env:Path -notlike "*$d*")) { $env:Path = "$env:Path;$d" }
    }
}

function Has([string]$cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

# Runs gh and returns its text output, or $null if it failed.
function GhOut {
    $o = & gh @args 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return (($o | Out-String).Trim())
}

# ---------------------------------------------------------------- tools
function Ensure-Tool([string]$cmd, [string]$wingetId, [string]$nice) {
    if (Has $cmd) { Ok "$nice is installed"; return }
    Say "   Installing $nice (about a minute, a permission window may appear)..."
    if (-not (Has 'winget')) {
        throw "Windows' installer 'winget' is missing. Open the Microsoft Store, install 'App Installer', then start this again. (Or install $nice by hand.)"
    }
    & winget install --id $wingetId -e --silent --accept-package-agreements --accept-source-agreements
    Refresh-Path
    if (-not (Has $cmd)) { throw "$nice was installed but Windows does not see it yet. Close this window and double-click START-HERE.bat again." }
    Ok "$nice installed"
}

function Ensure-Tools {
    Step 'Step 1 - tools (Git and the GitHub helper)'
    Refresh-Path
    Ensure-Tool 'git' 'Git.Git' 'Git'
    Ensure-Tool 'gh' 'GitHub.cli' 'GitHub CLI'
}

# ---------------------------------------------------------------- GitHub account
function Ensure-Login {
    Step 'Step 2 - sign in to GitHub'
    & gh auth status *> $null
    if ($LASTEXITCODE -ne 0) {
        Say '   A web page opens. Sign in to GitHub there and type the short code that appears in this window.' 'Yellow'
        & gh auth login --hostname github.com --git-protocol https --web --scopes 'repo,workflow'
        if ($LASTEXITCODE -ne 0) { throw 'GitHub sign-in did not finish. Run this again.' }
    }
    & gh auth setup-git 2>$null
    $script:User = GhOut api user --jq .login
    if (-not $script:User) { throw 'Could not read your GitHub name. Run this again.' }
    Ok "Signed in as $($script:User)"
}

function Ensure-GitIdentity {
    $n = (& git config user.name 2>$null)
    $e = (& git config user.email 2>$null)
    if (-not $n) { & git config --global user.name $script:User }
    if (-not $e) {
        $id = GhOut api user --jq .id
        & git config --global user.email "$id+$($script:User)@users.noreply.github.com"
    }
}

# ---------------------------------------------------------------- repository
function Read-RepoFromGit {
    $remote = (& git remote get-url origin 2>$null)
    if ($remote) {
        $script:Repo = ($remote -replace '^.*github\.com[:/]', '' -replace '\.git$', '').Trim()
        return $true
    }
    return $false
}

function Ensure-Repo {
    Step 'Step 3 - your GitHub repository (where the app is built)'
    if (-not (Test-Path (Join-Path $Root '.git'))) { & git init -b main | Out-Null }
    Ensure-GitIdentity
    if (Read-RepoFromGit) { Ok "Repository: $($script:Repo)"; return }

    $name = Ask 'Name for the repository' 'Youtubly'
    Say '   Public: the app finds updates by itself (simplest, recommended).' 'Gray'
    Say '   Private: also works, but each phone then needs a read-only token in Settings > App updates > Advanced.' 'Gray'
    $private = YesNo 'Make it PRIVATE?' $false
    $full = "$($script:User)/$name"

    & git add -A
    & git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) { & git commit -m 'Youtubly' | Out-Null }
    & git branch -M main

    & gh repo view $full *> $null
    if ($LASTEXITCODE -eq 0) {
        Say "   $full already exists - connecting to it."
        & git remote add origin "https://github.com/$full.git"
        & git push -u origin main
    } else {
        $vis = '--public'
        if ($private) { $vis = '--private' }
        & gh repo create $full $vis --source . --remote origin --push
    }
    if ($LASTEXITCODE -ne 0) { throw 'Could not create or fill the repository on GitHub.' }
    [void](Read-RepoFromGit)
    Ok "Repository ready: $($script:Repo)"
}

# ---------------------------------------------------------------- signing key
function Find-Keytool {
    if (Has 'keytool') { return (Get-Command keytool).Source }
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Android\Android Studio\jbr"
    )
    foreach ($r in $roots) {
        if (Test-Path $r) {
            $k = Get-ChildItem $r -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($k) { return $k.FullName }
        }
    }
    return $null
}

function Set-Secret([string]$name, [string]$value) {
    & gh secret set $name --body $value --repo $script:Repo | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not save the secret $name on GitHub." }
}

function Save-Signing([string]$file, [string]$alias, [string]$storePw, [string]$keyPw) {
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($file))
    Set-Secret 'KEYSTORE_BASE64' $b64
    Set-Secret 'KEYSTORE_PASSWORD' $storePw
    Set-Secret 'KEY_ALIAS' $alias
    Set-Secret 'KEY_PASSWORD' $keyPw
    Ok 'Signing key saved in GitHub (as secrets only you can read)'
}

function Ensure-Signing([bool]$force = $false) {
    Step 'Step 4 - signing key (lets the app update itself later)'
    $list = GhOut secret list --repo $script:Repo
    if (($list -match 'KEYSTORE_BASE64') -and (-not $force)) { Ok 'A signing key is already saved'; return }

    Say '   Android only installs an update if it is signed with the SAME key as the app on your phone.' 'Gray'
    $debugKs = Join-Path $env:USERPROFILE '.android\debug.keystore'
    $hasDebug = Test-Path $debugKs
    Write-Host ''
    if ($hasDebug) { Write-Host '   1) Android Studio key found on this PC (keeps the app on your phone, no uninstall)  <- pick this if you ran the app from Android Studio' }
    else { Write-Host '   1) Android Studio key (not found on this PC)' }
    Write-Host '   2) I have my own key file (for example youtubly-release.jks from the old project)'
    Write-Host '   3) Make a brand new key (the old app must be uninstalled once first)'
    $def = '3'
    if ($hasDebug) { $def = '1' }
    $c = Ask '   Which one?' $def

    if ($c -eq '1') {
        if (-not $hasDebug) { throw 'No Android Studio key on this PC. Choose 2 or 3.' }
        Save-Signing $debugKs 'androiddebugkey' 'android' 'android'
    }
    elseif ($c -eq '2') {
        $f = (Ask '   Full path of the key file').Trim('"')
        if (-not (Test-Path $f)) { throw "File not found: $f" }
        $alias = Ask '   Key alias' 'youtubly'
        $sp = Read-Host '   Key file password'
        $kp = Read-Host '   Key password (Enter = same as the file password)'
        if ([string]::IsNullOrWhiteSpace($kp)) { $kp = $sp }
        $kt = Find-Keytool
        if ($kt) {
            & $kt -list -keystore $f -storepass $sp -alias $alias *> $null
            if ($LASTEXITCODE -ne 0) { throw 'That alias or password does not open the key file. Check them and try again.' }
        }
        Save-Signing $f $alias $sp $kp
    }
    else {
        $kt = Find-Keytool
        if (-not $kt) {
            Ensure-Tool 'keytool' 'EclipseAdoptium.Temurin.21.JDK' 'Java (only used once, to make the key)'
            $kt = Find-Keytool
        }
        if (-not $kt) { throw 'Java is installed but keytool was not found. Close this window and start again.' }
        New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
        $ks = Join-Path $KeyDir 'youtubly-release.jks'
        if (Test-Path $ks) { throw "$ks already exists. Use option 2 to use it." }
        $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
        & $kt -genkeypair -keystore $ks -alias youtubly -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $pw -keypass $pw -dname 'CN=Youtubly, O=Personal, C=AE' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the key.' }
        Set-Content -Path (Join-Path $KeyDir 'READ-ME-youtubly-key.txt') -Value @(
            'Youtubly signing key - KEEP A COPY OF THIS FOLDER (cloud / USB).',
            'Without it, no future update can be installed over the app on your phone.',
            'File: youtubly-release.jks',
            'Alias: youtubly',
            "Password: $pw"
        )
        Save-Signing $ks 'youtubly' $pw $pw
        Say "   Key folder: $KeyDir  <- back it up now" 'Yellow'
        Start-Process explorer.exe $KeyDir
    }
}

# ---------------------------------------------------------------- build + download
function Show-BuildError([string]$runId) {
    $log = & gh run view $runId --repo $script:Repo --log-failed 2>&1 | Out-String
    $lines = $log -split "`r?`n" | Select-Object -Last 150
    Set-Content -Path $ErrFile -Value $lines
    Write-Host ''
    Say '   The build failed. The error is saved here:' 'Red'
    Say "   $ErrFile" 'Yellow'
    Say '   Open it, copy everything, and send it to Claude - it will be fixed and you press Rebuild-APK.bat again.' 'Yellow'
    Start-Process notepad.exe $ErrFile
}

function Run-Release([string]$notes) {
    Step 'Building the signed APK on GitHub (about 10 minutes - you can leave this window open)'
    $start = (Get-Date).ToUniversalTime().AddSeconds(-10)

    $started = $false
    for ($i = 0; $i -lt 8 -and -not $started; $i++) {
        & gh workflow run release.yml --repo $script:Repo --ref main -f "notes=$notes" 2>$null
        if ($LASTEXITCODE -eq 0) { $started = $true } else { Say '   GitHub is still registering the build recipe, retrying...'; Start-Sleep 6 }
    }
    if (-not $started) { throw 'Could not start the build. Open the repository on github.com > Actions and check that Actions are enabled.' }

    $id = $null
    for ($i = 0; $i -lt 40 -and -not $id; $i++) {
        Start-Sleep 3
        $json = GhOut run list --repo $script:Repo --workflow release.yml --limit 5 --json databaseId,createdAt,event
        if ($json) {
            foreach ($r in ($json | ConvertFrom-Json)) {
                if ($r.event -eq 'workflow_dispatch' -and ([datetime]$r.createdAt).ToUniversalTime() -ge $start) { $id = "$($r.databaseId)"; break }
            }
        }
    }
    if (-not $id) { throw 'The build did not appear on GitHub. Open the Actions tab of your repository.' }

    & gh run watch $id --repo $script:Repo --exit-status
    if ($LASTEXITCODE -ne 0) { Show-BuildError $id; throw 'Build failed (see above).' }

    Step 'Downloading the APK'
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Get-ChildItem $OutDir -Filter *.apk -ErrorAction SilentlyContinue | Remove-Item -Force
    & gh release download --repo $script:Repo --pattern '*.apk' --dir $OutDir --clobber
    if ($LASTEXITCODE -ne 0) { throw 'The build worked but the download failed. Open the Releases page and download the APK there.' }
    $apk = Get-ChildItem $OutDir -Filter *.apk | Select-Object -First 1
    Ok "Your APK: $($apk.FullName)"
    Write-Host ''
    Say '   Put it on your phone (USB cable, Telegram "Saved messages", Google Drive...) and open it to install.' 'Green'
    Say "   Or open this page ON THE PHONE and tap the APK: https://github.com/$($script:Repo)/releases/latest" 'Green'
    Say '   After this first install, updates arrive inside the app: Settings > App updates.' 'Green'
    Start-Process explorer.exe $OutDir
}

# ---------------------------------------------------------------- the menu actions
function Do-Everything {
    Ensure-Tools
    Ensure-Login
    Ensure-Repo
    Ensure-Signing $false
    $notes = Ask "What's new? (shown in the app's update screen)" 'First build from GitHub'
    Run-Release $notes
}

function Do-Rebuild {
    Ensure-Tools
    Ensure-Login
    if (-not (Read-RepoFromGit)) { throw 'No repository yet. Run START-HERE.bat and choose 1 first.' }
    Step "Sending your changes to GitHub ($($script:Repo))"
    Ensure-GitIdentity

    $vp = Join-Path $Root 'version.properties'
    if (Test-Path $vp) {
        $text = Get-Content $vp -Raw
        if ($text -match '(?m)^versionName=(.+)$') {
            $cur = $Matches[1].Trim()
            $new = Ask "Visible version number (Enter keeps it)" $cur
            if ($new -ne $cur) { Set-Content -Path $vp -Value ($text -replace '(?m)^versionName=.+$', "versionName=$new") -NoNewline }
        }
    }

    & git add -A
    & git diff --cached --quiet
    $changed = ($LASTEXITCODE -ne 0)
    $notes = ''
    if ($changed) {
        $notes = Ask 'What changed? (shown in the update screen)' 'Improvements and fixes'
        & git commit -m $notes | Out-Null
    } else {
        $notes = Ask 'Nothing new to send. Notes for this build' 'Rebuild'
    }
    & git push origin main
    if ($LASTEXITCODE -ne 0) { throw 'Could not send the changes to GitHub.' }
    Ok 'Changes sent'
    Run-Release $notes
}

function Do-Signing {
    Ensure-Tools
    Ensure-Login
    if (-not (Read-RepoFromGit)) { throw 'No repository yet. Choose 1 first.' }
    Ensure-Signing $true
}

function Show-LastError {
    if (Test-Path $ErrFile) { Start-Process notepad.exe $ErrFile } else { Say '   No build error saved. Good.' 'Green' }
}

function Open-Releases {
    Refresh-Path
    if (-not (Read-RepoFromGit)) { throw 'No repository yet. Choose 1 first.' }
    Start-Process "https://github.com/$($script:Repo)/releases/latest"
}

function Guard([scriptblock]$action) {
    try { & $action }
    catch {
        Write-Host ''
        Write-Host "   !!  $($_.Exception.Message)" -ForegroundColor Red
        Say '   If this keeps happening, copy the red text and send it to Claude.' 'Yellow'
    }
}

# ---------------------------------------------------------------- entry point
Refresh-Path
if ($Mode -eq 'rebuild') { Guard { Do-Rebuild }; return }
if ($Mode -eq 'everything') { Guard { Do-Everything }; return }

while ($true) {
    Write-Host ''
    Write-Host '  ======================  YOUTUBLY SETUP  ======================' -ForegroundColor Cyan
    Write-Host '   1) First time: set everything up and build my APK'
    Write-Host '   2) Rebuild my APK after changes (same as Rebuild-APK.bat)'
    Write-Host '   3) Change or re-save the signing key'
    Write-Host '   4) Show the last build error'
    Write-Host '   5) Open the Releases page'
    Write-Host '   0) Close'
    $c = Read-Host '  Type a number and press Enter'
    switch ($c) {
        '1' { Guard { Do-Everything } }
        '2' { Guard { Do-Rebuild } }
        '3' { Guard { Do-Signing } }
        '4' { Guard { Show-LastError } }
        '5' { Guard { Open-Releases } }
        '0' { return }
        default { Say '   Please type one of the numbers above.' 'Yellow' }
    }
}

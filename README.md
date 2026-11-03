# Remoty

Do on your phone what you do at your Windows PC when Claude gives you a project zip:
send the zip, unzip it over the project folder, run the project's own script (for example `Rebuild-APK.bat`), answer its
questions live, and get the finished APK installed on the phone.

```
phone (Remoty app, ZeroTier inside)  <== encrypted ZeroTier ==>  laptop (Remoty Agent: unzip, run your .bat, return the APK)
```

ZeroTier runs **inside the app** (libzt): no ZeroTier app on the phone, no VPN slot used. The phone joins your network as its own
device; authorise it once in my.zerotier.com.

## 1. Laptop (once)
1. ZeroTier One on the laptop, joined to your network and authorised.
2. In `agent\` double-click `build-exe.bat` (needs Python 3.12 once, only to build). It makes `RemotyAgent.exe`.
3. Double-click `RemotyAgent.exe`. The window shows the ZeroTier address and pairing token. Tick **Start with Windows**, click **Run in background**.
4. Your project folders must be under the folder shown in the window (default `C:\Users\<you>\RemotyApps`; add more in `config.json` "roots").
   Move or clone your projects (for example FamilySafeTube) there.

## 2. Phone app: double-click `Rebuild-APK.bat`
First time it installs Git and the GitHub helper, you sign in to GitHub once in the browser, and it creates a private repository.
Then it uploads the project, builds on GitHub (first build 20-30 min, later ~10), and puts `Remoty-buildN.apk` in the `APK` folder.
Copy that APK to the phone once. After that you can update Remoty itself the same way as your other apps: share the new zip to Remoty,
it runs `Rebuild-APK.bat` on the laptop and installs the APK on the phone.
If the build fails, `build-error.txt` opens: send it to Claude. (Optional: signing secrets + the **Release** workflow for in-app updates.)

## Updating Remoty itself
Every build is signed with the same key (`signing/remoty-debug.keystore`), so a new APK installs over the old one.
Laptop tab > **Check for updates** asks the laptop for the newest `Remoty-buildN.apk` (made by `Rebuild-APK.bat`) and installs it;
the app also checks by itself when it connects. The key is a convenience key kept in your private repository: if you want a
secret one, use the **Release** workflow with the four signing secrets instead.

## 3. Every update
1. Build tab: choose the project folder (once; it is remembered), pick the zip (file picker, or Share > Remoty from your chat app/browser).
2. **Send the zip and check it**: the laptop says how many files will be replaced/new and which scripts it found
   (`Rebuild-APK.bat` is preselected, else `START-HERE.bat`). Tap **Run**.
3. The console is live. When the script asks something ("what changed?") type the answer in the box at the bottom.
   "Press any key to continue" is answered automatically.
4. When the script ends and an APK appeared, the phone downloads it and opens the installer. **Roll back** puts the replaced files back.

## More tools (Laptop tab)
- **Smart error panel**: a failed job shows the real errors (file and line) at the top; **Copy for Claude** copies them with the log.
- **Diagnostics**: one tap copies versions, connection state, recent app log and any crash (never the token).
- **Live progress notification** while the app is in the background, with Stop; an "APK ready" notification with an Install button.
- **Keep the laptop awake** while a job runs (Windows will not sleep mid-build).
- **Remote screen**: see the laptop and click, drag, scroll and type. Off by default: tick "Allow remote control" in the agent window.
- **Activity log**: every action the phone took (also in `data\audit.log` next to the agent; the agent window has an Activity log button).
- **Build on GitHub (laptop off)**: needs a fine-grained GitHub token (Contents + Actions, read and write). The phone commits the zip's files to
  your repository, runs its workflow (`build.yml`, `workflow_dispatch`), and installs the APK it produces. Works from the first screen, before connecting.

## Limits (be aware)
- Scripts run with piped input, not a real console. Normal questions (`Read-Host`, `pause`) work. Hidden-typing prompts
  (`Read-Host -AsSecureString`, e.g. pasting the Drive key in the first-time wizard) cannot: do the first-time setup (menu 1/4) once at the PC.
- Success = exit code 0 and no new `build-error.txt`. If the script fails, its `build-error.txt` is shown in the log and in the error report.
- A zip is unzipped over the folder: files in the zip replace the old ones, files not in the zip stay. `.git` inside a zip is ignored.
- Scripts can do anything on the laptop: only run zips you trust. The agent runs only scripts inside the project folder, only .bat/.cmd/.ps1.

## Security
- Listens on the ZeroTier address only, needs the pairing token, locks out after 5 wrong tries.
- Only touches folders listed in `roots`; zip entries with `..` or absolute paths are rejected.
- The token is stored encrypted with the Android Keystore. Optional fingerprint/PIN lock for the app.
- The preview relay (used only when a script prints a localhost web address) is not token-protected: anyone you authorised on your ZeroTier network could open it.
- libzt is BSL 1.1 licensed (free for personal/internal/non-commercial use).
- Nothing in the Android app has been compiled by the author: the first CI run is the real test. Send the log if it fails.

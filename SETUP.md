# Youtubly: build and update with GitHub - no Android Studio

Two files do everything, like in Dishy:

| File | What it does |
|---|---|
| **START-HERE.bat** | Menu. Choose **1** the first time. |
| **Rebuild-APK.bat** | After you (or Claude) changed something: sends it to GitHub, builds, downloads the new APK. |

## First time (about 20 minutes, mostly waiting)
1. Unzip the project anywhere and double-click **START-HERE.bat**.
2. Type **1**. The wizard then, by itself:
   - installs **Git** and the **GitHub helper** (Windows asks permission once),
   - opens a web page: sign in to GitHub and type the short code shown in the window (create a free account first if you have none),
   - creates your repository and uploads the project,
   - sets up the **signing key** (see below),
   - builds the signed APK on GitHub (~10 min) and **downloads it into the `APK` folder**.
3. Copy the APK to your phone and open it. Done.

### The signing key (the one question the wizard asks)
Android installs an update only when it is signed with the same key as the app on the phone.
- You ran Youtubly from Android Studio before: pick **1**. The wizard finds that key on this PC; the app and its data stay.
- You have `youtubly-release.jks` from the old project: pick **2**.
- Fresh start: pick **3**. A new key is made in `C:\Users\<you>\Youtubly-signing` (**back that folder up**, it is the only copy; uninstall the old app first and export your playlists before).

## Every later update
1. Claude gives you a new project zip: unzip it over your folder (same place).
2. Double-click **Rebuild-APK.bat**, answer "what changed?", wait ~10 minutes, install the APK it downloads.
3. Or skip the PC for the install: on the phone, *Settings > App updates* shows the new build (also a banner on Home and a notification) and installs it with one tap.

If a build fails, the wizard saves the error as `build-error.txt` and opens it. Copy all of it and send it to Claude.

## What gets published
`Youtubly-1_1_0-build102.apk` plus a `.sha256` file on your repository's **Releases** page. The build number only goes up
(100 + GitHub's run number), and the app checks the checksum before installing.

## Keeping libraries current
All versions are in `gradle/libs.versions.toml`. GitHub's **Dependabot** opens a weekly pull request when something is outdated.
If YouTube playback breaks, bump `newpipe` there.

## Private repository?
Works, but each phone needs a read-only token: *Settings > App updates > Advanced*. A public repository needs nothing.

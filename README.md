# Youtubly v4

Personal-use, sideloaded YouTube client: your real YouTube Home without ads or Shorts.

## Build & install (no Android Studio needed)
Double-click **START-HERE.bat** and choose 1 - it installs what it needs, builds the app on GitHub and downloads the APK.
Later: **Rebuild-APK.bat**, or *Settings > App updates* on the phone. Details: **[SETUP.md](SETUP.md)**.

> Play Protect may still show its own "unknown app" prompt for any sideloaded APK: tap **More details > Install anyway**.
> Content blur needs nothing to download: five gender models are packed inside the app.

## Sign in
You tab → **Sign in to YouTube** → normal Google sign-in page inside the app.
No Google Cloud project is needed anymore; the old OAuth setup can be deleted.
The session stays in app-private storage only (backups disabled). Sign out wipes it.

## What's in v3
- **Your YouTube Home** (signed in) + Subscriptions, infinite scroll, **no Shorts anywhere**
- **Like / Subscribe / Watch later**, synced to your account
- **Picture-in-picture** when you leave the app mid-video (toggle in You → Settings)
- **Player settings** (gear icon): quality up to 4K, speed, captions + auto-translate
- **Chapters**: list under the video + markers on the seek bar
- **Resume where you left off** + red progress bars on thumbnails
- **DeArrow**: community titles/thumbnails instead of clickbait (tap title for original)
- You tab: profile, Watch later, Liked, Recently watched, Subscriptions

## Debugging
Logcat filters: `tag:Youtubly` (data), `tag:YoutublyInnertube` (account API),
`tag:YoutublyPlayer` (playback), `tag:YoutublyDeArrow`.

## v4 changes
- **Downloads, like, PiP and background playback fixes** — see below.
- **Background/lock-screen playback fixed**: the MediaController that keeps the app's
  player service alive was being garbage-collected right after backgrounding, which
  silently killed playback within seconds of leaving the app or locking the screen.
  It's now held for the app's lifetime.
- **Picture-in-picture is now more reliable**: added a manual PiP button (top-right on
  the video, when not in audio-only mode) as a guaranteed trigger, since `onUserLeaveHint`
  doesn't fire on every launcher/gesture-nav setup. Backgrounding the app while a video
  plays now also tries PiP from `onStop` as a second automatic path.
- **Keep screen on**: the screen no longer dims/locks while a video is actively playing,
  and stops holding it awake the instant playback pauses.
- **"Not interested" / "Don't recommend channel"**: on Home and Subscriptions, tap the
  ⋮ menu on a video card (when YouTube offers it) to remove it — the video disappears
  from the feed immediately and the feedback is sent to your account.

## v5 changes
- **Diagnostics** (You → System → Diagnostics): last 50 errors and crashes, kept across restarts, with a
  "copy report" button. Every failed request and final playback failure is recorded automatically.
- **Clear extractor errors**: "Playback source is outdated", "video unavailable", "age-restricted",
  "region-blocked" and "bot check" messages instead of generic failures. To update NewPipe, change
  `newPipeVersion` at the top of `app/build.gradle.kts` (the only place).
- **Content blur**: blurred thumbnails cached on disk (100 MB); "Don't blur this video" in the long-press
  menu (thumbnails + playback); "Scale with face size" so big faces get stronger blur; model SHA-256 with
  a copy button (paste it into `GENDER_MODEL_SHA256`).
- **Editor**: remembers your last resolution/bitrate; "Extract audio (.m4a)" keeps your trim and cuts;
  two-pane layout on tablets / landscape.
- **Playback**: sleep timer (15–90 min or end of video) with a 30 s fade, in the player's settings sheet;
  an interrupted playlist queue is offered back on Home; SponsorBlock categories are selectable
  (You → Settings → SponsorBlock categories), with "Filler" added and off by default.
- **System**: Storage screen (sizes; clear caches only), Backup & restore of all settings to a JSON file.
- **Polish**: illustrated empty states, skeleton while the model download connects, haptics on trim
  handles and blur switches, and the animated background/shimmer now respect "Remove animations".

## v5.1
- **Model source box** (first-run screen and Content blur): type a Hugging Face repo, no rebuild needed.
  Clear errors for a missing repo, private repo, web page, or a non-TFLite file (e.g. `.pt`).
- `tools/make_gender_tflite.py`: converts the best open gender model to `gender.tflite`.
- **Mini player no longer hides content**: every list reserves space for the player bar while a video is loaded.

## v5.2
- **Five built-in gender models** (`app/src/main/assets/models/`, catalog in `blur/BundledModels.kt`): pick one in
  Content blur → Gender model, and the preview re-runs so you can compare. Each model carries its own male/female
  output and pixel-scaling settings, so switching can't swap the genders. A "Swap male / female" switch is there if a
  model ever reads backwards. Custom files and the Hugging Face import still work under **Detection & custom model**.

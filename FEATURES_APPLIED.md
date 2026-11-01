# Features applied: 1, 4, 5, 7, 8, 19, 20

Not compiled in the sandbox (no access to Google's Maven repo). Build in Android Studio.
No Room schema changes, so no database migration is needed.

## 1 · Watch progress on thumbnails
- New `components/WatchProgress.kt`: shared progress bar + "Watched" tag at 95%.
- Used on video cards, History rows, and Playlist rows (Playlist rows had no bar before).

## 4 · Animated theme changes
- New `theme/AnimatedColorScheme.kt`: every scheme colour cross-fades (~350 ms) on
  theme / accent / wallpaper / tint changes. App launch is still instant.

## 5 · Download speed and ETA
- `DownloadsViewModel`: smoothed speed + ETA from the byte counts the worker already writes.
- Rows show "42% · 2.1 MB/s · ~3 min left"; a header strip shows totals while downloading.

## 7 · Subscriptions manager
- New `presentation/subscriptions/`: search, sort (A–Z, most watched, recently watched,
  not watched yet), pin to top, unsubscribe with confirmation (restored if YouTube refuses).
- Stats come from local history, matched by channel *name* (history stores no channel id).
- Opened from You → Subscriptions → "Manage".

## 8 · History cleanup
- ⋮ menu: Select videos, Remove older than (1 wk / 1 mo / 3 mo / 1 yr, count shown first),
  Remove finished videos, Remove a channel, Clear all.
- Long-press any row for multi-select. Every bulk delete has an Undo snackbar.

## 19 · Unified search
- New `search/LibrarySearch.kt` + `LibraryResultsList.kt`: matches subscribed channels,
  playlists, saved playlist videos, downloads and watch history — all on-device.
- Shown under "In your library" while typing and above YouTube results.
- The You tab and the Subscriptions screen keep a local channel list for this
  (the previously unused `subscriptions_cache` table); it's cleared on sign-out.

## 20 · More colour schemes
- Six palettes (Sunset, Ocean, Forest, Rose, Lavender, Mono) that also set secondary and
  tertiary colours; existing saved accent choices are unchanged.
- "Tinted surfaces" toggle. In Black mode the background stays pure black.
- Card/chip containers now follow the accent (previously always red-tinted).

## Build settings
- `gradle.properties`: `org.gradle.java.installations.auto-download=false`, plus daemon,
  parallel and build cache. To stop JDK/Gradle re-downloads: in Android Studio set
  Settings → Build Tools → Gradle → Gradle JDK to the bundled JetBrains Runtime, and
  copy the new source over your existing project folder instead of opening a fresh one
  each time (a fresh folder loses the IDE's per-project Gradle/JDK settings).

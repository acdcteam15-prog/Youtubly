# Youtubly — Phase 11.1 (Convert to MP4)

- Editor shows "This file is WEBM · Convert to MP4" when the open file isn't already MP4 (audio: "Convert to M4A" when it isn't M4A). One tap queues a whole-file conversion (H.264/AAC MP4 or AAC M4A), ignoring any trim, cuts or size settings. The job list shows "Convert to MP4 · 3:20".
- The exporter only enables trim optimization for .mp4 input, so WebM/3GP go straight to a normal export instead of a failed MP4 probe first.
- New: `ConvertRules` and `ExportSpec.isWholeFileConversion` in domain/editor/ExportSpec.kt, plus ConvertRulesTest (40 editor tests pass).
- Acceptance test: download a WebM quality → Saved → long-press → Edit → Convert to MP4 → the MP4 in Download/Youtubly plays in the stock gallery/player.

---

# Youtubly — Phase 11 change manifest (Video editor, part 3: Snapshot, Share-in, Audio)

Compared against Youtubely-phase10-editor-cut-merge-compress.zip. Earlier notes follow below.

## Snapshot
- Editor → "Save frame": saves the exact frame under the playhead (full resolution, MediaMetadataRetriever OPTION_CLOSEST, falling back to the nearest keyframe) as JPG (quality 95) or PNG to **Pictures/Youtubly** via MediaStore. It shows in the gallery; no permission needed.

## Share → Youtubly (videos)
- New intent filter: SEND with `video/*`. Sharing a video from Gallery, Files, WhatsApp… opens it in the Edit tab.
- The shared file is copied into app storage first, because the read grant from the other app ends with our activity, and exports may run later. There is a free-space check before copying, and only the 5 most recent imports are kept.
- Shared YouTube *links* (text) still open the player, as before.
- Routing: MainActivity → EditorInbox (`EditorRequest.ImportShared`) + `DeepLink.OpenEditor` → Edit tab.

## Audio-only downloads
- Saved → long-press → "Edit audio". Trim and "remove a part" work as for video. The player area shows the thumbnail with a headphones badge.
- Output is **.m4a** (AAC) in Download/Youtubly. Resolution, bitrate and snapshot are hidden for audio; merge lists only videos.
- Trim optimization is off for audio (it works from video keyframes, and audio re-encoding is cheap).

## Edge cases
- Specs store `audio=1`; older specs without that line decode as video.
- Output names use the right extension (.mp4 / .m4a) and MIME type.
- A queued export whose imported source was pruned (after 5+ newer shares) fails with "no longer exists" and doesn't crash.

## Files
- New: editor/FrameGrabber.kt, editor/SharedVideoImporter.kt
- Modified: domain/editor/EditorModels.kt (SnapshotFormat, EditorRequest, EditSource.audioOnly), ExportSpec.kt (audioOnly, extension/mime), EditorFormat.kt (snapshot/output names); editor/EditorInbox.kt (requests), MediaStoreSaver.kt (saveExport + saveImage), EditWorker.kt, VideoExporter.kt; presentation/editor/EditorViewModel.kt, EditorScreen.kt; presentation/downloads/DownloadsScreen.kt; presentation/navigation/YoutublyNavHost.kt; core/LinkParser.kt (DeepLink.OpenEditor); MainActivity.kt; AndroidManifest.xml
- Tests: ExportSpecTest.kt (+Phase3SpecTest)

## Checks
- 38 editor unit tests plus the existing 7 LinkParser tests pass. Non-Compose code type-checks against API stubs. Parser check is clean on all touched files, including MainActivity and navigation. The manifest is valid XML.
- Caught during review: the new link branch used `navigateTab` before its declaration (a compile error); fixed by navigating inline.
- No Gradle build in the sandbox.

## Acceptance tests
- Snapshot at 0:42.3 in JPG and in PNG: both appear in the gallery under Pictures/Youtubly and show that exact frame.
- From Google Photos / Files: Share a video → Youtubly → the Edit tab opens with it; trim and export.
- Share a YouTube link as text: still opens the player.
- Long-press an audio-only download → Edit audio → cut a part → a .m4a appears in Download/Youtubly and plays without the removed part.

---

# Youtubly — Phase 10 change manifest (Video editor, part 2: Delete parts, Merge, Compress)

Compared against Youtubely-phase9-editor-trim.zip. The Phase 9 notes follow below, unchanged.

## Delete a part
- In the editor: "Cut from here" marks the playhead, "…to here" marks the end; the part is removed. Several parts can be removed; overlapping ones merge. Each can be restored with ✕. Undo/Redo covers cuts too.
- Removed parts show in red on the timeline. The preview skips them, so it plays exactly what the export will contain.
- Moving a trim handle keeps the cuts, unless that would leave less than 0.5 s (then cuts are dropped).

## Merge
- Edit tab → "Merge" → tap videos in the order they should play (numbered; ↑/↓ reorder) → "Merge N videos".
- Clips of a different size are fitted into the first video's frame (letterboxed). A clip without audio gets silence instead of failing the merge.

## Compress (advanced controls)
- Output panel on single-video edits and merges: resolution (Original / 1080 / 720 / 480 / 360p, never upscales, even dimensions) and video bitrate (Auto, or custom 300 kbps–8 Mbps). Shows an estimated size.
- Compress-only exports are allowed (whole video, new size/bitrate).
- Audio stays AAC at the encoder default: Media3 1.4.0 has no audio bitrate setting (checked in the source).

## How exports run
- Every export is an `ExportSpec` (clips + kept ranges + output settings), stored in the new `edit_jobs.spec` column (DB v7 → v8, `MIGRATION_7_8`). v7 rows without a spec still run as plain trims.
- A plain trim still uses the fast path (mostly copied, no full re-encode). Cuts, merges and compression go through a Transformer Composition and are fully re-encoded, so every cut lands on the exact frame. They take longer.
- The job list shows a summary, e.g. "Merge 3 videos · 12:04 · 720p · 2 Mbps".

## Files
- New: domain/editor/Segments.kt (+ CutRules), domain/editor/ExportSpec.kt (ExportSpec, ClipSpec, OutputSettings, ExportSpecCodec, OutputMath); tests SegmentsTest.kt, ExportSpecTest.kt
- Renamed: editor/TrimExporter.kt → editor/VideoExporter.kt (now runs any ExportSpec)
- Modified: domain/editor/EditorModels.kt (EditState.cuts/keep, EditSource size, EditJob.summary), EditorFormat.kt (bitrate/size; digits and file names pinned to Locale.US so Arabic-locale phones get ASCII names), EditJobEntity.kt, YoutublyDatabase.kt, DatabaseModule.kt, EditRepository.kt (`enqueue(spec)`), EditWorker.kt, presentation/editor/EditorViewModel.kt, EditorScreen.kt, TrimTimeline.kt

## Checks
- 35 JVM unit tests pass (-Werror). The migration column set is checked against the entity (16/16).
- Non-Compose code type-checks against API stubs. Media3 signatures (Composition, EditedMediaItemSequence, Effects, Presentation, DefaultEncoderFactory, VideoEncoderSettings) are copied from the 1.4.0 source.
- Compose screens: parser-level check only. No Gradle build in the sandbox.

## Acceptance tests
- Cut 2 parts from a video: the preview skips them, and the export has exactly those parts missing (check timestamps on either side of each cut).
- Merge 3 videos of different resolutions: they play in the chosen order, all in the first video's frame.
- Compress a 720p video to 480p at 1 Mbps: the output is 480p and roughly the estimated size.
- Upgrade over Phase 9 with finished/failed jobs in the list: they're still there, and Retry on an old job works.

---

# Youtubly — Phase 9 change manifest (Video editor, part 1: Trim)

Compared against Youtubely-phase8.zip. build/.gradle excluded; CHANGES.md replaced.

## Engine
- **Media3 Transformer 1.4.0** (+ media3-effect), same version as the player. FFmpeg-kit was retired in 2025 and its artifacts pulled, so it is not used.
- Output is always MP4 (H.264 + AAC). H.264/AAC MP4 sources use Transformer's trim optimization: only the frames before the first keyframe after the cut are re-encoded, the rest is copied (frame-accurate and fast). Verified in the 1.4.0 source that forcing H.264 output does not disable this when the input is already H.264. WebM sources are transcoded.

## Edit tab
- New 5th tab "Edit" (route `edit`). Floating bar pills are slimmer when there are more than 4 tabs, so it fits 360 dp phones.
- Saved → long-press a video → Edit / Delete menu. Edit is disabled for audio-only and unfinished downloads.
- Videos reach the tab through `EditorInbox` (a hand-off object, not a route argument), so the tab keeps normal tab save/restore.
- Editor: preview player (separate ExoPlayer; takes audio focus, so the main player pauses when a preview plays), which only plays the selection. Thumbnail strip with drag handles (horizontal drags only, so the page still scrolls), tap to seek, ±0.1 s nudges, "Set here" (handle to playhead), Undo/Redo/Reset. Minimum clip 0.5 s; videos under 1 s can't be edited.
- Timeline frames: memory LRU + JPEG disk cache keyed by path/size/mtime (last 20 videos).

## Export queue
- `edit_jobs` table (DB v6 → v7, `MIGRATION_6_7`, SQL checked column-by-column against the entity).
- `EditWorker` (Hilt CoroutineWorker) drains the queue oldest-first, one export at a time. Each enqueue appends one drain to a unique chain (`APPEND_OR_REPLACE`); workers always return success, so one failed export never cancels the rest.
- Keeps running after the app is closed. A job interrupted by a process kill is re-queued on the next run; queued work is also re-kicked on app start.
- Cancel works for queued and running jobs (a running export stops within ~0.5 s). Retry for failed/cancelled. Free-space check before starting.
- Saved via MediaStore to **Download/Youtubly** (no storage permission; IS_PENDING until fully written). Completion notification if notifications are allowed. Open / Share from the job list. Removing a job from the list does not delete the file.

## Files
- New:
  - domain/editor/EditorModels.kt, TrimRules.kt, EditHistory.kt, EditorFormat.kt (plain Kotlin)
  - data/local/entity/EditJobEntity.kt, data/local/dao/EditJobDao.kt
  - data/repository/EditRepository.kt
  - editor/EditWorker.kt, TrimExporter.kt, MediaStoreSaver.kt, FrameStripCache.kt, EditorInbox.kt
  - presentation/editor/EditorViewModel.kt, EditorScreen.kt, TrimTimeline.kt
  - test: domain/editor/TrimRulesTest.kt, EditHistoryTest.kt, EditorFormatTest.kt
- Modified:
  - app/build.gradle.kts — media3-transformer, media3-effect
  - core/Constants.kt — editor channel + output folder
  - data/local/YoutublyDatabase.kt, di/DatabaseModule.kt — v7 + migration + DAO
  - YoutublyApplication.kt — "Video editor" notification channel, resume queued exports
  - presentation/navigation/Screen.kt, YoutublyNavHost.kt, FloatingNavBar.kt — Edit tab
  - presentation/downloads/DownloadsScreen.kt, DownloadsViewModel.kt — long-press menu

## Checks
- 22 JVM unit tests for the editor logic pass (Kotlin 1.9.24, -Werror).
- Worker, exporter, repository, DAO, saver, frame cache and ViewModel type-check against API stubs. Media3 signatures were taken from the 1.4.0 source.
- Compose screens: parser-level check only. No Gradle build in the sandbox (Google Maven unreachable) — build and test on a device.

## Acceptance tests
- Saved → long-press a finished video → Edit → the Edit tab opens with the video and a thumbnail strip.
- Drag both handles, nudge, Set here, Undo/Redo. Play: the preview stops at the end handle.
- Export a 20 s clip from an MP4 download: it finishes in seconds and appears in Files → Download/Youtubly; Open and Share work.
- Queue 3 exports, swipe the app away: all 3 finish. Force-stop mid-export, reopen: the job restarts.
- Cancel a running export: it stops and no file is left in Download/Youtubly.
- Upgrade over Phase 8 with existing downloads/history/playlists: everything is kept.

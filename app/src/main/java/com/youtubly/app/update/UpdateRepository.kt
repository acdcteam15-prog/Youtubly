package com.youtubly.app.update

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.youtubly.app.BuildConfig
import com.youtubly.app.data.local.datastore.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String, val build: Int) : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    /** [fraction] is 0..1, or -1 when the size isn't known. */
    data class Downloading(val info: ReleaseInfo, val fraction: Float, val note: String? = null) : UpdateState
    data class Ready(val info: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Looks for a newer build in this app's GitHub Releases (see .github/workflows/release.yml), downloads it,
 * checks it, and hands it to Android's installer. The release's APK file name carries the build number
 * (Youtubly-1_1_0-build101.apk); a build is newer when its number is above the installed versionCode.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val prefs: UserPreferences
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val repo: String get() = BuildConfig.UPDATE_REPO
    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val updateDir get() = File(context.cacheDir, "updates").apply { mkdirs() }
    private val apkFile get() = File(updateDir, "Youtubly.apk")

    private fun request(url: String, token: String, accept: String): Request = Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Youtubly/${BuildConfig.VERSION_NAME}")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        .build()

    /** Asks GitHub for the latest release. [manual] checks report "up to date"; automatic ones stay quiet. */
    suspend fun check(): UpdateState {
        if (_state.value is UpdateState.Downloading) return _state.value
        _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        prefs.setLastUpdateCheck(System.currentTimeMillis())
        val state = result.fold(
            onSuccess = { info ->
                if (info != null && UpdateParser.isNewer(info.build, currentBuild)) {
                    val waiting = apkFile.takeIf { it.exists() && it.length() > 0 }
                    val sameBuild = waiting?.let {
                        runCatching { context.packageManager.getPackageArchiveInfo(it.absolutePath, 0) }.getOrNull()
                            ?.takeIf { a -> a.packageName == context.packageName && a.longVersionCode == info.build.toLong() }
                    }
                    if (waiting != null && sameBuild != null) UpdateState.Ready(info, waiting) else UpdateState.Available(info)
                }
                else UpdateState.UpToDate(currentVersion, currentBuild)
            },
            onFailure = { UpdateState.Failed(it.message ?: "Could not check for updates") }
        )
        _state.value = state
        return state
    }

    private suspend fun fetchLatest(): ReleaseInfo? {
        if (repo.startsWith("CHANGE_ME")) {
            throw IOException("This build doesn't know where to look for updates. Build it with GitHub Actions (see SETUP.md), or set updateRepo in gradle.properties.")
        }
        val token = prefs.githubToken.first()
        val url = "https://api.github.com/repos/$repo/releases/latest"
        client.newCall(request(url, token, "application/vnd.github+json")).execute().use { r ->
            when {
                r.code == 404 -> throw IOException(
                    if (token.isBlank()) "No update has been published yet. (If the repository is private, add a GitHub token under Advanced.)"
                    else "No update has been published yet."
                )
                r.code == 401 -> throw IOException("GitHub rejected the token. Check it under Advanced.")
                r.code == 403 || r.code == 429 -> throw IOException("GitHub is limiting requests right now. Try again in a few minutes.")
                !r.isSuccessful -> throw IOException("GitHub answered ${r.code}.")
            }
            return UpdateParser.parseRelease(r.body.string(), currentBuild)
        }
    }

    /** The partly downloaded file. It is kept after a dropped connection, so the next try continues from there. */
    private fun partFileFor(info: ReleaseInfo) = File(updateDir, "Youtubly-${info.build}.apk.part")

    /**
     * Queues the download as background work with a notification: it goes on with the screen off or the app closed,
     * and continues from where it stopped if the connection drops or the app is killed.
     */
    fun startDownload(info: ReleaseInfo) {
        _state.value = UpdateState.Downloading(info, if (info.sizeBytes > 0) 0f else -1f)
        UpdateDownloadWorker.enqueue(context, info)
    }

    /**
     * Downloads the APK of [info] (continuing a partial file, retrying a dropped connection), checks its hash and
     * identity, and returns it as [UpdateState.Ready]. The CPU and Wi-Fi are kept awake meanwhile.
     */
    suspend fun download(info: ReleaseInfo): UpdateState = withContext(Dispatchers.IO) {
        val token = prefs.githubToken.first()
        _state.value = UpdateState.Downloading(info, if (info.sizeBytes > 0) 0f else -1f)
        val locks = KeepAwake.acquire(context)
        val outcome = runCatching {
            // Private repositories need the API address with the token; public ones use the plain link.
            val (url, accept) = if (token.isNotBlank() && info.apkApiUrl != null) info.apkApiUrl to "application/octet-stream"
            else info.apkUrl to "application/octet-stream"
            if (!url.startsWith("https://")) throw NoRetryIOException("The update address is not https.")

            val part = partFileFor(info)
            updateDir.listFiles { f -> f.name.endsWith(".part") && f.name != part.name }?.forEach { it.delete() }   // another build's leftovers
            // First choice: a small patch against the installed app; the full file is the fallback.
            val patched = info.patchUrl != null && tryPatch(info, token, accept, part)
            if (!patched) downloadWithRetries(info, url, accept, token, part, info.sizeBytes, null)

            // Integrity: compare with the published checksum when there is one.
            val expected = fetchChecksum(info, token)
            if (expected != null && !expected.equals(sha256(part), ignoreCase = true)) {
                part.delete()
                throw NoRetryIOException("The downloaded file is damaged (checksum mismatch). Try again.")
            }
            val file = apkFile.also { it.delete() }
            if (!part.renameTo(file)) throw IOException("Could not save the update.")

            // Identity: it must be this app, at the build the release promised.
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: run { file.delete(); throw NoRetryIOException("The downloaded file is not a valid app.") }
            if (archive.packageName != context.packageName) { file.delete(); throw NoRetryIOException("The downloaded file is a different app.") }
            if (archive.longVersionCode != info.build.toLong()) { file.delete(); throw NoRetryIOException("The downloaded file has an unexpected version.") }
            file
        }
        KeepAwake.release(locks)
        val next = outcome.fold(
            onSuccess = { UpdateState.Ready(info, it) },
            onFailure = { UpdateState.Failed(friendly(it)) }
        )
        _state.value = next
        next
    }

    /** Downloads [url] into [part], reconnecting and continuing when the connection drops. */
    private suspend fun downloadWithRetries(info: ReleaseInfo, url: String, accept: String, token: String, part: File, sizeHint: Long, label: String?) {
        var attempt = 0
        while (true) {
            try {
                downloadResumable(info, url, accept, token, part, sizeHint, label)
                return
            } catch (e: NoRetryIOException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt > MAX_RETRIES) throw e
                val so = (_state.value as? UpdateState.Downloading)?.fraction ?: 0f
                _state.value = UpdateState.Downloading(info, so, "Connection dropped - continuing ($attempt of $MAX_RETRIES)...")
                delay(3_000L * attempt)
            }
        }
    }

    /**
     * The small update: downloads the patch (a few MB), rebuilds the new APK from the installed one into [out], and
     * checks it. Returns false, leaving nothing behind, when anything is off (no matching patch, a different installed
     * copy, a damaged patch...): the caller then downloads the full file.
     */
    private suspend fun tryPatch(info: ReleaseInfo, token: String, accept: String, out: File): Boolean {
        val installed = File(context.applicationInfo.sourceDir)
        val patchUrl = (if (token.isNotBlank()) info.patchApiUrl else null) ?: info.patchUrl ?: return false
        if (!patchUrl.startsWith("https://") || !installed.exists()) return false
        val patchPart = File(updateDir, "patch-${info.build}.part")
        val label = "Small update (${UpdateParser.humanSize(info.patchSize)} instead of ${UpdateParser.humanSize(info.sizeBytes)})"
        return try {
            downloadWithRetries(info, patchUrl, accept, token, patchPart, info.patchSize, label)
            _state.value = UpdateState.Downloading(info, 0f, "Building the new version from the one installed...")
            out.delete()
            PatchApplier.apply(installed, patchPart, out) { f ->
                _state.value = UpdateState.Downloading(info, f, "Building the new version from the one installed...")
            }
            patchPart.delete()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            patchPart.delete()
            out.delete()
            _state.value = UpdateState.Downloading(info, 0f, "The small update could not be used. Downloading the full file...")
            false
        }
    }

    /** One connection: continues [part] with a Range request when it already has bytes, and appends what arrives. */
    private fun downloadResumable(info: ReleaseInfo, url: String, accept: String, token: String, part: File, sizeHint: Long = info.sizeBytes, label: String? = null) {
        val have = if (part.exists()) part.length() else 0L
        val builder = request(url, token, accept).newBuilder()
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { r ->
            when {
                r.code == 416 -> {                                   // asked for bytes past the end
                    if (sizeHint > 0 && have == sizeHint) return   // it was already complete
                    part.delete()
                    throw IOException("Starting the download again")
                }
                r.code in 400..499 && r.code != 408 && r.code != 429 -> throw NoRetryIOException("Download failed (${r.code}).")
                !r.isSuccessful -> throw IOException("Download failed (${r.code}).")
            }
            val resumed = r.code == 206 && have > 0
            if (!resumed) part.delete()                              // the server sent the whole file from the start
            val start = if (resumed) have else 0L
            val length = r.body.contentLength()
            val total = if (length > 0) start + length else sizeHint
            var done = start
            var lastEmit = 0L
            r.body.byteStream().use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            _state.value = UpdateState.Downloading(info, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f, label)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("The connection closed early")   // retried, continuing from here
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun friendly(t: Throwable): String = when {
        t is NoRetryIOException -> t.message ?: "The update did not work."
        t is java.net.SocketException || t is java.net.SocketTimeoutException || t is java.net.UnknownHostException ||
            t.message?.contains("abort", ignoreCase = true) == true ->
            "The connection dropped. Tap Try again: the download continues from where it stopped."
        else -> t.message ?: "The update did not work."
    }

    private fun fetchChecksum(info: ReleaseInfo, token: String): String? {
        val url = (if (token.isNotBlank()) info.sha256ApiUrl else null) ?: info.sha256Url ?: return null
        return runCatching {
            client.newCall(request(url, token, "application/octet-stream")).execute().use { r ->
                if (r.isSuccessful) UpdateParser.parseSha256(r.body.string()) else null
            }
        }.getOrNull()
    }

    /**
     * Installs [file] through Android's PackageInstaller. After the first manual install Android 12+ lets an app that
     * installed itself update itself without another prompt; otherwise the system shows its usual "Update" dialog
     * (the receiver opens it). Falls back to the classic install intent if a session cannot be created.
     */
    fun install(file: File) {
        UpdateInstallResult.failure.value = null
        val started = runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("youtubly.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        }.isSuccess
        if (!started) context.startActivity(installIntent(file))
    }

    /** A finished download from an earlier run (the app was closed since): show it as ready to install. */
    fun restoreReady() {
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) return
        val file = apkFile.takeIf { it.exists() && it.length() > 0 } ?: return
        val archive = runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull() ?: return
        if (archive.packageName != context.packageName || archive.longVersionCode <= currentBuild) return
        _state.value = UpdateState.Ready(
            ReleaseInfo(
                versionName = archive.versionName ?: "", build = archive.longVersionCode.toInt(), notes = "", required = false,
                apkUrl = "", apkApiUrl = null, sizeBytes = file.length(), sha256Url = null, sha256ApiUrl = null, publishedAt = null
            ),
            file
        )
    }

    /** Android asks the user once to allow installs from an app ("Allow installing from Youtubly"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Back to "Available" after a failed install attempt, so the button reappears. */
    fun reset() { _state.value = UpdateState.Idle }

    /** After an update: say so once ("Youtubly was updated to 1.1.0 (build 102)"), then remember this build. */
    suspend fun announceIfJustUpdated() {
        val last = prefs.lastSeenBuild.first()
        if (last in 1 until currentBuild) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Youtubly was updated to $currentVersion (build $currentBuild)", Toast.LENGTH_LONG).show()
            }
            runCatching { File(context.cacheDir, "updates").deleteRecursively() }   // the installed APK isn't needed any more
        }
        if (last != currentBuild) prefs.setLastSeenBuild(currentBuild)
    }

    private companion object { const val MAX_RETRIES = 12 }
}

/** A download failure that retrying cannot fix (wrong address, damaged file, wrong app). */
class NoRetryIOException(message: String) : IOException(message)

/** Keeps the CPU and Wi-Fi awake while a download runs, so the screen going off does not cut the connection. */
internal object KeepAwake {
    class Locks(val cpu: android.os.PowerManager.WakeLock?, val wifi: android.net.wifi.WifiManager.WifiLock?)

    @Suppress("DEPRECATION")
    fun acquire(context: Context): Locks {
        val cpu = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "youtubly:update")
                .apply { setReferenceCounted(false); acquire(30 * 60_000L) }
        }.getOrNull()
        val wifi = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "youtubly:update")
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return Locks(cpu, wifi)
    }

    fun release(l: Locks) {
        runCatching { if (l.cpu?.isHeld == true) l.cpu.release() }
        runCatching { if (l.wifi?.isHeld == true) l.wifi.release() }
    }
}

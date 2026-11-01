package com.youtubly.app.update

import android.content.Context
import android.content.Intent
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
    data class Downloading(val info: ReleaseInfo, val fraction: Float) : UpdateState
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
                if (info != null && UpdateParser.isNewer(info.build, currentBuild)) UpdateState.Available(info)
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
            return UpdateParser.parseRelease(r.body.string())
        }
    }

    /** Downloads the APK of [info], checks its hash and identity, and returns it as [UpdateState.Ready]. */
    suspend fun download(info: ReleaseInfo): UpdateState = withContext(Dispatchers.IO) {
        val token = prefs.githubToken.first()
        _state.value = UpdateState.Downloading(info, if (info.sizeBytes > 0) 0f else -1f)
        val outcome = runCatching {
            val file = apkFile.also { it.delete() }
            // Private repositories need the API address with the token; public ones use the plain link.
            val (url, accept) = if (token.isNotBlank() && info.apkApiUrl != null) info.apkApiUrl to "application/octet-stream"
            else info.apkUrl to "application/octet-stream"
            if (!url.startsWith("https://")) throw IOException("The update address is not https.")

            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request(url, token, accept)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (${r.code}).")
                val total = r.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                var done = 0L
                var lastEmit = 0L
                r.body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 200) {
                                lastEmit = now
                                _state.value = UpdateState.Downloading(info, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                            }
                        }
                    }
                }
            }

            // Integrity: compare with the published checksum when there is one.
            val expected = fetchChecksum(info, token)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                file.delete()
                throw IOException("The downloaded file is damaged (checksum mismatch). Try again.")
            }

            // Identity: it must be this app, at the build the release promised.
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: run { file.delete(); throw IOException("The downloaded file is not a valid app.") }
            if (archive.packageName != context.packageName) { file.delete(); throw IOException("The downloaded file is a different app.") }
            if (archive.longVersionCode != info.build.toLong()) { file.delete(); throw IOException("The downloaded file has an unexpected version.") }
            file
        }
        val next = outcome.fold(
            onSuccess = { UpdateState.Ready(info, it) },
            onFailure = { UpdateState.Failed(it.message ?: "The update did not work.") }
        )
        _state.value = next
        next
    }

    private fun fetchChecksum(info: ReleaseInfo, token: String): String? {
        val url = (if (token.isNotBlank()) info.sha256ApiUrl else null) ?: info.sha256Url ?: return null
        return runCatching {
            client.newCall(request(url, token, "application/octet-stream")).execute().use { r ->
                if (r.isSuccessful) UpdateParser.parseSha256(r.body.string()) else null
            }
        }.getOrNull()
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

}

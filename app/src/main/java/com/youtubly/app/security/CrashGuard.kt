package com.youtubly.app.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.BuildConfig
import com.youtubly.app.blur.BlurPreferencesStore
import com.youtubly.app.core.CrashLoop
import com.youtubly.app.music.MusicPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notices when the app crashes again and again right after starting (typically a bad new build) and switches the
 * risky features off so the app opens again: content blur, music remover and GPU video effects.
 * Android cannot install an older version over a newer one, so "going back" means getting an older APK from the
 * Releases page and reinstalling; the dialog explains that.
 */
object CrashGuard {
    private const val PREFS = "crash_guard"
    private const val STABLE_AFTER_MS = 60_000L

    data class Notice(val summary: String)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Installs the crash recorder and returns true when the previous runs were a crash loop (safe mode starts now).
     * Call once from Application.onCreate, after the other crash handler.
     */
    fun install(context: Context): Boolean {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val p = prefs(app)
                val now = System.currentTimeMillis()
                val times = CrashLoop.prune(decode(p.getString("times", "")), now) + now
                val summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - " +
                    throwable.stackTraceToString().lineSequence().take(14).joinToString("\n")
                p.edit().putString("times", times.joinToString(",")).putString("last", summary.take(3500)).commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val times = decode(p.getString("times", ""))
        val loop = CrashLoop.isLoop(times, now)
        if (loop) {
            // Start fresh after reacting, so one more crash is not an instant second safe mode.
            p.edit().putString("times", "").putBoolean("notice", true).apply()
        }
        if (p.getBoolean("notice", false)) _notice.value = Notice(p.getString("last", "").orEmpty())
        return loop
    }

    /** The app has been running long enough that earlier crashes no longer count towards a loop. */
    fun markStable(context: Context) { prefs(context).edit().putString("times", "").apply() }
    fun stableAfterMs() = STABLE_AFTER_MS

    fun remember(context: Context, blurWasOn: Boolean, musicWasOn: Boolean) {
        prefs(context).edit().putBoolean("prev_blur", blurWasOn).putBoolean("prev_music", musicWasOn).apply()
    }
    fun previous(context: Context): Pair<Boolean, Boolean> = prefs(context).let { it.getBoolean("prev_blur", false) to it.getBoolean("prev_music", false) }
    fun dismiss(context: Context) { prefs(context).edit().putBoolean("notice", false).apply(); _notice.value = null }

    private fun decode(s: String?): List<Long> = s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
}

@HiltViewModel
class SafeModeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val blurStore: BlurPreferencesStore,
    private val musicStore: MusicPreferencesStore
) : ViewModel() {
    /** Puts back what safe mode switched off. */
    fun turnBackOn() {
        viewModelScope.launch {
            val (blur, music) = CrashGuard.previous(appContext)
            if (blur) blurStore.setEnabled(true)
            if (music) musicStore.setEnabled(true)
            com.youtubly.app.playback.GpuEffects.suspendFor(appContext, 0L)
            CrashGuard.dismiss(appContext)
        }
    }
    fun keepOff() { CrashGuard.dismiss(appContext) }
}

@Composable
fun SafeModeDialog(vm: SafeModeViewModel = hiltViewModel()) {
    val notice by CrashGuard.notice.collectAsState()
    val n = notice ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = vm::keepOff,
        title = { Text("Safe mode") },
        text = {
            Text(
                "Youtubly crashed twice in a row right after starting, so it switched off content blur, the music remover and " +
                    "video effects to get back up.\n\n" +
                    "You can turn them back on in Settings, or below. If this started after an update, an older version can be " +
                    "installed from the Releases page, but Android only allows that after uninstalling the newer one " +
                    "(back up first: Settings > Backup and restore)."
            )
        },
        confirmButton = { TextButton(onClick = vm::keepOff) { Text("Keep them off") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Youtubly crash\n${n.summary}")
                    context.startActivity(Intent.createChooser(send, "Share crash details").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share details") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Older builds") }
                TextButton(onClick = vm::turnBackOn) { Text("Turn back on") }
            }
        }
    )
}

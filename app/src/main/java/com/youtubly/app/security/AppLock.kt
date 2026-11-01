package com.youtubly.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App lock on/off, kept in a plain SharedPreferences so the very first frame already knows (no flash of content). */
object AppLockState {
    private const val PREFS = "app_lock"
    private const val GRACE_MS = 30_000L
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private var unlocked = false
    private var backgroundedAt = 0L

    fun init(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
    }

    /** Turning the lock on while using the app must not lock you out of this very session. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        _enabled.value = on
        if (on) markUnlocked()
    }

    fun markUnlocked() { unlocked = true }
    fun markBackgrounded() { backgroundedAt = SystemClock.elapsedRealtime() }

    /** True at cold start, and after the app was in the background for more than 30 seconds. */
    fun needsUnlock(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        _enabled.value && (!unlocked || nowElapsed - backgroundedAt > GRACE_MS)
}

object AppLock {
    /** The phone must have a PIN, pattern, password or fingerprint set up. */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Fingerprint / face when the phone has them, otherwise (or on "use PIN") the phone's screen lock. */
    fun authenticate(activity: Activity, title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val builder = BiometricPrompt.Builder(activity).setTitle(title)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onFailure(errString?.toString().orEmpty()) }
        })
    }
}

private fun Context.findActivity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

/** Covers the app with a lock screen until you unlock it (when App lock is on). Hides the app in recents too. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val enabled by AppLockState.enabled.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var locked by remember { mutableStateOf(AppLockState.needsUnlock()) }
    var message by remember { mutableStateOf<String?>(null) }

    // While the lock is on: no screenshots and a blank thumbnail in the recent-apps list.
    DisposableEffect(enabled) {
        val window = activity?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) AppLockState.markBackgrounded()
            if (event == Lifecycle.Event.ON_START && AppLockState.needsUnlock()) locked = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!enabled && locked) locked = false

    fun prompt() {
        val a = activity ?: run { locked = false; return }
        AppLock.authenticate(
            a, "Unlock Youtubly",
            onSuccess = { AppLockState.markUnlocked(); message = null; locked = false },
            onFailure = { message = it.ifBlank { "Not unlocked" } }
        )
    }
    LaunchedEffect(locked) { if (locked) prompt() }

    Box(Modifier.fillMaxSize()) {
        content()
        if (locked && enabled) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = false) {},
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text("Youtubly is locked", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                Button(onClick = ::prompt, modifier = Modifier.padding(top = 20.dp)) { Text("Unlock") }
            }
        }
    }
}

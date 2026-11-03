package com.example.remoty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.remoty.ui.RemotyRoot
import com.example.remoty.ui.RemotyTheme

class MainActivity : AppCompatActivity() {
    private val vm: RemotyVm by viewModels()
    private var prompting = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // "Pick a zip" button
    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickZip(uri)
    }

    private fun openZipPicker() =
        zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))

    /** "Share > Remoty" from a chat app or browser. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        vm.pickZip(uri)
        setIntent(Intent())   // handled: do not process it again after a rotation
    }

    /** The Install button on the "APK ready" notification. */
    private fun handleInstallExtra(i: Intent?) {
        val id = i?.getStringExtra("install_job") ?: return
        i.removeExtra("install_job")
        vm.requestInstall(id)
    }

    private val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private fun canLock() = BiometricManager.from(this).canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With the lock on, hide the app from screenshots and the recent-apps preview.
        if (vm.store.appLock) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            RemotyTheme { RemotyRoot(vm, canLock = canLock(), onUnlock = ::promptUnlock, onPickZip = ::openZipPicker) }
        }
        handleShare(intent)
        handleInstallExtra(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleInstallExtra(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.foreground = true
        vm.onForeground()
        if (vm.appLock && vm.locked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        vm.foreground = false
        if (vm.appLock) vm.locked = true
    }

    private fun promptUnlock() {
        if (prompting) return
        if (!canLock()) { vm.locked = false; return }   // no screen lock set on the phone: nothing to ask
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false; vm.locked = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Remoty").setAllowedAuthenticators(auth).build()
        )
    }
}

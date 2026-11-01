package com.youtubly.app.presentation.signin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Signs in to youtube.com inside a WebView and captures the session cookies.
 * The UA is a normal mobile Chrome (no "; wv") so Google's sign-in page accepts it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel()
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var finished by remember { mutableStateOf(false) }

    fun tryCapture(url: String?) {
        if (finished || url == null || !url.contains("youtube.com")) return
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
        val signedIn = cookies.contains("SAPISID=") || cookies.contains("__Secure-3PAPISID=")
        if (signedIn) {
            finished = true
            CookieManager.getInstance().flush()
            viewModel.save(cookies, onDone)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 8.dp))
            Column {
                Text("Sign in to YouTube", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "accounts.google.com · stays on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(progress < 1f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = MOBILE_CHROME_UA
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = tryCapture(url)
                            override fun onPageFinished(view: WebView?, url: String?) = tryCapture(url)
                        }
                        loadUrl(SIGN_IN_URL)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
        }
    }
}

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3D%252F"

private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

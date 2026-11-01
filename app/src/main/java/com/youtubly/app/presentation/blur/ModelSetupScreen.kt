package com.youtubly.app.presentation.blur

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.youtubly.app.blur.BlurEngine
import com.youtubly.app.blur.ModelDownloader
import com.youtubly.app.presentation.components.rememberReduceMotion
import com.youtubly.app.presentation.components.shimmer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    private val engine: BlurEngine,
    private val downloader: ModelDownloader,
) : ViewModel() {
    val info = engine.classifier.info
    var importing by mutableStateOf(false)
        private set
    /** 0..100, or -1 when the size is unknown (show an indeterminate bar). */
    var progress by mutableStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Downloads the model from Hugging Face, validates it, and installs it. */
    fun downloadModel() {
        if (importing) return
        viewModelScope.launch {
            importing = true; error = null; progress = -1
            val download = downloader.download { progress = it }
            val file = download.getOrNull()
            if (file == null) {
                error = download.exceptionOrNull()?.message ?: "Download failed. Check your connection and try again."
            } else {
                engine.classifier.importFromFile(file)
                    .onFailure { error = it.message ?: "That file isn't a usable model" }
            }
            importing = false
        }
    }
    fun finish() = viewModelScope.launch { engine.store.markSetupDone() }
}

/**
 * Wraps the app root. The splash screen is held while [BlurEngine.setupNeeded] is loading
 * (see MainActivity), so this never shows an empty frame. The setup screen slides away into
 * the app once a model is imported or the user skips.
 */
@Composable
fun ModelSetupGate(app: @Composable () -> Unit) {
    val engine = com.youtubly.app.blur.LocalBlurEngine.current
    val needs by engine.setupNeeded.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = needs,
        transitionSpec = {
            (fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.96f)) togetherWith
                (fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it / 8 })
        },
        contentKey = { it == true },
        label = "modelSetupGate",
    ) { state ->
        when (state) {
            true -> ModelSetupScreen()
            else -> if (state == false) app() else Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
fun ModelSetupScreen(vm: ModelSetupViewModel = hiltViewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    var confirmSkip by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()

    // Staggered entrance
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    // Gentle breathing halo behind the icon (skipped when animations are off)
    val halo = if (reduceMotion) 1f else rememberInfiniteTransition(label = "halo").animateFloat(
        0.92f, 1.08f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "haloScale"
    ).value

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(shown, enter = fadeIn(tween(500)) + scaleIn(spring(0.6f, Spring.StiffnessLow), initialScale = 0.6f)) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(132.dp).scale(halo).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)))
                    AnimatedContent(info != null, transitionSpec = {
                        (scaleIn(spring(0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut())
                    }, label = "setupIcon") { ready ->
                        Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.FaceRetouchingOff, null,
                            Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(500, 150)) + slideInVertically(tween(500, 150)) { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(info, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "setupText") { m ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (m == null) "Content blur needs a model" else "Model ready", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (m == null) "Youtubly couldn't set up its built-in gender model. You can try again, or import a model " +
                                    "yourself (.tflite from Hugging Face). It's kept privately on this device."
                                else "${m.inputW}×${m.inputH} ${m.inputType.lowercase()} · ${m.outputs} output(s) · ${m.sizeKb} KB\n" +
                                    "Tip: try the other built-in models in You → Content blur to find the most accurate one.",
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AnimatedVisibility(vm.error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Text(vm.error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(500, 300)) + slideInVertically(tween(500, 300)) { it / 2 }) {
                AnimatedContent(
                    when { vm.importing -> 0; info == null -> 1; else -> 2 },
                    transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
                    label = "setupActions",
                ) { step ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (step) {
                            0 -> {
                                if (vm.progress in 0..100)
                                    LinearProgressIndicator(progress = { vm.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                else {
                                    // Connecting / size unknown: skeleton card instead of a bare bar.
                                    Column(
                                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                                            .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(CircleShape).shimmer())
                                        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).shimmer())
                                        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(CircleShape).shimmer())
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (vm.progress in 0..100) "Downloading model… ${vm.progress}%" else "Downloading model…",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            1 -> {
                                ModelSourceField()
                                Spacer(Modifier.height(12.dp))
                                Button(vm::downloadModel, Modifier.fillMaxWidth().height(52.dp)) {
                                    Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("Import model")
                                }
                                TextButton({ confirmSkip = true }) { Text("Skip for now") }
                            }
                            else -> {
                                Button(vm::finish, Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
                                TextButton(vm::downloadModel) { Text("Download again") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmSkip) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text("Skip model import?") },
        text = { Text("Without a model, gender can't be detected, so faces count as uncertain (blurred while that option is on). You can download it later in You → Content blur.") },
        confirmButton = { TextButton({ confirmSkip = false; vm.finish() }) { Text("Skip") } },
        dismissButton = { TextButton({ confirmSkip = false }) { Text("Back") } },
    )
}

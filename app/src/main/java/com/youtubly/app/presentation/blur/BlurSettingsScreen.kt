package com.youtubly.app.presentation.blur

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.blur.BlurMode
import com.youtubly.app.blur.BodyCoverage
import com.youtubly.app.blur.Normalization
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.popOnChange
import com.youtubly.app.presentation.components.staggeredEntrance
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlurSettingsScreen(onBack: () -> Unit, onOpenWhitelist: () -> Unit, vm: BlurSettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val model by vm.modelInfo.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::loadPreview) }

    val pickModelFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModelFile) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Content blur") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton({ confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, "Reset") } },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).nestedScrollBehavior(scroll),
            contentPadding = PaddingValues(bottom = 32.dp + com.youtubly.app.presentation.components.miniBarClearance()),
        ) {
            item { MasterCard(s.enabled, vm::setEnabled, Modifier.staggeredEntrance(0)) }

            item {
                SectionCard("Who to blur", Icons.Rounded.Face, Modifier.staggeredEntrance(1)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckChip("Women / girls", s.blurFemale) { vm.setBlurFemale(!s.blurFemale) }
                        CheckChip("Men / boys", s.blurMale) { vm.setBlurMale(!s.blurMale) }
                    }
                    AnimatedVisibility(!s.blurFemale && !s.blurMale, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Text("Nothing is selected, so nothing will be blurred.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    SwitchRow("Blur uncertain faces", "When the model isn't sure, or no model is loaded", s.blurUncertain, vm::setBlurUncertain)
                }
            }

            item {
                SectionCard("Cover", Icons.Rounded.Tune, Modifier.staggeredEntrance(2)) {
                    Text(
                        "Once a face is found, how much of the person is hidden.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BodyCoverage.entries.forEachIndexed { i, c ->
                            SegmentedButton(s.bodyCoverage == c, { vm.setBodyCoverage(c) }, SegmentedButtonDefaults.itemShape(i, BodyCoverage.entries.size)) {
                                Text(when (c) { BodyCoverage.FACE -> "Face"; BodyCoverage.UPPER_BODY -> "Upper body"; BodyCoverage.FULL_BODY -> "Full body" })
                            }
                        }
                    }
                    Text(
                        "The body is estimated from the face.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.bodyCoverage != BodyCoverage.FACE) {
                        SwitchRow(
                            "Also cover people seen from behind",
                            "Finds person shapes with no visible face (back turned, face too small) and hides them too. Works best on people fairly close to the camera.",
                            s.coverBackViews, vm::setCoverBackViews
                        )
                    }
                }
            }

            item {
                SectionCard("Style", Icons.Rounded.BlurOn, Modifier.staggeredEntrance(2)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BlurMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(s.mode == m, { vm.setMode(m) }, SegmentedButtonDefaults.itemShape(i, BlurMode.entries.size)) {
                                Text(when (m) { BlurMode.GAUSSIAN -> "Blur"; BlurMode.PIXELATE -> "Pixelate"; BlurMode.SOLID -> "Solid" })
                            }
                        }
                    }
                    AnimatedVisibility(s.mode != BlurMode.SOLID, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabeledSlider("Strength", s.strength.toFloat(), 5f..50f, { "${it.roundToInt()}" }) { vm.setStrength(it.roundToInt()) }
                            SwitchRow("Scale with face size", "Big faces get stronger blur, so every face is equally hidden", s.scaleWithFaceSize, vm::setScaleWithFaceSize)
                        }
                    }
                    LabeledSlider("Extra margin", s.facePaddingPct.toFloat(), 0f..60f, { "${it.roundToInt()}%" }) { vm.setPadding(it.roundToInt()) }
                }
            }

            item {
                SectionCard("Where", Icons.Rounded.Tune, Modifier.staggeredEntrance(3)) {
                    SwitchRow("Thumbnails & avatars", "Home, search, subscriptions, channels, playlists…", s.applyToThumbnails, vm::setApplyToThumbnails)
                    SwitchRow("Video playback", "Tracked live while you watch · turning it on applies from the next video", s.applyToPlayback, vm::setApplyToPlayback)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var gpuBroken by remember { mutableStateOf(com.youtubly.app.playback.GpuEffects.isBroken(ctx)) }
                    if (gpuBroken) {
                        Text("Video blur is paused (the graphics driver failed or froze), so blurred videos play as audio only until you retry or update the app.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton({ com.youtubly.app.playback.GpuEffects.reset(ctx); gpuBroken = false }) { Text("Try video blur again (after an update)") }
                    }
                    NavLine(Icons.Rounded.PersonOff, "Excluded channels", "${s.whitelist.size} channel(s) never blurred", onOpenWhitelist, badge = s.whitelist.size)
                }
            }

            item {
                SectionCard("Preview lab", Icons.Rounded.Science, Modifier.staggeredEntrance(4)) {
                    Text("Pick a photo to test settings live. Press and hold to compare with the original.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PreviewPane(preview)
                    FilledTonalButton({ pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp))
                        Text(if (preview.original == null) "Pick test photo" else "Change photo")
                    }
                }
            }

            if (vm.hasBundledModel) item { ModelPickerCard(vm, Modifier.staggeredEntrance(5)) }
            item { AdvancedCard(s, model, vm, onImport = vm::importModel, Modifier.staggeredEntrance(6),
                onPickFile = { pickModelFile.launch(arrayOf("application/octet-stream", "*/*")) }) }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset blur settings?") },
        text = { Text("Style, strength and detection options go back to defaults. Your model and excluded channels are kept.") },
        confirmButton = { TextButton({ confirmReset = false; vm.resetDefaults() }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.nestedScrollBehavior(b: TopAppBarScrollBehavior) = this.nestedScroll(b.nestedScrollConnection)

// ------------------------------------------------------------------ pieces

@Composable
private fun MasterCard(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val bg by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(400), label = "masterBg")
    val fg by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, tween(400), label = "masterFg")
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(bg)
            .bounceClick(scaleDown = 0.98f) { onChange(!enabled) }.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(enabled, transitionSpec = {
            (scaleIn(spring(0.45f)) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut())
        }, label = "masterIcon") {
            Icon(if (it) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = fg, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Blur faces", style = MaterialTheme.typography.titleLarge, color = fg)
            AnimatedContent(enabled, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "masterSub") {
                Text(if (it) "Active everywhere in the app" else "Off", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
            }
        }
        Switch(enabled, onChange)
    }
}

@Composable
internal fun SectionCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun CheckChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text(label) }, Modifier.popOnChange(selected, peak = 1.08f),
        leadingIcon = {
            AnimatedVisibility(selected, enter = scaleIn(spring(0.5f)) + expandHorizontally(), exit = scaleOut() + shrinkHorizontally()) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
            }
        })
}

@Composable
internal fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).bounceClick(scaleDown = 0.98f) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        Switch(checked, { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onChange(it) })
    }
}

@Composable
internal fun NavLine(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, badge: Int) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).bounceClick(scaleDown = 0.98f, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            AnimatedContent(sub, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wlCount") {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.popOnChange(badge))
    }
}

/** Local state while dragging, saved on release → live label, no DataStore write storm. */
@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(local, { local = it }, valueRange = range, onValueChangeFinished = { onCommit(local) })
    }
}

@Composable
private fun PreviewPane(preview: PreviewState) {
    val shown = preview.result?.bitmap ?: preview.original
    var holding by remember { mutableStateOf(false) }
    AnimatedVisibility(shown != null, enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(MaterialTheme.shapes.large).background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false }) }) {
                Crossfade(if (holding) preview.original else shown, animationSpec = tween(220), label = "previewSwap") { b ->
                    b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                androidx.compose.animation.AnimatedVisibility(preview.busy, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                androidx.compose.animation.AnimatedVisibility(holding, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    Text("Original", color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            preview.result?.let { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatPill("${r.facesFound} faces", r.facesFound)
                    StatPill("${r.facesBlurred} blurred", r.facesBlurred)
                    StatPill("${r.timeMs} ms", 0)
                }
            }
        }
    }
}

@Composable
private fun StatPill(text: String, key: Int) {
    AnimatedContent(text, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "stat") {
        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.popOnChange(key)
            .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun AdvancedCard(s: com.youtubly.app.blur.BlurSettings, model: com.youtubly.app.blur.ModelInfo?, vm: BlurSettingsViewModel, onImport: () -> Unit, modifier: Modifier, onPickFile: () -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val dlProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val arrow by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "advArrow")
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)).padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).bounceClick(scaleDown = 0.98f) { open = !open },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Detection & custom model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(arrow))
        }
        AnimatedVisibility(open, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledSlider("Minimum face size", s.minFaceSizePct.toFloat(), 2f..30f, { "${it.roundToInt()}%" }) { vm.setMinFaceSize(it.roundToInt()) }
                Text("Lower catches small / background faces but costs more time.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                AnimatedContent(model, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "modelInfo") { m ->
                    Text(m?.let { "Active model: ${it.inputW}×${it.inputH} ${it.inputType.lowercase()}, ${it.outputs} output(s), ${it.sizeKb} KB" + (if (it.bundled) " · built in" else " · custom file") }
                        ?: "No gender model: every face counts as uncertain", style = MaterialTheme.typography.bodyMedium)
                }
                ModelSourceField()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onImport, enabled = !downloading) {
                        Text(when {
                            downloading && dlProgress in 0..100 -> "Downloading… $dlProgress%"
                            downloading -> "Downloading…"
                            model == null -> "Import model"
                            else -> "Change model"
                        })
                    }
                    AnimatedVisibility(model != null && !downloading, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        OutlinedButton(vm::removeModel) { Text("Remove") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AnimatedVisibility(vm.hasBundledModel && model?.bundled != true && !downloading, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        TextButton(vm::useBundledModel) { Text("Use the built-in model") }
                    }
                    TextButton(onPickFile, enabled = !downloading) { Text("Choose a file") }
                }
                val sha by vm.modelSha256.collectAsStateWithLifecycle()
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                AnimatedVisibility(sha != null && !downloading, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SHA-256: ${sha.orEmpty().take(16)}…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton({ sha?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) } }) { Text("Copy") }
                    }
                }
                AnimatedVisibility(downloading, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    if (dlProgress in 0..100) LinearProgressIndicator(progress = { dlProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AnimatedVisibility(model != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabeledSlider("Gender confidence", s.genderConfidence, 0.5f..0.95f, { "${(it * 100).roundToInt()}%" }) { vm.setGenderConfidence(it) }
                        if (model?.bundled == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Swap male / female", style = MaterialTheme.typography.labelLarge)
                                    Text("Built-in models are already set up. Turn this on only if the preview above blurs men instead of women for this model.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(s.flipGender, { vm.setFlipGender(it) })
                            }
                        } else {
                        Text("Output index for 'female'", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (0..1).forEach { i -> FilterChip(s.femaleOutputIndex == i, { vm.setFemaleOutputIndex(i) }, { Text("Index $i") }) }
                        }
                        Text("Input normalization", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Normalization.entries.forEach { n ->
                                FilterChip(s.normalization == n, { vm.setNormalization(n) },
                                    { Text(when (n) { Normalization.ZERO_ONE -> "0…1"; Normalization.MINUS_ONE_ONE -> "−1…1"; Normalization.RAW -> "0…255" }) })
                            }
                        }
                        Text("Tip: if the preview blurs men instead of women, flip the index.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lists the models packed with the app. Tapping one switches to it immediately; the preview above
 * re-runs so the difference is visible on the test photo. Each model brings its own settings, so
 * switching can't swap men and women.
 */
@Composable
private fun ModelPickerCard(vm: BlurSettingsViewModel, modifier: Modifier) {
    val active by vm.activeBundledId.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Gender model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            if (switching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text("Built in. Pick a test photo above, then try each one and keep whichever gets it right most often on your phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp))
        vm.bundledModels.forEach { m ->
            val selected = active == m.id
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                    .bounceClick(scaleDown = 0.98f) { if (!switching) vm.chooseBundledModel(m.id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        if (m.recommended) {
                            Spacer(Modifier.width(8.dp))
                            Text("Default", style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (active == null) {
            Text("A custom model file is in use. Pick one above to go back to a built-in model.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

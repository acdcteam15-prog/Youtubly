package com.youtubly.app.presentation.blur

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.blur.LocalBlurEngine
import com.youtubly.app.blur.ModelSource
import kotlinx.coroutines.launch

/** Where "Import model" downloads from. Saved as you type; empty uses the built-in default. */
@Composable
fun ModelSourceField(modifier: Modifier = Modifier) {
    val engine = LocalBlurEngine.current
    val saved by engine.store.modelSource.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf<String?>(null) }   // null = not edited yet, show saved
    val shown = typed ?: saved
    val invalid = shown.isNotBlank() && ModelSource.resolveUrl(shown) == null

    OutlinedTextField(
        value = shown,
        onValueChange = { typed = it; scope.launch { engine.store.setModelSource(it) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid,
        label = { Text("Model source on Hugging Face") },
        placeholder = { Text("username/repo") },
        supportingText = {
            Text(
                when {
                    invalid -> "Use username/repo (with a gender.tflite in it), or a huggingface.co link to a .tflite file."
                    shown.isBlank() -> "The repo must be public and contain a .tflite model."
                    else -> ModelSource.resolveUrl(shown)!!.removePrefix("https://huggingface.co/")
                }
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
    )
}

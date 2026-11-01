package com.youtubly.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.youtubly.app.MainActivity
import com.youtubly.app.R

private val Bg = Color(0xFF14141C)
private val TextMain = ColorProvider(Color(0xFFEDEDF5))
private val TextDim = ColorProvider(Color(0xFF9A9AB0))

class YoutublyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent { WidgetContent(context, state) }
    }

    companion object {
        /** Re-render every placed instance (playback changed, etc.). */
        suspend fun refresh(context: Context) = runCatching { YoutublyWidget().updateAll(context) }
    }
}

class YoutublyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YoutublyWidget()
}

/** Play/pause button → the app's player (same process), then redraw the widget. */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.togglePlayPause(context)
        // The player's state flips a moment after the call; redraw now and once more shortly after.
        YoutublyWidget.refresh(context)
        kotlinx.coroutines.delay(700)
        YoutublyWidget.refresh(context)
    }
}

private val UrlKey = ActionParameters.Key<String>("url")
private val StartKey = ActionParameters.Key<Long>("startMs")

/** "Continue watching" ▶ → starts that video as background audio, from where it was left off. */
class PlayContinueAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val url = parameters[UrlKey] ?: return
        val ok = WidgetData.playContinue(context, url, parameters[StartKey] ?: 0L)
        if (!ok) {
            // Couldn't start it here (offline?): open the app on that video instead of doing nothing.
            context.startActivity(openVideoIntent(context, url))
        }
        YoutublyWidget.refresh(context)
    }
}

/** Opens a video through the same VIEW-link path as a browser tap (MainActivity.handleIntent). */
internal fun openVideoIntent(context: Context, url: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (url != null) { action = Intent.ACTION_VIEW; data = Uri.parse(url) }
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

@Composable
private fun WidgetContent(context: Context, state: WidgetState) {
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(Bg).padding(10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val np = state.nowPlaying
        val cont = state.continueItem
        when {
            np != null -> ItemRow(
                context = context,
                label = if (np.isPlaying) "Now playing" else "Paused",
                title = np.title,
                subtitle = np.subtitle,
                thumb = np.thumb,
                progress = null,
                openIntent = openVideoIntent(context, np.videoUrl),
                showPlayPause = true,
                isPlaying = np.isPlaying,
                accent = state.accent
            )
            cont != null -> ItemRow(
                context = context,
                label = "Continue watching",
                title = cont.title,
                subtitle = listOfNotNull(cont.channel.ifBlank { null }, cont.remainingSec.takeIf { it > 0 }?.let { "${formatLeft(it)} left" }).joinToString(" · "),
                thumb = cont.thumb,
                progress = cont.progress,
                openIntent = openVideoIntent(context, cont.url),
                showPlayPause = false,
                isPlaying = false,
                accent = state.accent,
                continueUrl = cont.url,
                continueStartMs = cont.startMs
            )
            else -> Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openVideoIntent(context, null)))) {
                Text("Youtubly", style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text("Nothing to continue yet — tap to open", style = TextStyle(color = TextDim, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun ItemRow(
    context: Context,
    label: String,
    title: String,
    subtitle: String?,
    thumb: android.graphics.Bitmap?,
    progress: Float?,
    openIntent: Intent,
    showPlayPause: Boolean,
    isPlaying: Boolean,
    accent: Color,
    continueUrl: String? = null,
    continueStartMs: Long = 0L
) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            GlanceModifier.defaultWeight().clickable(actionStartActivity(openIntent)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Image(
                    provider = if (thumb != null) ImageProvider(thumb) else ImageProvider(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.width(112.dp).height(63.dp).cornerRadius(10.dp).background(Color.Black)
                )
                if (progress != null && progress > 0f) {
                    Spacer(GlanceModifier.height(3.dp))
                    Row(GlanceModifier.width(112.dp).height(3.dp).background(Color(0x33FFFFFF))) {
                        Box(GlanceModifier.width((112 * progress).dp).fillMaxHeight().background(accent)) {}
                    }
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            Column {
                Text(label, style = TextStyle(color = ColorProvider(accent), fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Text(title, maxLines = 2, style = TextStyle(color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, style = TextStyle(color = TextDim, fontSize = 12.sp))
            }
        }
        if (!showPlayPause && continueUrl != null) {
            Spacer(GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(android.R.drawable.ic_media_play),
                contentDescription = "Play",
                modifier = GlanceModifier.size(44.dp).cornerRadius(22.dp).background(accent).padding(10.dp)
                    .clickable(
                        actionRunCallback<PlayContinueAction>(
                            androidx.glance.action.actionParametersOf(UrlKey to continueUrl, StartKey to continueStartMs)
                        )
                    )
            )
        }
        if (showPlayPause) {
            Spacer(GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = GlanceModifier.size(44.dp).cornerRadius(22.dp).background(accent).padding(10.dp)
                    .clickable(actionRunCallback<PlayPauseAction>())
            )
        }
    }
}

private fun formatLeft(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.youtubly.app.MainActivity
import com.youtubly.app.data.local.entity.PlaylistSummary
import com.youtubly.app.data.repository.PlaylistRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistsWidgetEntryPoint {
    fun playlistRepository(): PlaylistRepository
}

private val Bg = Color(0xFF14141C)
private val TextMain = ColorProvider(Color(0xFFEDEDF5))
private val TextDim = ColorProvider(Color(0xFF9A9AB0))
private val Accent = ColorProvider(com.youtubly.app.presentation.theme.Ember)

/** Home-screen widget: your playlists (not the finished ones); tap one to open it in the app. */
class PlaylistsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context.applicationContext, PlaylistsWidgetEntryPoint::class.java).playlistRepository()
        val lists = runCatching { repo.playlists.first() }.getOrDefault(emptyList()).filter { !it.isFinished }.take(4)
        provideContent { PlaylistsContent(context, lists) }
    }

    companion object {
        suspend fun refresh(context: Context) = runCatching { PlaylistsWidget().updateAll(context) }
    }
}

class PlaylistsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistsWidget()
}

private fun openPlaylistIntent(context: Context, id: Long?): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(if (id != null) "youtubly://playlist/$id" else "youtubly://playlists")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

@Composable
private fun PlaylistsContent(context: Context, lists: List<PlaylistSummary>) {
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(Bg).padding(12.dp)) {
        Text(
            "Playlists",
            modifier = GlanceModifier.clickable(actionStartActivity(openPlaylistIntent(context, null))),
            style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        )
        Spacer(GlanceModifier.height(6.dp))
        if (lists.isEmpty()) {
            Text("No playlists yet", style = TextStyle(color = TextDim, fontSize = 13.sp))
        }
        lists.forEach { p ->
            Column(
                GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(actionStartActivity(openPlaylistIntent(context, p.id)))
            ) {
                Text(p.name, maxLines = 1, style = TextStyle(color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Text(
                    if (p.autoRemoveFinished && p.finishedCount > 0) "${p.videoCount} left" else "${p.videoCount} videos",
                    maxLines = 1, style = TextStyle(color = TextDim, fontSize = 12.sp)
                )
            }
        }
    }
}

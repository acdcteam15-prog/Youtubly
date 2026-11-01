package com.youtubly.app.playback

import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.VideoRepository
import com.youtubly.app.domain.usecase.GetStreamInfoUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays through [PlayerController.queue]. This is the only place that advances the queue, so
 * it works the same whether the full player is open, minimised to the floating player,
 * in system PiP, or playing in the background with the screen off.
 *
 * It loads the next stream and hands it to the singleton player. An open player screen sees
 * [PlayerController.nowPlayingId] change and re-attaches to what's already playing (no restart).
 */
@Singleton
class QueuePlayer @Inject constructor(
    private val controller: PlayerController,
    private val getStreamInfo: GetStreamInfoUseCase,
    private val videoRepository: VideoRepository,
    private val userPreferences: com.youtubly.app.data.local.datastore.UserPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short status lines ("Couldn't play X — skipped") for the player to toast. */
    val messages: SharedFlow<String> = _messages

    init {
        scope.launch {
            controller.playbackEnded.collect {
                // Only streamed videos that belong to the queue (downloads clear it).
                val q = controller.queue.value ?: return@collect
                if (q.current?.id != controller.currentVideoId) return@collect
                // Autoplay off: stop at the end of this video. Next/previous still work by hand.
                if (!userPreferences.autoplayNext.first()) return@collect
                skip(+1)
            }
        }
    }

    /** +1 = next, -1 = previous. No-op at either end. */
    fun skip(direction: Int) {
        val next = (if (direction > 0) controller.queueNext() else controller.queuePrevious()) ?: return
        playItem(next, direction)
    }

    /** Jump straight to [index] in the current queue (tapping an item in the queue list). */
    fun jumpTo(index: Int) {
        val q = controller.queue.value ?: return
        if (index !in q.items.indices || index == q.index) return
        controller.setQueue(q.title, q.items, index)
        playItem(q.items[index], if (index > q.index) 1 else -1)
    }

    private fun playItem(video: Video, direction: Int) {
        job?.cancel()
        job = scope.launch {
            getStreamInfo(video.url).fold(
                onSuccess = { info ->
                    runCatching { controller.play(info, controller.currentOptions.copy(qualityHeight = null, caption = null, translateTo = null), 0L) }
                        .onSuccess { videoRepository.recordWatched(info.video) }
                        .onFailure { failAndSkip(video, direction) }
                },
                onFailure = { failAndSkip(video, direction) }
            )
        }
    }

    private fun failAndSkip(video: Video, direction: Int) {
        _messages.tryEmit("Couldn't play \"${video.title}\" — skipped")
        skip(direction)
    }
}

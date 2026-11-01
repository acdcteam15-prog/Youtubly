package com.youtubly.app.presentation.hidden

import androidx.lifecycle.ViewModel
import com.youtubly.app.playback.HiddenItem
import com.youtubly.app.playback.VideoActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HiddenViewModel @Inject constructor(private val actions: VideoActions) : ViewModel() {
    val videos: StateFlow<List<HiddenItem>> = actions.hiddenVideoItems
    val channels: StateFlow<List<HiddenItem>> = actions.blockedChannelItems

    fun restoreVideo(item: HiddenItem) = actions.restoreVideo(item.key)
    fun restoreChannel(item: HiddenItem) = actions.restoreChannel(item.key)
    fun restoreAllVideos() = actions.restoreAllVideos()
    fun restoreAllChannels() = actions.restoreAllChannels()
}

package com.youtubly.app.data.repository

import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.library.ChannelGroups
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Your own channel groups ("Lectures", "News"), kept on this device. */
@Singleton
class ChannelGroupsRepository @Inject constructor(private val prefs: UserPreferences) {
    val groups: Flow<Map<String, List<String>>> = prefs.channelGroups

    suspend fun toggle(group: String, channelUrl: String) {
        val g = prefs.channelGroups.first()
        prefs.setChannelGroups(
            if (channelUrl in g[group].orEmpty()) ChannelGroups.remove(g, group, channelUrl) else ChannelGroups.add(g, group, channelUrl)
        )
    }

    /** Creates [name]; when [channelUrl] is given the channel is put in it straight away. */
    suspend fun create(name: String, channelUrl: String? = null) {
        val g = prefs.channelGroups.first()
        val withGroup = ChannelGroups.create(g, name)
        prefs.setChannelGroups(if (channelUrl != null) ChannelGroups.add(withGroup, name, channelUrl) else withGroup)
    }

    suspend fun rename(old: String, new: String) = prefs.setChannelGroups(ChannelGroups.rename(prefs.channelGroups.first(), old, new))
    suspend fun delete(name: String) = prefs.setChannelGroups(ChannelGroups.deleteGroup(prefs.channelGroups.first(), name))
    suspend fun removeChannel(group: String, channelUrl: String) =
        prefs.setChannelGroups(ChannelGroups.remove(prefs.channelGroups.first(), group, channelUrl))
}

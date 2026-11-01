package com.youtubly.app.blur

import com.youtubly.app.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelRef(val id: String, val name: String, val avatarUrl: String?) {
    fun encode() = listOf(id, name, avatarUrl.orEmpty()).joinToString(SEP)
    companion object {
        const val SEP = "\u001F"
        fun decode(s: String) = s.split(SEP).takeIf { it.size >= 2 }?.let { ChannelRef(it[0], it[1], it.getOrNull(2)?.ifBlank { null }) }
    }
}

private val UC_ID = Regex("UC[\\w-]{22}")

/**
 * Stable whitelist key for a channel. Video.uploaderUrl, Channel.url and SubscribedChannel
 * all resolve to the same "UC…" id; handle/custom URLs fall back to their normalized path.
 */
fun channelKeyOf(urlOrId: String?): String? {
    val s = urlOrId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    UC_ID.find(s)?.let { return it.value }
    return s.substringAfter("youtube.com").substringBefore('?').trimEnd('/').lowercase().ifEmpty { s }
}

/** Channels offered as one-tap suggestions on the Excluded channels screen. */
interface SubscriptionsProvider {
    val subscriptions: Flow<List<ChannelRef>>
}

@Singleton
class AccountSubscriptionsProvider @Inject constructor(
    private val accountRepository: AccountRepository,
) : SubscriptionsProvider {
    // Signed-out or offline → empty list, the suggestions section simply stays hidden.
    override val subscriptions: Flow<List<ChannelRef>> = flow {
        emit(
            accountRepository.subscribedChannels().getOrNull().orEmpty()
                .map { ChannelRef(it.channelId, it.name, it.avatarUrl) }
        )
    }
}

package com.youtubly.app.data.repository

import android.util.Log
import com.youtubly.app.core.NetworkPolicy
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.data.remote.sponsorblock.SponsorBlockApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Community replacement for a clickbait title and/or thumbnail. Null fields mean "keep original". */
data class Branding(val title: String?, val thumbnailUrl: String?)

@Singleton
class DeArrowRepository @Inject constructor(
    private val api: SponsorBlockApi,
    prefs: UserPreferences,
    networkPolicy: NetworkPolicy
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Branding>()
    private val limiter = Semaphore(6)

    val enabled: StateFlow<Boolean> = prefs.deArrow.stateIn(scope, SharingStarted.Eagerly, true)

    /** Data saver on mobile data: keep DeArrow titles (tiny JSON) but never fetch its
     *  rendered thumbnails, which are full-size images generated on demand. */
    val thumbnailsBlocked: StateFlow<Boolean> = networkPolicy.dataSaverActive

    fun cached(videoId: String): Branding? = cache[videoId]

    suspend fun branding(videoId: String): Branding {
        cache[videoId]?.let { return it }
        return withContext(Dispatchers.IO) {
            limiter.withPermit {
                val result = try {
                    val dto = api.getBranding(videoId)
                    // Same rules as the DeArrow extension: skip the original, skip downvoted entries.
                    val title = dto.titles.firstOrNull { !it.original && (it.locked || it.votes >= 0) }
                        ?.title?.removePrefix(">")?.trim()?.ifBlank { null }
                    val thumb = dto.thumbnails.firstOrNull { !it.original && it.timestamp != null && (it.locked || it.votes >= 0) }
                        ?.timestamp?.let { "$THUMB_BASE?videoID=$videoId&time=$it" }
                    Branding(title, thumb)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: HttpException) {
                    Branding(null, null) // 404 = no submissions for this video
                } catch (t: Throwable) {
                    Log.w("YoutublyDeArrow", "branding($videoId) failed: ${t.message}")
                    return@withPermit Branding(null, null) // don't cache transient network errors
                }
                cache[videoId] = result
                result
            }
        }
    }

    private companion object {
        const val THUMB_BASE = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
    }
}

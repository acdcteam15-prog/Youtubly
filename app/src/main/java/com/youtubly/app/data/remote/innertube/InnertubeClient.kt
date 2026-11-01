package com.youtubly.app.data.remote.innertube

import android.util.Log
import com.youtubly.app.data.local.datastore.AuthTokenStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class NotSignedInException : Exception("Sign in to YouTube on the You tab first")
class SessionExpiredException : Exception("Your YouTube session expired — sign in again on the You tab")

/**
 * Talks to YouTube's internal web API ("InnerTube") exactly like youtube.com does,
 * authenticated with the signed-in session cookies + SAPISIDHASH signature.
 */
@Singleton
class InnertubeClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authTokenStore: AuthTokenStore,
    private val json: Json
) {
    @Volatile private var clientVersion: String? = null

    private fun webClientVersion(): String = clientVersion ?: runCatching {
        YoutubeParsingHelper.getClientVersion()
    }.getOrNull().orEmpty().ifBlank { FALLBACK_CLIENT_VERSION }.also { clientVersion = it }

    /**
     * POST https://www.youtube.com/youtubei/v1/{endpoint} with a WEB context — authenticated
     * when signed in. With [allowAnonymous] a signed-out user gets a plain public request
     * instead of NotSignedInException (for public data such as channel search).
     */
    suspend fun post(endpoint: String, allowAnonymous: Boolean = false, body: JsonObjectBuilder.() -> Unit): JsonObject {
        val cookies = authTokenStore.current() ?: if (allowAnonymous) null else throw NotSignedInException()
        val version = webClientVersion()
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", version)
                    put("hl", "en")
                }
            }
            body()
        }

        val request = Request.Builder()
            .url("$BASE/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .apply {
                if (cookies != null) {
                    header("Cookie", cookies)
                    header("Authorization", sapisidHash(cookies) ?: throw SessionExpiredException())
                    header("X-Goog-AuthUser", "0")
                }
            }
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", version)
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                Log.w(TAG, "InnerTube $endpoint -> ${resp.code}: ${text.take(300)}")
                throw SessionExpiredException()
            }
            if (!resp.isSuccessful) error("YouTube returned HTTP ${resp.code} for $endpoint")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    suspend fun browse(browseId: String? = null, continuation: String? = null): JsonObject =
        post("browse") {
            if (continuation != null) put("continuation", continuation)
            else if (browseId != null) put("browseId", browseId)
        }

    /**
     * Search inside one channel — what the web app's channel "Search" tab sends: a browse
     * request for the channel id with the search-tab params plus the query. Works signed out.
     * (NewPipeExtractor 0.26.5 has no channel search tab for YouTube, so this is the path.)
     */
    suspend fun searchChannel(channelId: String, query: String, continuation: String? = null): JsonObject =
        post("browse", allowAnonymous = true) {
            if (continuation != null) {
                put("continuation", continuation)
            } else {
                put("browseId", channelId)
                put("params", CHANNEL_SEARCH_PARAMS)
                put("query", query)
            }
        }

    /**
     * Browser-style auth header: SAPISIDHASH <ts>_<sha1("ts SAPISID origin")>,
     * plus the 1P/3P variants Google now also checks.
     */
    private fun sapisidHash(cookieHeader: String): String? {
        val cookies = cookieHeader.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
        val ts = System.currentTimeMillis() / 1000
        fun hash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$ts $secret $ORIGIN".toByteArray())
            return "${ts}_" + digest.joinToString("") { "%02x".format(it) }
        }
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
        return buildList {
            add("SAPISIDHASH ${hash(sapisid)}")
            cookies["__Secure-1PAPISID"]?.let { add("SAPISID1PHASH ${hash(it)}") }
            cookies["__Secure-3PAPISID"]?.let { add("SAPISID3PHASH ${hash(it)}") }
        }.joinToString(" ")
    }

    companion object {
        private const val TAG = "YoutublyInnertube"
        private const val BASE = "https://www.youtube.com/youtubei/v1"
        const val ORIGIN = "https://www.youtube.com"
        /** Protobuf {2: "search"} (+ the tab flag the web client adds), base64. */
        private const val CHANNEL_SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"
        private const val FALLBACK_CLIENT_VERSION = "2.20260901.00.00"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}

package com.youtubly.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What the app needs to know about a published build. */
data class ReleaseInfo(
    /** "1.1.0" — from the file name Youtubly-1_1_0-build101.apk. */
    val versionName: String,
    /** The build number (= versionCode); a build is newer when this is larger than ours. */
    val build: Int,
    /** Release notes with the "[required]" marker removed. */
    val notes: String,
    /** True when the release notes contain "[required]": the app asks to install it right away. */
    val required: Boolean,
    /** Public download address (works for public repositories). */
    val apkUrl: String,
    /** API address of the same file (needed, with a token, for private repositories). */
    val apkApiUrl: String?,
    val sizeBytes: Long,
    /** The ".sha256" file published next to the APK, when present. */
    val sha256Url: String?,
    val sha256ApiUrl: String?,
    val publishedAt: String?,
    /** A small patch from the build installed now to this one (null = none published for it). */
    val patchUrl: String? = null,
    val patchApiUrl: String? = null,
    val patchSize: Long = -1L
)

/** Pure JSON → [ReleaseInfo] logic (unit-tested; no Android types). */
object UpdateParser {
    /** Youtubly-1_1_0-build101.apk → ("1_1_0", "101") */
    private val APK_NAME = Regex("""^.+-(\d+(?:_\d+)*)-build(\d+)\.apk$""", RegexOption.IGNORE_CASE)
    private val SHA_HEX = Regex("""\b[a-fA-F0-9]{64}\b""")
    private val REQUIRED = Regex("""\[required]""", RegexOption.IGNORE_CASE)
    /** Youtubly-patch-from101-to105.ytpatch */
    private val PATCH_NAME = Regex("""^.+-patch-from(\d+)-to(\d+)\.ytpatch$""", RegexOption.IGNORE_CASE)

    /** Parses GitHub's "latest release" JSON. Null when there is no APK asset (or the JSON isn't a release). */
    fun parseRelease(json: String, currentBuild: Int = -1): ReleaseInfo? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (root["draft"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        val assets: JsonArray = root["assets"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val objs = assets.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val apk = objs.firstNotNullOfOrNull { a ->
            val name = a.str("name") ?: return@firstNotNullOfOrNull null
            APK_NAME.matchEntire(name)?.let { m -> a to m }
        } ?: return null
        val (apkObj, match) = apk
        val apkName = apkObj.str("name")!!
        val build = match.groupValues[2].toIntOrNull() ?: return null
        val sha = objs.firstOrNull { it.str("name") == "$apkName.sha256" }
        val body = (root["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        // The small patch made for exactly the build that is installed now (if the release has one).
        val patch = objs.firstOrNull { a ->
            a.str("name")?.let { PATCH_NAME.matchEntire(it) }?.let { m ->
                m.groupValues[1].toIntOrNull() == currentBuild && m.groupValues[2].toIntOrNull() == build
            } == true
        }

        return ReleaseInfo(
            versionName = match.groupValues[1].replace('_', '.'),
            build = build,
            notes = body.replace(REQUIRED, "").replace(Regex("""\n{3,}"""), "\n\n").trim(),
            required = REQUIRED.containsMatchIn(body),
            apkUrl = apkObj.str("browser_download_url") ?: return null,
            apkApiUrl = apkObj.str("url"),
            sizeBytes = (apkObj["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
            sha256Url = sha?.str("browser_download_url"),
            sha256ApiUrl = sha?.str("url"),
            publishedAt = root.str("published_at"),
            patchUrl = patch?.str("browser_download_url"),
            patchApiUrl = patch?.str("url"),
            patchSize = (patch?.get("size") as? JsonPrimitive)?.longOrNull ?: -1L
        )
    }

    /** First 64-hex-digit value in a ".sha256" file ("<hash>  <file name>"). */
    fun parseSha256(text: String): String? = SHA_HEX.find(text)?.value?.lowercase()

    fun isNewer(releaseBuild: Int, currentBuild: Int) = releaseBuild > currentBuild

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }
}

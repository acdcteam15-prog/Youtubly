package com.youtubly.app.data.system

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.youtubly.app.blur.BlurPreferencesStore
import com.youtubly.app.data.local.datastore.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure JSON codec for settings backups (no Android types → unit-tested).
 * Each value is stored with a type tag so it restores as exactly the same type:
 *   {"version":1,"app":{"key":{"t":"b","v":true}}, "blur":{...}}
 */
object SettingsBackupCodec {
    const val VERSION = 1

    fun encode(sections: Map<String, Map<String, Any>>): String = buildJsonObject {
        put("version", VERSION)
        sections.forEach { (name, values) ->
            putJsonObject(name) { values.toSortedMap().forEach { (k, v) -> tagged(v)?.let { put(k, it) } } }
        }
    }.toString()

    /** @throws IllegalArgumentException if the text isn't a Youtubly backup. */
    fun decode(text: String): Map<String, Map<String, Any>> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Not a Youtubly settings backup")
        val version = root["version"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Not a Youtubly settings backup")
        require(version <= VERSION) { "This backup is from a newer version of Youtubly" }
        return root.filterKeys { it != "version" }.mapValues { (_, sec) ->
            (sec as? JsonObject)?.mapNotNull { (k, v) -> untag(v)?.let { k to it } }?.toMap() ?: emptyMap()
        }
    }

    private fun tagged(v: Any): JsonObject? = when (v) {
        is Boolean -> buildJsonObject { put("t", "b"); put("v", v) }
        is Int -> buildJsonObject { put("t", "i"); put("v", v) }
        is Long -> buildJsonObject { put("t", "l"); put("v", v) }
        is Float -> buildJsonObject { put("t", "f"); put("v", v) }
        is Double -> buildJsonObject { put("t", "d"); put("v", v) }
        is String -> buildJsonObject { put("t", "s"); put("v", v) }
        is Set<*> -> buildJsonObject { put("t", "ss"); putJsonArray("v") { v.filterIsInstance<String>().sorted().forEach { add(it) } } }
        else -> null
    }

    private fun untag(e: JsonElement): Any? {
        val o = e as? JsonObject ?: return null
        val t = o["t"]?.jsonPrimitive?.contentOrNull ?: return null
        val v = o["v"] ?: return null
        return runCatching {
            when (t) {
                "b" -> v.jsonPrimitive.boolean
                "i" -> v.jsonPrimitive.int
                "l" -> v.jsonPrimitive.long
                "f" -> v.jsonPrimitive.float
                "d" -> v.jsonPrimitive.double
                "s" -> v.jsonPrimitive.content
                "ss" -> v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Exports / imports the app's settings (general, content blur and music remover, including the
 * excluded channels and videos). Needed because Android backup is disabled for this app, so a reinstall wipes them.
 *
 * Not included: sign-in session, downloads, history, playlists, and the gender model file.
 */
@Singleton
class SettingsBackup @Inject constructor(
    private val userPreferences: UserPreferences,
    private val blurStore: BlurPreferencesStore,
    private val musicStore: com.youtubly.app.music.MusicPreferencesStore,
) {
    /** Keys that are per-device state rather than settings. */
    private val skip = setOf("subs_seen_ids", "benchmarks")   // benchmarks: speed of THIS phone

    suspend fun export(): String = SettingsBackupCodec.encode(mapOf(
        "app" to dump(userPreferences.rawStore),
        "blur" to dump(blurStore.ds),
        "music" to dump(musicStore.ds),
    ))

    /** Writes every setting found in the backup over the current one (others are left as they are).
     *  Returns how many values were restored. */
    suspend fun import(text: String): Int {
        val data = SettingsBackupCodec.decode(text)
        return restore(userPreferences.rawStore, data["app"].orEmpty()) + restore(blurStore.ds, data["blur"].orEmpty()) +
            restore(musicStore.ds, data["music"].orEmpty())
    }

    private suspend fun dump(ds: DataStore<Preferences>): Map<String, Any> {
        return ds.data.first().asMap().mapKeys { it.key.name }.filterKeys { it !in skip }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun restore(ds: DataStore<Preferences>, values: Map<String, Any>): Int {
        var n = 0
        ds.edit { p ->
            values.forEach { (k, v) ->
                if (k in skip) return@forEach
                when (v) {
                    is Boolean -> p[booleanPreferencesKey(k)] = v
                    is Int -> p[intPreferencesKey(k)] = v
                    is Long -> p[longPreferencesKey(k)] = v
                    is Float -> p[floatPreferencesKey(k)] = v
                    is Double -> p[doublePreferencesKey(k)] = v
                    is String -> p[stringPreferencesKey(k)] = v
                    is Set<*> -> p[stringSetPreferencesKey(k)] = v as Set<String>
                    else -> return@forEach
                }
                n++
            }
        }
        return n
    }
}

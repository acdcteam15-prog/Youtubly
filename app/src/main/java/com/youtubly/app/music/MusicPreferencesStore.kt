package com.youtubly.app.music

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.youtubly.app.blur.ChannelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MusicPreferencesStore(internal val ds: DataStore<Preferences>) {
    private object K {
        val ENABLED = booleanPreferencesKey("enabled")
        val MODEL = stringPreferencesKey("model")
        val LEVEL = intPreferencesKey("music_level")
        val THREADS = intPreferencesKey("threads")
        val STRENGTH = intPreferencesKey("strength")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val WL_META = stringSetPreferencesKey("whitelist_meta")
        val BENCH = stringSetPreferencesKey("benchmarks")   // "id|secPerBlock|blockSec"
    }

    val settings: Flow<MusicSettings> = ds.data.map { p ->
        val d = MusicSettings()
        MusicSettings(
            enabled = p[K.ENABLED] ?: d.enabled,
            modelId = MusicModels.byId(p[K.MODEL]).id,
            musicLevelPct = p[K.LEVEL] ?: d.musicLevelPct,
            whitelist = p[K.WHITELIST] ?: d.whitelist,
            threads = p[K.THREADS] ?: d.threads,
            strength = (p[K.STRENGTH] ?: d.strength).coerceIn(1, 3),
        )
    }

    suspend fun setEnabled(v: Boolean) = ds.edit { it[K.ENABLED] = v }
    suspend fun setModel(id: String) = ds.edit { it[K.MODEL] = MusicModels.byId(id).id }
    suspend fun setMusicLevel(v: Int) = ds.edit { it[K.LEVEL] = v.coerceIn(0, MusicSettings.MAX_LEVEL_PCT) }
    suspend fun setStrength(v: Int) = ds.edit { it[K.STRENGTH] = v.coerceIn(1, 3) }
    suspend fun setThreads(v: Int) = ds.edit { it[K.THREADS] = v.coerceIn(1, 8) }

    val benchmarks: Flow<Map<String, Benchmark>> = ds.data.map { p ->
        (p[K.BENCH] ?: emptySet()).mapNotNull { s ->
            val parts = s.split('|')
            val sec = parts.getOrNull(1)?.toFloatOrNull(); val blk = parts.getOrNull(2)?.toFloatOrNull()
            if (parts.size == 3 && sec != null && blk != null) Benchmark(parts[0], sec, blk) else null
        }.associateBy { it.modelId }
    }
    suspend fun saveBenchmark(b: Benchmark) = ds.edit { p ->
        p[K.BENCH] = (p[K.BENCH] ?: emptySet()).filterNot { it.startsWith(b.modelId + "|") }.toSet() +
            "${b.modelId}|${b.secondsPerBlock}|${b.blockSeconds}"
    }

    val whitelistChannels: Flow<List<ChannelRef>> = ds.data.map { p ->
        val meta = (p[K.WL_META] ?: emptySet()).mapNotNull(ChannelRef::decode).associateBy { it.id }
        (p[K.WHITELIST] ?: emptySet()).map { meta[it] ?: ChannelRef(it, it, null) }.sortedBy { it.name.lowercase() }
    }
    suspend fun addToWhitelist(ch: ChannelRef) = ds.edit { p ->
        p[K.WHITELIST] = (p[K.WHITELIST] ?: emptySet()) + ch.id
        p[K.WL_META] = (p[K.WL_META] ?: emptySet()).filterNot { it.startsWith(ch.id + ChannelRef.SEP) }.toSet() + ch.encode()
    }
    suspend fun removeFromWhitelist(id: String) = ds.edit { p ->
        p[K.WHITELIST] = (p[K.WHITELIST] ?: emptySet()) - id
        p[K.WL_META] = (p[K.WL_META] ?: emptySet()).filterNot { it.startsWith(id + ChannelRef.SEP) }.toSet()
    }
    suspend fun clearWhitelist() = ds.edit { it.remove(K.WHITELIST); it.remove(K.WL_META) }
}

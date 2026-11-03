package com.example.remoty

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers. Tokens are encrypted by [Vault]; the rest is plain app-private storage. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("remoty", Context.MODE_PRIVATE)

    fun profiles(): List<Profile> {
        val arr = try { JSONArray(sp.getString("profiles", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Profile(
                id = o.optString("id"), name = o.optString("name"), networkId = o.optString("network"),
                ip = o.optString("ip"), port = o.optInt("port", 8765), token = Vault.decrypt(o.optString("token")),
            )
        }
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("network", it.networkId)
                    .put("ip", it.ip).put("port", it.port).put("token", Vault.encrypt(it.token))
            )
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    var appLock: Boolean
        get() = sp.getBoolean("lock", false)
        set(v) { sp.edit().putBoolean("lock", v).apply() }

    fun favorites(profileId: String): List<String> = strings("fav_$profileId")
    fun setFavorites(profileId: String, list: List<String>) = putStrings("fav_$profileId", list)

    fun lastFolder(profileId: String): String = sp.getString("last_$profileId", "") ?: ""
    fun setLastFolder(profileId: String, path: String) { sp.edit().putString("last_$profileId", path).apply() }

    /** GitHub token for building without the laptop: encrypted with the Android Keystore like the pairing token. */
    var cloudToken: String
        get() = Vault.decrypt(sp.getString("cloud_token", "") ?: "")
        set(v) { sp.edit().putString("cloud_token", if (v.isBlank()) "" else Vault.encrypt(v)).apply() }

    fun cloudProjects(): List<CloudProject> {
        val arr = try { JSONArray(sp.getString("cloud_projects", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CloudProject(o.optString("name"), o.optString("repo"), o.optString("workflow", "build.yml"), o.optString("branch", "main"))
        }
    }

    fun saveCloudProjects(list: List<CloudProject>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("repo", it.repo).put("workflow", it.workflow).put("branch", it.branch)) }
        sp.edit().putString("cloud_projects", arr.toString()).apply()
    }

    var themeKey: String
        get() = sp.getString("theme", "midnight") ?: "midnight"
        set(v) { sp.edit().putString("theme", v).apply() }

    var autoInstall: Boolean
        get() = sp.getBoolean("auto_install", true)
        set(v) { sp.edit().putBoolean("auto_install", v).apply() }

    private fun strings(key: String): List<String> {
        val arr = try { JSONArray(sp.getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun putStrings(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}

package com.example.remoty

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AgentException(val code: Int, message: String) : Exception(message)

/** Talks to the laptop agent. All traffic goes 127.0.0.1 -> [Forwarder] -> embedded ZeroTier -> laptop. */
class Api(private val p: Profile) {

    private fun base() = "http://127.0.0.1:${Forwarder.localPortFor(p.ip, p.port)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(method: String, path: String, body: JSONObject?, timeoutMs: Int): HttpURLConnection {
        val c = URL(base() + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("X-Remoty-Token", p.token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        return c
    }

    private fun fail(code: Int, bytes: ByteArray): Nothing {
        val raw = String(bytes, Charsets.UTF_8)
        val msg = try { JSONObject(raw).optString("detail", raw) } catch (e: Exception) { raw }
        throw AgentException(code, msg.ifBlank { "HTTP $code" })
    }

    private fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 20_000): ByteArray {
        val c = open(method, path, body, timeoutMs)
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) fail(code, bytes)
        return bytes
    }

    private fun json(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 20_000) =
        JSONObject(String(call(method, path, body, timeoutMs), Charsets.UTF_8))

    /** Round-trip time in ms (no token needed). */
    fun ping(timeoutMs: Int = 8_000): Long {
        val t = System.currentTimeMillis()
        val c = URL(base() + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        if (c.responseCode != 200) throw AgentException(c.responseCode, "agent not healthy")
        c.inputStream.use { it.readBytes() }
        return System.currentTimeMillis() - t
    }

    fun info(): AgentInfo {
        val o = json("GET", "/info", timeoutMs = 30_000)
        val tools = o.optJSONObject("tools")
        val map = LinkedHashMap<String, Boolean>()
        tools?.keys()?.forEach { map[it] = tools.optBoolean(it) }
        val roots = o.optJSONArray("roots")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return AgentInfo(o.optString("name"), o.optString("version"), roots, map, o.optInt("running"), o.optBoolean("remote_control"), o.optBoolean("keep_awake", true))
    }

    fun list(path: String): FsListing {
        val o = json("GET", "/fs/list?path=${enc(path)}")
        val arr = o.getJSONArray("entries")
        val entries = (0 until arr.length()).map {
            val e = arr.getJSONObject(it)
            FsEntry(e.getString("name"), e.getString("path"), e.getBoolean("dir"), e.optLong("size"))
        }
        return FsListing(o.optString("path"), if (o.isNull("parent")) null else o.optString("parent"), entries)
    }

    fun mkdir(path: String) { json("POST", "/fs/mkdir", JSONObject().put("path", path)) }
    fun readFile(path: String): String = json("GET", "/fs/read?path=${enc(path)}").getString("text")
    fun writeFile(path: String, text: String) { json("PUT", "/fs/write", JSONObject().put("path", path).put("text", text)) }

    fun createJob(folder: String, uploadId: String?, script: String): JobSummary {
        val b = JSONObject().put("folder", folder).put("script", script)
        if (uploadId != null) b.put("upload_id", uploadId)
        return JobSummary.from(json("POST", "/jobs", b))
    }

    /** Streams a zip to the laptop. [onProgress] gets the bytes sent so far. Returns the upload id. */
    fun upload(zip: PickedZip, onProgress: (Long) -> Unit): String {
        val c = URL(base() + "/uploads?name=${enc(zip.name)}").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 600_000
        c.setRequestProperty("X-Remoty-Token", p.token)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(zip.size)
        c.outputStream.use { out ->
            zip.file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        }
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) fail(code, bytes)
        return JSONObject(String(bytes, Charsets.UTF_8)).getString("upload_id")
    }

    fun inspect(folder: String, uploadId: String?, zipName: String): Inspect {
        val q = "/inspect?folder=${enc(folder)}" + (if (uploadId != null) "&upload_id=$uploadId" else "")
        val o = json("GET", q, timeoutMs = 60_000)
        val arr = o.getJSONArray("scripts")
        val scripts = (0 until arr.length()).map {
            val e = arr.getJSONObject(it)
            ScriptEntry(e.getString("path"), e.optString("source") == "zip")
        }
        return Inspect(
            folder = o.optString("folder"), uploadId = uploadId, zipName = zipName,
            files = o.optInt("files"), overwrite = o.optInt("overwrite"), new = o.optInt("new"),
            scripts = scripts, defaultScript = if (o.isNull("default_script")) null else o.optString("default_script"),
        )
    }

    fun sendInput(id: String, text: String) { json("POST", "/jobs/$id/input", JSONObject().put("text", text)) }

    fun jobs(): List<JobSummary> {
        val arr: JSONArray = json("GET", "/jobs").getJSONArray("jobs")
        return (0 until arr.length()).map { JobSummary.from(arr.getJSONObject(it)) }
    }

    /** Returns the job plus the log lines after index [since], and the next index to ask for. */
    fun job(id: String, since: Int): Triple<JobSummary, List<LogLine>, Int> {
        val o = json("GET", "/jobs/$id?since=$since")
        val arr = o.getJSONArray("lines")
        val lines = (0 until arr.length()).map { LogLine(arr.getJSONObject(it).optString("k"), arr.getJSONObject(it).optString("s")) }
        return Triple(JobSummary.from(o), lines, o.optInt("next", since))
    }

    fun stop(id: String) { json("POST", "/jobs/$id/stop") }
    fun rollback(id: String) { json("POST", "/jobs/$id/rollback") }
    fun report(id: String): String = String(call("GET", "/jobs/$id/report"), Charsets.UTF_8)
    fun screenshot(width: Int = 1000, quality: Int = 70): ByteArray = call("GET", "/screenshot?w=$width&q=$quality", timeoutMs = 30_000)

    fun problems(id: String): List<Problem> {
        val arr = json("GET", "/jobs/$id/problems").getJSONArray("problems")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Problem(o.optString("title"), o.optString("detail"), o.optString("file"), o.optInt("line"))
        }
    }

    fun audit(limit: Int = 300): List<AuditEntry> {
        val arr = json("GET", "/audit?limit=$limit").getJSONArray("entries")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AuditEntry(o.optString("ts"), o.optString("ip"), o.optString("action"), o.optString("detail"))
        }
    }

    fun diagnostics(): String = String(call("GET", "/diagnostics"), Charsets.UTF_8)

    /** Mouse and keyboard on the laptop (needs "Allow remote control" in the agent window). */
    fun remote(body: JSONObject) { json("POST", "/remote", body, 10_000) }

    /** The newest Remoty APK on the laptop, or null if Rebuild-APK.bat has not produced one yet. */
    fun latestApk(): UpdateInfo? {
        val c = open("GET", "/latest-apk", null, 60_000)
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code == 404) return null
        if (code !in 200..299) fail(code, bytes)
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return UpdateInfo(o.getInt("build"), o.getString("name"), o.getString("path"))
    }

    fun downloadApk(path: String, dest: File) = download("/apk?path=${enc(path)}", dest)

    fun downloadArtifact(id: String, dest: File) = download("/jobs/$id/artifact", dest)

    private fun download(url: String, dest: File) {
        val c = open("GET", url, null, 120_000)
        val code = c.responseCode
        if (code !in 200..299) fail(code, c.errorStream?.use { it.readBytes() } ?: ByteArray(0))
        dest.parentFile?.mkdirs()
        c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    /** URL the preview WebView loads (a localhost port that tunnels to the laptop's app). */
    fun previewUrl(port: Int) = "http://127.0.0.1:${Forwarder.localPortFor(p.ip, port)}/"
}

package com.example.remoty

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.ZipFile

class GitHubException(val code: Int, message: String) : Exception(message)

/** Just enough of the GitHub REST API to replace files in a repository, start the Build workflow, and fetch the APK. */
class GitHubApi(private val token: String) {
    private val base = "https://api.github.com"

    private fun open(method: String, url: String, auth: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 90_000
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return c
    }

    private fun explain(code: Int, text: String): String {
        val m = try { JSONObject(text).optString("message") } catch (e: Exception) { text.take(200) }
        return when (code) {
            401 -> "GitHub rejected the token (401). Check that it is correct and has not expired."
            403 -> "GitHub refused (403): $m. The token needs Contents and Actions permissions on this repository."
            404 -> "Not found (404): $m. Check the repository name, and that the token can access it."
            else -> "GitHub error $code: $m"
        }
    }

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val c = open(method, base + path)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw GitHubException(code, explain(code, text))
        return text
    }

    // ---- committing the zip's files
    fun branchSha(repo: String, branch: String): String =
        JSONObject(call("GET", "/repos/$repo/git/ref/heads/$branch")).getJSONObject("object").getString("sha")

    fun commitTree(repo: String, commitSha: String): String =
        JSONObject(call("GET", "/repos/$repo/git/commits/$commitSha")).getJSONObject("tree").getString("sha")

    fun createBlob(repo: String, bytes: ByteArray): String {
        val b = JSONObject().put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64")
        return JSONObject(call("POST", "/repos/$repo/git/blobs", b)).getString("sha")
    }

    /** Entries are (path, mode, blob sha). Files that are not in the list stay as they are (base_tree). */
    fun createTree(repo: String, baseTree: String, entries: List<Triple<String, String, String>>): String {
        val arr = JSONArray()
        entries.forEach { (path, mode, sha) -> arr.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", sha)) }
        val b = JSONObject().put("base_tree", baseTree).put("tree", arr)
        return JSONObject(call("POST", "/repos/$repo/git/trees", b)).getString("sha")
    }

    fun createCommit(repo: String, message: String, tree: String, parent: String): String {
        val b = JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray().put(parent))
        return JSONObject(call("POST", "/repos/$repo/git/commits", b)).getString("sha")
    }

    fun updateRef(repo: String, branch: String, sha: String) {
        call("PATCH", "/repos/$repo/git/refs/heads/$branch", JSONObject().put("sha", sha).put("force", false))
    }

    // ---- running the workflow
    fun dispatch(repo: String, workflow: String, branch: String) {
        call("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", JSONObject().put("ref", branch))
    }

    fun recentRuns(repo: String, workflow: String, branch: String): List<JSONObject> {
        val arr = JSONObject(call("GET", "/repos/$repo/actions/workflows/$workflow/runs?event=workflow_dispatch&branch=$branch&per_page=5"))
            .getJSONArray("workflow_runs")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun run(repo: String, id: Long): JSONObject = JSONObject(call("GET", "/repos/$repo/actions/runs/$id"))

    fun jobs(repo: String, runId: Long): List<JSONObject> {
        val arr = JSONObject(call("GET", "/repos/$repo/actions/runs/$runId/jobs")).getJSONArray("jobs")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun cancelRun(repo: String, id: Long) { try { call("POST", "/repos/$repo/actions/runs/$id/cancel") } catch (_: Exception) {} }

    fun artifacts(repo: String, runId: Long): List<JSONObject> {
        val arr = JSONObject(call("GET", "/repos/$repo/actions/runs/$runId/artifacts")).getJSONArray("artifacts")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** GitHub answers with a redirect to a signed address: follow it ourselves, without the token. */
    private fun redirected(path: String): HttpURLConnection {
        val c = open("GET", base + path)
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in 300..399) {
            val target = URL(c.getHeaderField("Location")).openConnection() as HttpURLConnection
            target.connectTimeout = 20_000
            target.readTimeout = 120_000
            return target
        }
        if (code !in 200..299) {
            throw GitHubException(code, explain(code, c.errorStream?.use { String(it.readBytes()) } ?: ""))
        }
        return c
    }

    fun downloadArtifact(repo: String, artifactId: Long, dest: File) {
        dest.parentFile?.mkdirs()
        redirected("/repos/$repo/actions/artifacts/$artifactId/zip").inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    fun jobLog(repo: String, jobId: Long): String =
        redirected("/repos/$repo/actions/jobs/$jobId/logs").inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

/** Reading a project zip the way unzipping over a folder would: one top-level folder is stripped. */
object CloudZip {
    class Entry(val path: String, val bytes: ByteArray, val exec: Boolean)

    fun read(file: File): List<Entry> {
        ZipFile(file).use { z ->
            val names = z.entries().asSequence().filter { !it.name.startsWith("__MACOSX/") && it.name.trim('/').isNotEmpty() }.toList()
            val tops = names.map { it.name.substringBefore('/') }.toSet()
            val prefix = if (tops.size == 1 && names.all { it.name.startsWith(tops.first() + "/") }) tops.first() + "/" else ""
            val out = ArrayList<Entry>()
            for (e in names) {
                if (e.isDirectory) continue
                val rel = e.name.removePrefix(prefix).replace('\\', '/')
                if (rel.isEmpty() || rel.startsWith("/") || rel.contains("..") || rel.contains(':')) continue
                if (rel.startsWith(".git/")) continue
                if (e.size > 90L * 1024 * 1024) throw IllegalStateException("$rel is bigger than 90 MB: GitHub will not take it")
                val bytes = z.getInputStream(e).use { it.readBytes() }
                out.add(Entry(rel, bytes, rel == "gradlew" || rel.endsWith(".sh")))
            }
            return out
        }
    }

    /** Pulls the first .apk out of the downloaded artifact (a zip) into [outDir]. */
    fun extractApk(zip: File, outDir: File): File {
        outDir.mkdirs()
        ZipFile(zip).use { z ->
            val e = z.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw IllegalStateException("The build finished but its download has no APK in it")
            val dest = File(outDir, e.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
            z.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            return dest
        }
    }
}

/** Picks the real errors out of a build log (the laptop agent does the same for its logs). */
object ProblemParser {
    private val kt = Regex("""^e: (?:file://)?(.+?\.kts?):(\d+):(\d+):?\s*(.*)$""")
    private val stamp = Regex("""^\S*\d{4}-\d\d-\d\dT[\d:.]+Z """)
    private val titles = Regex("""^(?:> )?(Execution failed for task .*|Could not (?:resolve|find|get|download) .*|Unresolved reference.*|Task .* FAILED|BUILD FAILED.*)$""")

    fun parse(raw: List<String>): List<Problem> {
        val lines = raw.map { stamp.replace(it.trimStart('\uFEFF'), "").trim() }
        val out = ArrayList<Problem>()
        val seen = HashSet<String>()
        fun add(title: String, detail: String = "", file: String = "", line: Int = 0) {
            val t = title.trim()
            if (t.isEmpty() || out.size >= 12 || !seen.add("$t|$file|$line")) return
            out.add(Problem(t.take(220), detail.trim().take(400), file, line))
        }
        for ((i, s) in lines.withIndex()) {
            val m = kt.find(s)
            when {
                m != null -> add(m.groupValues[4].ifBlank { "Kotlin compile error" }, "", m.groupValues[1].substringAfterLast('/'), m.groupValues[2].toInt())
                s.startsWith("* What went wrong:") -> {
                    val next = lines.drop(i + 1).take(4).filter { it.isNotEmpty() && !it.startsWith("*") }
                    if (next.isNotEmpty()) add(next[0], next.drop(1).take(2).joinToString(" "))
                }
                s.contains("##[error]") -> add(s.replace("##[error]", ""))
                titles.matches(s) -> add(s)
            }
        }
        return out.sortedBy { if (it.file.isNotEmpty()) 0 else if (Regex("BUILD FAILED|Execution failed|Process completed").containsMatchIn(it.title)) 2 else 1 }
    }
}

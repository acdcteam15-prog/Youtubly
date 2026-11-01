package com.youtubly.app.blur

/** Pure helpers for where the gender model comes from (unit-tested, no Android types). */
object ModelSource {
    private val SEG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /**
     * Accepts `user/repo`, `user/repo/path/file.tflite`, or a huggingface.co link. A `/blob/` link
     * (what the browser address bar shows) is turned into `/resolve/`, which serves the raw file.
     * Only https://huggingface.co is allowed. Returns null when it can't be a .tflite on Hugging Face.
     */
    fun resolveUrl(input: String, defaultFile: String = "gender.tflite", revision: String = "main"): String? {
        val text = input.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "huggingface.co" && host != "www.huggingface.co") return null
            val parts = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            return when {
                parts.size == 2 -> build(parts[0], parts[1], revision, listOf(defaultFile))
                parts.size >= 5 && (parts[2] == "resolve" || parts[2] == "blob") -> build(parts[0], parts[1], parts[3], parts.drop(4))
                parts.size >= 4 && parts[2] == "tree" -> build(parts[0], parts[1], parts[3], listOf(defaultFile))
                else -> null
            }
        }

        val parts = text.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return build(parts[0], parts[1], revision, if (parts.size == 2) listOf(defaultFile) else parts.drop(2))
    }

    private fun build(user: String, repo: String, rev: String, file: List<String>): String? {
        if (!SEG.matches(user) || !SEG.matches(repo) || !SEG.matches(rev)) return null
        if (file.isEmpty() || file.any { !SEG.matches(it) }) return null
        if (!file.last().endsWith(".tflite", ignoreCase = true)) return null
        return "https://huggingface.co/$user/$repo/resolve/$rev/${file.joinToString("/")}"
    }

    /** TFLite files are FlatBuffers; their file identifier "TFL3" sits at bytes 4..7. */
    fun looksLikeTflite(header: ByteArray): Boolean =
        header.size >= 8 && header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() &&
            header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte()
}

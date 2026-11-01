package com.youtubly.app.core

private const val FSI = '\u2068'   // first-strong isolate
private const val PDI = '\u2069'   // pop directional isolate

/**
 * Wraps [this] in Unicode isolates so its own direction (e.g. an Arabic channel name) cannot reorder the
 * numbers and words around it. Without this, "<Arabic> · 81K views" shows as "81 · <Arabic>K views".
 */
fun String.isolated(): String = if (isBlank()) this else "$FSI$this$PDI"

/** "name  ·  81K views  ·  7 days ago": every part isolated, blanks dropped. Show it with a left-to-right text style. */
fun metaLine(vararg parts: String?, separator: String = "  ·  "): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(separator) { it.isolated() }

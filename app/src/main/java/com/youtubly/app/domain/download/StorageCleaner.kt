package com.youtubly.app.domain.download

import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus

/** How the Saved list is ordered. Unfinished transfers always stay on top. */
enum class SavedSort(val label: String) {
    NEWEST("Newest"), LARGEST("Largest"), OLDEST("Oldest"), NAME("A–Z");

    companion object { fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: NEWEST }
}

/** On-disk size of a finished download (stored total, else what was received). */
fun DownloadItem.sizeBytes(): Long = if (totalBytes > 0) totalBytes else downloadedBytes

fun List<DownloadItem>.sortedFor(sort: SavedSort): List<DownloadItem> {
    val (done, active) = partition { it.status == DownloadStatus.COMPLETED }
    val ordered = when (sort) {
        SavedSort.NEWEST -> done.sortedByDescending { it.createdAt }
        SavedSort.OLDEST -> done.sortedBy { it.createdAt }
        SavedSort.LARGEST -> done.sortedByDescending { it.sizeBytes() }
        SavedSort.NAME -> done.sortedBy { it.title.lowercase() }
    }
    return active.sortedByDescending { it.createdAt } + ordered
}

/** One group of "you could free this" suggestions. */
data class CleanerGroup(val kind: Kind, val title: String, val hint: String, val items: List<DownloadItem>) {
    enum class Kind { DUPLICATES, WATCHED, STALE, LARGEST }
    val bytes: Long get() = items.sumOf { it.sizeBytes() }
}

object StorageCleaner {
    const val STALE_DAYS = 30
    const val LARGEST_COUNT = 8

    /**
     * Groups of removable downloads. [excludeId] (what's playing now) is never suggested.
     *  - Duplicates: the same video saved more than once; the largest copy (usually the best quality) is kept.
     *  - Watched: ≥95 % watched.
     *  - Stale: saved over [STALE_DAYS] days ago and barely watched (< 5 %).
     *  - Largest: the biggest files, whatever their state.
     */
    fun groups(all: List<DownloadItem>, nowMs: Long, excludeId: String? = null): List<CleanerGroup> {
        val done = all.filter { it.status == DownloadStatus.COMPLETED && it.id != excludeId }

        val dupes = done.groupBy { it.videoId }.values.filter { it.size > 1 }
            .flatMap { copies -> copies.sortedByDescending { it.sizeBytes() }.drop(1) }
        val dupeIds = dupes.map { it.id }.toSet()

        val watched = done.filter { it.watchedPercent >= 95 && it.id !in dupeIds }
        val watchedIds = watched.map { it.id }.toSet()

        val staleCutoff = nowMs - STALE_DAYS * 24L * 3600_000
        val stale = done.filter { it.createdAt in 1 until staleCutoff && it.watchedPercent < 5 && it.id !in dupeIds && it.id !in watchedIds }

        val largest = done.sortedByDescending { it.sizeBytes() }.take(LARGEST_COUNT).filter { it.sizeBytes() > 0 }

        return listOf(
            CleanerGroup(CleanerGroup.Kind.DUPLICATES, "Duplicates", "Same video saved more than once (the largest copy is kept)", dupes),
            CleanerGroup(CleanerGroup.Kind.WATCHED, "Already watched", "95% or more played", watched),
            CleanerGroup(CleanerGroup.Kind.STALE, "Old and never watched", "Saved over $STALE_DAYS days ago, barely played", stale),
            CleanerGroup(CleanerGroup.Kind.LARGEST, "Biggest files", "What takes the most space", largest)
        ).filter { it.items.isNotEmpty() }
    }
}

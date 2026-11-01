package com.youtubly.app.data.repository

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.youtubly.app.data.local.dao.EditJobDao
import com.youtubly.app.data.local.entity.EditJobEntity
import com.youtubly.app.domain.editor.EditJob
import com.youtubly.app.domain.editor.EditJobStatus
import com.youtubly.app.domain.editor.ClipSpec
import com.youtubly.app.domain.editor.ExportSpec
import com.youtubly.app.domain.editor.ExportSpecCodec
import com.youtubly.app.domain.editor.TimeRange
import com.youtubly.app.editor.EditWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The edit export queue. Jobs live in Room; a single [EditWorker] drains them oldest-first.
 *
 * Queue model: every change that adds work appends one more queue-drain worker to a unique
 * WorkManager chain. Drains therefore never run concurrently (one export at a time — the
 * hardware encoder is the bottleneck anyway), each drain processes everything queued so far,
 * and a drain appended after the queue is empty simply finds nothing and ends.
 * Cancelling a single job only flips its row; the running drain notices and moves on, so
 * nothing else in the chain is affected.
 */
@Singleton
class EditRepository @Inject constructor(
    private val dao: EditJobDao,
    private val workManager: WorkManager
) {
    fun observeJobs(): Flow<List<EditJob>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Queues any export (trim, cut, merge, compress). [sourceDownloadId]/[title]/[thumbnailUrl]
     * describe the job in the list; the spec says what to produce.
     */
    suspend fun enqueue(sourceDownloadId: String, title: String, thumbnailUrl: String?, spec: ExportSpec): String {
        require(spec.isValid) { "Nothing to export" }
        val id = UUID.randomUUID().toString()
        val first = spec.clips.first()
        dao.insert(
            EditJobEntity(
                id = id,
                sourceDownloadId = sourceDownloadId,
                sourcePath = first.path,
                title = title,
                thumbnailUrl = thumbnailUrl,
                sourceDurationMs = first.durationMs,
                startMs = 0,
                endMs = spec.totalMs,
                status = EditJobStatus.QUEUED.name,
                progress = 0,
                outputUri = null,
                outputName = null,
                error = null,
                createdAt = System.currentTimeMillis(),
                finishedAt = null,
                spec = ExportSpecCodec.encode(spec)
            )
        )
        kickQueue()
        return id
    }

    /** Queued jobs are skipped; a running export is stopped within about half a second. */
    suspend fun cancel(id: String) {
        dao.cancel(id, System.currentTimeMillis())
    }

    suspend fun retry(id: String) {
        if (dao.retry(id, System.currentTimeMillis()) > 0) kickQueue()
    }

    /** Removes a finished job from the list. The saved video itself stays in Downloads. */
    suspend fun remove(id: String) {
        dao.removeFinished(id)
    }

    suspend fun clearFinished() {
        dao.clearFinished()
    }

    /** App start: make sure queued work (e.g. after a force-stop) gets picked up. */
    suspend fun resumePendingIfAny() {
        if (dao.activeCount() > 0) kickQueue()
    }

    private fun kickQueue() {
        val request = OneTimeWorkRequestBuilder<EditWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(EditWorker.TAG)
            .build()
        // APPEND_OR_REPLACE (not APPEND): if an earlier drain in the chain was cancelled or
        // failed, APPEND would cancel this one too; this starts a fresh chain instead.
        workManager.enqueueUniqueWork(EditWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun EditJobEntity.toDomain(): EditJob {
        val exportSpec = specOf(this)
        return EditJob(
        id = id,
        sourceDownloadId = sourceDownloadId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        sourceDurationMs = sourceDurationMs,
        range = TimeRange.safe(startMs, endMs),
        status = EditJobStatus.parse(status),
        progress = progress.coerceIn(0, 100),
        outputUri = outputUri,
        outputName = outputName,
        error = error,
        createdAt = createdAt,
        summary = exportSpec?.summary() ?: "Export"
        )
    }

    companion object {
        /** The row's spec; v7 rows (no spec column value) were always a plain trim. */
        fun specOf(e: EditJobEntity): ExportSpec? =
            ExportSpecCodec.decode(e.spec) ?: if (e.spec == null) {
                ExportSpec(listOf(ClipSpec(e.sourcePath, e.sourceDurationMs, listOf(TimeRange.safe(e.startMs, e.endMs)))))
            } else null
    }
}

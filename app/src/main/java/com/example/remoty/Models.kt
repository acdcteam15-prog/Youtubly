package com.example.remoty

import org.json.JSONObject

data class Profile(
    val id: String,
    val name: String,
    val networkId: String,   // 16 hex characters
    val ip: String,          // the laptop's ZeroTier address
    val port: Int,
    val token: String,       // pairing token from the agent
)

data class FsEntry(val name: String, val path: String, val dir: Boolean, val size: Long)
data class FsListing(val path: String, val parent: String?, val entries: List<FsEntry>)

data class LogLine(val kind: String, val text: String)

data class JobSummary(
    val id: String,
    val title: String,
    val folder: String,
    val script: String,
    val zipName: String,
    val status: String,
    val phase: String,
    val created: Double,
    val exitCode: Int?,
    val hasArtifact: Boolean,
    val artifactName: String?,
    val previewPort: Int?,
    val canRollback: Boolean,
    val waitingInput: Boolean,
    val lastLine: String = "",
) {
    val active: Boolean get() = status in ACTIVE
    val failed: Boolean get() = status == "failed"

    companion object {
        val ACTIVE = setOf("queued", "preparing", "running")

        fun from(o: JSONObject) = JobSummary(
            id = o.getString("id"),
            title = o.optString("title"),
            folder = o.optString("folder"),
            script = o.optString("script"),
            zipName = o.optString("zip_name"),
            status = o.optString("status"),
            phase = o.optString("phase"),
            created = o.optDouble("created", 0.0),
            exitCode = if (o.isNull("exit_code")) null else o.optInt("exit_code"),
            hasArtifact = o.optBoolean("has_artifact"),
            artifactName = if (o.isNull("artifact_name")) null else o.optString("artifact_name"),
            previewPort = if (o.isNull("preview_port")) null else o.optInt("preview_port"),
            canRollback = o.optBoolean("can_rollback"),
            waitingInput = o.optBoolean("waiting_input"),
            lastLine = o.optString("last_line"),
        )
    }
}

data class AgentInfo(
    val name: String,
    val version: String,
    val roots: List<String>,
    val tools: Map<String, Boolean>,
    val running: Int,
    val remoteControl: Boolean = false,
    val keepAwake: Boolean = true,
)

/** A zip copied from the phone (file picker or Share) and waiting to be sent to the laptop. */
data class PickedZip(val file: java.io.File, val name: String, val size: Long)

data class ScriptEntry(val path: String, val fromZip: Boolean)

/** What the agent says will happen: shown on the confirm screen before anything runs. */
data class Inspect(
    val folder: String,
    val uploadId: String?,
    val zipName: String,
    val files: Int,
    val overwrite: Int,
    val new: Int,
    val scripts: List<ScriptEntry>,
    val defaultScript: String?,
)

/** One thing that went wrong in a build log (the agent, or the GitHub build, picked it out of the noise). */
data class Problem(val title: String, val detail: String, val file: String, val line: Int)

/** One line of the laptop's activity log: what the phone did, and when. */
data class AuditEntry(val ts: String, val ip: String, val action: String, val detail: String)

/** A GitHub repository the phone can build without the laptop. */
data class CloudProject(val name: String, val repo: String, val workflow: String, val branch: String)

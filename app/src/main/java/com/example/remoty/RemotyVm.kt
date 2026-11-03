package com.example.remoty

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.remoty.ui.Brand
import com.example.remoty.ui.Palettes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.UUID

enum class Stage { Idle, Connecting, Ready }

class RemotyVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    val store = Store(app)

    init { Brand.palette = Palettes.byKey(store.themeKey) }
    var themeKey by mutableStateOf(store.themeKey)

    fun applyTheme(key: String) {
        themeKey = key
        store.themeKey = key
        Brand.palette = Palettes.byKey(key)
    }

    // ---- profiles and connection
    var profiles by mutableStateOf(store.profiles())
    var active by mutableStateOf<Profile?>(null)
    var stage by mutableStateOf(Stage.Idle)
    var agentStep by mutableStateOf("")
    var connectError by mutableStateOf<String?>(null)
    var api: Api? = null
    var info by mutableStateOf<AgentInfo?>(null)
    var online by mutableStateOf(false)
    var latency by mutableStateOf(-1L)
    var tab by mutableStateOf(0)
    var toast by mutableStateOf<String?>(null)
    @Volatile var foreground = true

    private var connectJob: Job? = null
    private var heartbeat: Job? = null
    private var jobPoll: Job? = null
    private val seen = HashMap<String, String>()

    // ---- build form
    var formFolder by mutableStateOf("")
    var favorites by mutableStateOf<List<String>>(emptyList())
    var zip by mutableStateOf<PickedZip?>(null)
    var zipBusy by mutableStateOf(false)          // copying the zip, sending it, or checking it
    var zipProgress by mutableStateOf(0f)
    var zipStatus by mutableStateOf("")
    var sheet by mutableStateOf<Inspect?>(null)    // the "ready to run?" confirmation
    var autoInstall by mutableStateOf(store.autoInstall)
    private var pendingInstall: String? = null
    private val asked = HashSet<String>()

    // ---- folder browser / editor
    var browse by mutableStateOf<FsListing?>(null)
    var browseLoading by mutableStateOf(false)
    var browseError by mutableStateOf<String?>(null)
    var picking by mutableStateOf(false)
    var editorPath by mutableStateOf<String?>(null)
    var editorText by mutableStateOf("")
    var editorOriginal by mutableStateOf("")
    var editorLoading by mutableStateOf(false)

    // ---- jobs
    var jobs by mutableStateOf<List<JobSummary>>(emptyList())
    var openJobId by mutableStateOf<String?>(null)
    var jobDetail by mutableStateOf<JobSummary?>(null)
    val logLines = mutableStateListOf<LogLine>()
    var shot by mutableStateOf<ImageBitmap?>(null)
    var shotLoading by mutableStateOf(false)

    // ---- screens on top of the tabs: "remote", "audit" (and "cloud", which also works with the laptop off)
    var overlay by mutableStateOf<String?>(null)

    // ---- smart error panel, activity log, remote screen
    var problems by mutableStateOf<List<Problem>>(emptyList())
    var audit by mutableStateOf<List<AuditEntry>>(emptyList())
    var auditLoading by mutableStateOf(false)
    var remoteError by mutableStateOf<String?>(null)
    private val progressShown = HashSet<String>()

    // ---- build on GitHub (laptop off)
    var cloudToken by mutableStateOf(store.cloudToken)
    var cloudProjects by mutableStateOf(store.cloudProjects())
    var cloudSelected by mutableStateOf(0)
    var cloudPhase by mutableStateOf("idle")          // idle uploading queued building downloading done failed
    var cloudStatus by mutableStateOf("")
    var cloudProgress by mutableStateOf(0f)
    var cloudSteps by mutableStateOf<List<String>>(emptyList())
    var cloudRunUrl by mutableStateOf("")
    var cloudApk by mutableStateOf<File?>(null)
    var cloudProblems by mutableStateOf<List<Problem>>(emptyList())
    var cloudLog by mutableStateOf("")
    private var cloudJob: Job? = null
    private var cloudRunId = 0L

    // ---- app lock and updates
    var locked by mutableStateOf(store.appLock)
    var appLock by mutableStateOf(store.appLock)
    var updateStatus by mutableStateOf("")
    var updateInfo by mutableStateOf<UpdateInfo?>(null)

    private fun msg(e: Throwable): String = when (e) {
        is AgentException -> when (e.code) {
            401 -> "Wrong pairing token. Copy it again from the laptop (config.json / agent window)."
            429 -> "Too many wrong tokens. Wait a minute."
            else -> e.message ?: "Error ${e.code}"
        }
        is SocketTimeoutException -> "Timed out talking to the laptop."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ================= profiles =================
    fun saveProfile(p: Profile) {
        profiles = profiles.filter { it.id != p.id } + p
        store.saveProfiles(profiles)
    }

    fun deleteProfile(p: Profile) {
        profiles = profiles.filter { it.id != p.id }
        store.saveProfiles(profiles)
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ================= connection =================
    fun connect(p: Profile) {
        connectJob?.cancel(); heartbeat?.cancel(); jobPoll?.cancel()
        active = p
        stage = Stage.Connecting
        connectError = null
        agentStep = ""
        info = null; online = false; jobs = emptyList(); browse = null; seen.clear()
        api = Api(p)
        Session.api = api
        AppLog.i("connecting to ${p.name} ${p.ip}:${p.port}")
        favorites = store.favorites(p.id)
        formFolder = store.lastFolder(p.id)
        connectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                RemotyService.start(ctx, "Connecting to ${p.name}...")
                ZtManager.connect(ctx, p.networkId)
                agentStep = "Reaching the agent at ${p.ip}:${p.port} ..."
                val a = api!!
                a.ping(30_000)
                info = a.info()
                online = true
                stage = Stage.Ready
                AppLog.i("connected, agent ${info?.version}")
                RemotyService.start(ctx, "Connected to ${p.name}")
                startHeartbeat()
                checkUpdate(silent = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.i("connect failed: ${e.message}")
                connectError = if (e is AgentException || e is NumberFormatException) msg(e)
                else if (ZtManager.state.value.phase == ZtPhase.Ready)
                    "Cannot reach the agent at ${p.ip}:${p.port}. Is run-agent.bat running on the laptop, and is the address right? (${msg(e)})"
                else msg(e)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel(); heartbeat?.cancel(); jobPoll?.cancel()
        ZtManager.disconnect()
        Session.api = null
        AppLog.i("disconnected")
        RemotyService.stop(ctx)
        active = null; api = null; stage = Stage.Idle; openJobId = null; editorPath = null; picking = false
        jobs = emptyList(); info = null
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val a = api ?: break
                try { latency = a.ping(); online = true } catch (e: Exception) { online = false; latency = -1 }
                if (online) {
                    try { handleJobs(a.jobs()) } catch (_: Exception) {}
                }
                delay(if (jobs.any { it.active }) 2500 else 8000)
            }
        }
    }

    private fun handleJobs(list: List<JobSummary>) {
        for (j in list) {
            val prev = seen[j.id]
            if (prev != null && prev != j.status) {
                AppLog.i("job ${j.id}: $prev -> ${j.status} (${j.title})")
                val install = j.status == "succeeded" && j.hasArtifact && autoInstall
                if (install && foreground) installApk(j)
                else if (!foreground) when {
                    install -> { pendingInstall = j.id; Notifier.installReady(ctx, j) }
                    j.status == "succeeded" -> Notifier.job(ctx, j.id, "Done: ${j.title}", "Finished successfully")
                    j.status == "failed" -> Notifier.job(ctx, j.id, "Failed: ${j.title}", "Open Remoty to see what went wrong")
                }
            }
            // live progress notification while the app is in the background
            if (j.active && !foreground) { Notifier.progress(ctx, j); progressShown.add(j.id) }
            else if (progressShown.remove(j.id)) Notifier.clearProgress(ctx, j.id)
            if (j.waitingInput && !foreground && asked.add(j.id)) {
                Notifier.job(ctx, j.id, "Waiting for your answer: ${j.title}", "Open Remoty and type your answer")
            }
            if (!j.waitingInput) asked.remove(j.id)
            seen[j.id] = j.status
        }
        jobs = list
    }

    fun refreshJobs() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) { try { handleJobs(a.jobs()) } catch (e: Exception) { toast = msg(e) } }
    }

    // ================= zip and script =================
    /** Copies the zip you picked (or shared) into the app, so it can be sent later whatever happens to the link. */
    fun pickZip(uri: Uri) {
        zipBusy = true; zipStatus = "Reading the zip..."; sheet = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var name = "project.zip"
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                val dir = File(ctx.cacheDir, "inbox").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
                ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                zip = PickedZip(f, name, f.length())
                tab = 0
                toast = "Zip ready: $name"
            } catch (e: Exception) {
                toast = "Could not read that file: ${msg(e)}"
            }
            zipBusy = false; zipStatus = ""
        }
    }

    fun clearZip() { zip = null; sheet = null }

    /** Sends the zip to the laptop, then asks it what will happen (files, scripts) for the confirm screen. */
    fun checkZip() {
        val a = api ?: return
        val z = zip ?: return
        val p = active ?: return
        if (formFolder.isBlank()) { toast = "Choose the project folder first"; return }
        store.setLastFolder(p.id, formFolder)
        zipBusy = true; zipProgress = 0f; zipStatus = "Sending ${z.name}..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = a.upload(z) { sent -> zipProgress = if (z.size > 0) sent.toFloat() / z.size else 0f }
                zipStatus = "Checking..."
                sheet = a.inspect(formFolder, id, z.name)
            } catch (e: Exception) { toast = msg(e) }
            zipBusy = false; zipStatus = ""
        }
    }

    /** No zip: pick a script that is already in the folder. */
    fun checkFolderScripts() {
        val a = api ?: return
        val p = active ?: return
        if (formFolder.isBlank()) { toast = "Choose the project folder first"; return }
        store.setLastFolder(p.id, formFolder)
        zipBusy = true; zipProgress = 0f; zipStatus = "Looking for scripts..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = a.inspect(formFolder, null, "")
                if (r.scripts.isEmpty()) toast = "No .bat, .cmd or .ps1 scripts in that folder" else sheet = r
            } catch (e: Exception) { toast = msg(e) }
            zipBusy = false; zipStatus = ""
        }
    }

    fun cancelSheet() { sheet = null }

    /** The user tapped Run on the confirm screen. [script] is empty to only unzip. */
    fun runSheet(script: String) {
        val a = api ?: return
        val s = sheet ?: return
        sheet = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val j = a.createJob(s.folder, s.uploadId, script)
                jobs = listOf(j) + jobs
                seen[j.id] = j.status
                if (s.uploadId != null) zip = null
                openJob(j.id)
            } catch (e: Exception) { toast = msg(e) }
        }
    }

    fun updateAutoInstall(v: Boolean) { autoInstall = v; store.autoInstall = v }

    /** Called when the app comes to the front: finishes an install that was waiting for it. */
    fun onForeground() {
        val id = pendingInstall ?: return
        pendingInstall = null
        jobs.find { it.id == id }?.let { installApk(it) }
    }

    // ================= folders and files =================
    fun browseTo(path: String) {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            browseLoading = true
            try { browse = a.list(path); browseError = null } catch (e: Exception) { browseError = msg(e) }
            browseLoading = false
        }
    }

    fun startPicking() { picking = true; if (browse == null) browseTo("") }
    fun chooseFolder(path: String) { formFolder = path; picking = false }

    fun toggleFavorite(path: String) {
        val p = active ?: return
        favorites = if (path in favorites) favorites - path else favorites + path
        store.setFavorites(p.id, favorites)
    }

    fun newFolder(name: String) {
        val a = api ?: return
        val cur = browse ?: return
        if (cur.path.isEmpty() || name.isBlank()) return
        val sep = if (cur.path.contains('\\')) "\\" else "/"
        val target = cur.path.trimEnd('\\', '/') + sep + name.trim()
        viewModelScope.launch(Dispatchers.IO) {
            try { a.mkdir(target); browse = a.list(cur.path) } catch (e: Exception) { toast = msg(e) }
        }
    }

    fun openFile(path: String) {
        val a = api ?: return
        editorPath = path; editorText = ""; editorOriginal = ""; editorLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try { val t = a.readFile(path); editorText = t; editorOriginal = t }
            catch (e: Exception) { toast = msg(e); editorPath = null }
            editorLoading = false
        }
    }

    fun saveFile() {
        val a = api ?: return
        val path = editorPath ?: return
        val text = editorText
        viewModelScope.launch(Dispatchers.IO) {
            try { a.writeFile(path, text); editorOriginal = text; toast = "Saved" } catch (e: Exception) { toast = msg(e) }
        }
    }

    fun closeEditor() { editorPath = null }

    // ================= job screen =================
    fun openJob(id: String) {
        jobPoll?.cancel()
        logLines.clear(); shot = null; problems = emptyList()
        jobDetail = jobs.find { it.id == id }
        openJobId = id
        val a = api ?: return
        jobPoll = viewModelScope.launch(Dispatchers.IO) {
            var since = 0
            var problemsLoaded = false
            while (isActive) {
                try {
                    val (s, lines, next) = a.job(id, since)
                    jobDetail = s
                    if (!s.active && !problemsLoaded) {
                        problemsLoaded = true
                        try { problems = a.problems(id) } catch (_: Exception) {}
                    }
                    if (lines.isNotEmpty()) {
                        logLines.addAll(lines)
                        val extra = logLines.size - 4000
                        if (extra > 0) repeat(extra) { logLines.removeAt(0) }
                    }
                    since = next
                    delay(if (lines.size >= 400) 100 else if (s.active) 800 else 2500)
                } catch (e: Exception) { delay(2000) }
            }
        }
    }

    fun closeJob() { jobPoll?.cancel(); openJobId = null; jobDetail = null; shot = null }

    private fun launchApi(block: suspend (Api) -> Unit) {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) { try { block(a) } catch (e: Exception) { toast = msg(e) } }
    }

    fun stopJob(id: String) = launchApi { it.stop(id); toast = "Stopping..." }

    fun rollbackJob(id: String) = launchApi { it.rollback(id); toast = "Rolled back to the snapshot taken before this job" }

    fun rerun(j: JobSummary) = launchApi {
        if (j.script.isBlank()) { toast = "Nothing to run again"; return@launchApi }
        val n = it.createJob(j.folder, null, j.script)
        seen[n.id] = n.status
        jobs = listOf(n) + jobs
        openJob(n.id)
    }

    fun sendInput(id: String, text: String) = launchApi { it.sendInput(id, text) }

    fun copyReport(id: String) = launchApi {
        val text = it.report(id)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Remoty error report", text))
        toast = "Error report copied. Paste it to Claude."
    }

    fun shareReport(id: String) = launchApi {
        val text = it.report(id)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "Share error report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun refreshShot() = launchApi {
        shotLoading = true
        try {
            val bytes = it.screenshot()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp -> shot = bmp.asImageBitmap() }
        } finally { shotLoading = false }
    }

    fun installApk(j: JobSummary) = launchApi {
        toast = "Downloading the APK..."
        val f = File(ctx.cacheDir, "apks/" + (j.artifactName ?: "app.apk"))
        it.downloadArtifact(j.id, f)
        withContext(Dispatchers.Main) {
            if (!Updater.install(ctx, f)) toast = "Allow installs from Remoty in the settings page that opened, then tap Install again."
        }
    }

    fun previewUrl(port: Int): String? = api?.previewUrl(port)

    // ================= notification Install button =================
    fun requestInstall(jobId: String) {
        pendingInstall = jobId
        if (foreground) onForeground()
    }

    // ================= diagnostics =================
    fun copyDiagnostics(share: Boolean = false) {
        val a = api
        viewModelScope.launch(Dispatchers.IO) {
            val agentText = try { a?.diagnostics() ?: "(not connected to a laptop)" } catch (e: Exception) { "(the agent did not answer: ${msg(e)})" }
            val text = Diagnostics.build(ctx, this@RemotyVm, agentText)
            withContext(Dispatchers.Main) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Remoty diagnostics", text))
                toast = "Diagnostics copied. Paste them to Claude."
                if (share) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    fun clearCrash() { File(ctx.filesDir, "last_crash.txt").delete(); toast = "Crash report cleared" }

    // ================= activity log =================
    fun loadAudit() {
        val a = api ?: return
        auditLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try { audit = a.audit(300) } catch (e: Exception) { toast = msg(e) }
            auditLoading = false
        }
    }

    // ================= remote screen =================
    fun remoteSend(body: JSONObject) {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { a.remote(body); remoteError = null } catch (e: Exception) { remoteError = msg(e) }
        }
    }

    suspend fun fetchScreen(width: Int, quality: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext null
        val bytes = a.screenshot(width, quality)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    // ================= build on GitHub (laptop off) =================
    fun saveCloudToken(t: String) { cloudToken = t.trim(); store.cloudToken = cloudToken; toast = if (cloudToken.isBlank()) "Token removed" else "Token saved" }

    fun saveCloudProject(p: CloudProject, index: Int?) {
        cloudProjects = if (index != null && index in cloudProjects.indices) cloudProjects.toMutableList().also { it[index] = p } else cloudProjects + p
        store.saveCloudProjects(cloudProjects)
        if (index == null) cloudSelected = cloudProjects.size - 1
    }

    fun deleteCloudProject(index: Int) {
        cloudProjects = cloudProjects.filterIndexed { i, _ -> i != index }
        store.saveCloudProjects(cloudProjects)
        cloudSelected = 0
    }

    fun cancelCloud() {
        cloudJob?.cancel()
        val p = cloudProjects.getOrNull(cloudSelected)
        if (p != null && cloudRunId != 0L && cloudPhase == "building") {
            val t = cloudToken
            viewModelScope.launch(Dispatchers.IO) { GitHubApi(t).cancelRun(p.repo, cloudRunId) }
        }
        cloudPhase = "idle"; cloudStatus = "Cancelled"
    }

    fun startCloudBuild() {
        val z = zip
        val p = cloudProjects.getOrNull(cloudSelected)
        if (z == null) { toast = "Pick the zip first"; return }
        if (p == null) { toast = "Add a project first"; return }
        if (cloudToken.isBlank()) { toast = "Add your GitHub token first"; return }
        cloudJob?.cancel()
        cloudJob = viewModelScope.launch(Dispatchers.IO) {
            try { runCloud(z, p) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.i("cloud build failed: ${e.message}"); cloudPhase = "failed"; cloudStatus = msg(e) }
        }
    }

    private suspend fun runCloud(z: PickedZip, p: CloudProject) {
        val gh = GitHubApi(cloudToken)
        cloudApk = null; cloudProblems = emptyList(); cloudSteps = emptyList(); cloudRunUrl = ""; cloudLog = ""; cloudRunId = 0L
        cloudPhase = "uploading"; cloudProgress = 0f; cloudStatus = "Reading the zip..."
        AppLog.i("cloud build: ${p.repo} (${p.workflow}) with ${z.name}")
        val files = CloudZip.read(z.file)
        if (files.isEmpty()) throw IllegalStateException("The zip has no files")

        cloudStatus = "Connecting to GitHub..."
        val head = gh.branchSha(p.repo, p.branch)
        val baseTree = gh.commitTree(p.repo, head)

        val total = files.size
        val done = AtomicInteger(0)
        val shas = arrayOfNulls<String>(total)
        val pool = Executors.newFixedThreadPool(4)
        try {
            files.mapIndexed { i, f ->
                pool.submit(Runnable {
                    shas[i] = gh.createBlob(p.repo, f.bytes)
                    val d = done.incrementAndGet()
                    cloudProgress = d.toFloat() / total
                    cloudStatus = "Uploading files $d / $total"
                })
            }.forEach {
                try { it.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            }
        } finally { pool.shutdown() }

        val tree = gh.createTree(p.repo, baseTree, files.indices.map { Triple(files[it].path, if (files[it].exec) "100755" else "100644", shas[it]!!) })
        if (tree != baseTree) {
            val commit = gh.createCommit(p.repo, "Remoty: update from the phone (${z.name})", tree, head)
            gh.updateRef(p.repo, p.branch, commit)
        } else cloudStatus = "No file changes: building the version already on GitHub"

        cloudPhase = "queued"; cloudStatus = "Starting the build..."
        val since = Instant.now().minusSeconds(20)
        var started = false
        for (attempt in 0 until 8) {
            try { gh.dispatch(p.repo, p.workflow, p.branch); started = true; break }
            catch (e: GitHubException) { if (attempt == 7) throw e; delay(5000) }   // right after a commit GitHub needs a moment
        }
        if (!started) throw IllegalStateException("Could not start the workflow ${p.workflow}")

        var run: JSONObject? = null
        for (i in 0 until 40) {
            delay(3000)
            run = gh.recentRuns(p.repo, p.workflow, p.branch).firstOrNull { Instant.parse(it.getString("created_at")) >= since }
            if (run != null) break
        }
        if (run == null) throw IllegalStateException("The build was started but I could not find it. Open the Actions page of ${p.repo}.")
        val runId = run.getLong("id")
        cloudRunId = runId
        cloudRunUrl = run.optString("html_url")
        cloudPhase = "building"

        var conclusion = ""
        while (true) {
            val r = gh.run(p.repo, runId)
            val steps = ArrayList<String>()
            var current = "waiting for a runner"
            for (j in gh.jobs(p.repo, runId)) {
                val arr = j.optJSONArray("steps") ?: continue
                for (k in 0 until arr.length()) {
                    val st = arr.getJSONObject(k)
                    val mark = when (st.optString("status")) { "completed" -> if (st.optString("conclusion") == "success" || st.optString("conclusion") == "skipped") "✓" else "✗"; "in_progress" -> "…"; else -> "○" }
                    if (mark == "…") current = st.optString("name")
                    if (!st.optString("name").startsWith("Set up job") && !st.optString("name").startsWith("Post ")) steps.add("$mark ${j.optString("name")}: ${st.optString("name")}")
                }
            }
            cloudSteps = steps.takeLast(14)
            cloudStatus = "Building on GitHub (run ${r.optInt("run_number")}): $current"
            if (r.optString("status") == "completed") { conclusion = r.optString("conclusion"); break }
            delay(8000)
        }

        if (conclusion != "success") {
            cloudPhase = "failed"; cloudStatus = "The build failed ($conclusion)"
            var log = ""
            for (j in gh.jobs(p.repo, runId)) {
                if (j.optString("conclusion") == "failure") { log = try { gh.jobLog(p.repo, j.getLong("id")) } catch (e: Exception) { "" }; if (log.isNotEmpty()) break }
            }
            val lines = log.lines()
            cloudProblems = ProblemParser.parse(lines)
            cloudLog = lines.takeLast(80).joinToString("\n")
            return
        }

        cloudPhase = "downloading"; cloudStatus = "Downloading the APK..."
        val arts = gh.artifacts(p.repo, runId).filter { !it.optBoolean("expired") }
        val art = arts.firstOrNull { it.optString("name").contains("apk", ignoreCase = true) } ?: arts.firstOrNull()
            ?: throw IllegalStateException("The build succeeded but produced no download")
        val zipFile = File(ctx.cacheDir, "cloud/artifact.zip")
        gh.downloadArtifact(p.repo, art.getLong("id"), zipFile)
        val apk = CloudZip.extractApk(zipFile, File(ctx.cacheDir, "apks"))
        cloudApk = apk
        cloudPhase = "done"; cloudStatus = "APK ready: ${apk.name}"
        AppLog.i("cloud build done: ${apk.name}")
        withContext(Dispatchers.Main) {
            if (autoInstall && foreground) installCloudApk()
            else if (!foreground) Notifier.job(ctx, "cloud", "APK ready: ${p.name}", "Open Remoty, then tap Install")
        }
    }

    fun installCloudApk() {
        val f = cloudApk ?: return
        if (!Updater.install(ctx, f)) toast = "Allow installs from Remoty in the settings page that opened, then tap Install again."
    }

    fun copyCloudProblems() {
        val sb = StringBuilder("## Remoty GitHub build failed\n${cloudStatus}\n")
        if (cloudRunUrl.isNotBlank()) sb.appendLine("Run: $cloudRunUrl")
        sb.appendLine()
        if (cloudProblems.isNotEmpty()) {
            sb.appendLine("### What went wrong")
            cloudProblems.forEachIndexed { i, p -> sb.appendLine("${i + 1}. ${p.title}" + (if (p.file.isNotEmpty()) " (${p.file}:${p.line})" else "") + (if (p.detail.isNotEmpty()) "\n   ${p.detail}" else "")) }
            sb.appendLine()
        }
        sb.appendLine("### End of the log\n```\n$cloudLog\n```")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Remoty build error", sb.toString()))
        toast = "Copied. Paste it to Claude."
    }

    // ================= lock and updates =================
    fun updateAppLock(v: Boolean) { appLock = v; store.appLock = v }

    /** Looks on the laptop for a Remoty APK newer than this app (built by Rebuild-APK.bat). */
    fun checkUpdate(silent: Boolean = false) {
        val a = api ?: return
        if (!silent) updateStatus = "Asking the laptop..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val u = a.latestApk()
                val mine = BuildConfig.BUILD_NUMBER
                if (u == null) {
                    updateInfo = null
                    if (!silent) updateStatus = "No Remoty APK on the laptop yet. Run Rebuild-APK.bat there."
                } else if (u.build > mine) {
                    updateInfo = u
                    updateStatus = "A newer Remoty is ready: build ${u.build} (this phone has build $mine)."
                    if (silent) toast = "A newer Remoty is on your laptop: Laptop tab, then Install."
                } else {
                    updateInfo = null
                    if (!silent) updateStatus = "You are up to date (build $mine)."
                }
            } catch (e: Exception) { if (!silent) updateStatus = msg(e) }
        }
    }

    /** Files tab: tap an .apk on the laptop to download it and open the installer. */
    fun installApkFile(path: String, name: String) = launchApi {
        toast = "Downloading $name..."
        val f = File(ctx.cacheDir, "apks/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        it.downloadApk(path, f)
        withContext(Dispatchers.Main) {
            if (!Updater.install(ctx, f)) toast = "Allow installs from Remoty in the settings page that opened, then tap again."
        }
    }

    fun installUpdate() {
        val u = updateInfo ?: return
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateStatus = "Downloading build ${u.build}..."
                val f = File(ctx.cacheDir, "apks/${u.name}")
                a.downloadApk(u.path, f)
                withContext(Dispatchers.Main) {
                    if (!Updater.install(ctx, f)) toast = "Allow installs from Remoty in the settings page that opened, then tap Install again."
                }
                updateStatus = ""
            } catch (e: Exception) { updateStatus = msg(e) }
        }
    }
}

package com.example.remoty

import android.content.Context
import android.os.Build
import java.io.File

/** Builds the text the Diagnostics button copies: everything needed to understand a problem, with no secrets in it. */
object Diagnostics {
    fun build(ctx: Context, vm: RemotyVm, agentText: String): String {
        val zt = ZtManager.state.value
        val p = vm.active
        val sb = StringBuilder()
        sb.appendLine("## Remoty diagnostics")
        sb.appendLine("App: ${BuildConfig.VERSION_LABEL}  (installed build number ${BuildConfig.BUILD_NUMBER})")
        sb.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Theme: ${vm.themeKey}   auto-install: ${vm.autoInstall}   app lock: ${vm.appLock}")
        sb.appendLine()
        sb.appendLine("### Connection")
        sb.appendLine("Laptop: ${p?.name ?: "(none)"} ${p?.ip ?: ""}:${p?.port ?: ""}   network ${p?.networkId ?: ""}   (token not included)")
        sb.appendLine("ZeroTier: ${zt.phase}  node ${zt.nodeId}  phone address ${zt.ip}  ${zt.message}")
        sb.appendLine("Agent reachable: ${vm.online}  latency ${vm.latency} ms   stage ${vm.stage}")
        vm.info?.let { i ->
            sb.appendLine("Agent ${i.version}  tools ${i.tools}  folders ${i.roots}  remote control ${i.remoteControl}  keep awake ${i.keepAwake}")
        }
        sb.appendLine()
        sb.appendLine("### Recent jobs")
        vm.jobs.take(5).forEach { sb.appendLine("- ${it.status} (${it.phase}) ${it.title}   exit=${it.exitCode} apk=${it.artifactName ?: "-"}") }
        if (vm.cloudProjects.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### GitHub builds")
            vm.cloudProjects.forEach { sb.appendLine("- ${it.name}: ${it.repo} (${it.workflow} on ${it.branch})   token saved: ${vm.cloudToken.isNotBlank()}") }
            if (vm.cloudPhase != "idle") sb.appendLine("Last GitHub build: ${vm.cloudPhase}: ${vm.cloudStatus}")
        }
        val crash = File(ctx.filesDir, "last_crash.txt")
        if (crash.exists()) {
            sb.appendLine()
            sb.appendLine("### Last crash")
            sb.appendLine(crash.readText().take(6000))
        }
        sb.appendLine()
        sb.appendLine("### App log")
        sb.appendLine(AppLog.dump().takeLast(5000))
        sb.appendLine()
        sb.appendLine("### Laptop agent")
        sb.appendLine(agentText)
        return sb.toString()
    }
}

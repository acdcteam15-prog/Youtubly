package com.youtubly.app.download

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object DownloadManagerHelper {
    fun openFile(context: Context, filePath: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
        val mime = when (filePath.substringAfterLast('.').lowercase()) {
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

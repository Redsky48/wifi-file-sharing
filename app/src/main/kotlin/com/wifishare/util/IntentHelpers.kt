package com.wifishare.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast

object IntentHelpers {

    fun openFolder(context: Context, treeUri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(context, intent, "No app to open this folder")
    }

    fun openFile(context: Context, fileUri: Uri, mime: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mime ?: "*/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(context, Intent.createChooser(intent, "Open with"), "No app can open this file")
    }

    private fun launch(context: Context, intent: Intent, fallbackMessage: String): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, fallbackMessage, Toast.LENGTH_SHORT).show()
            false
        }
    }
}

package com.piremote.screens

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.piremote.FileDownload
import com.piremote.theme.accent
import com.piremote.theme.bgSecondary
import com.piremote.theme.borderMuted
import com.piremote.theme.textMuted
import com.piremote.theme.textSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A host-pushed name must never look like the updater's APK artifact, or a
// chooser/install flow could be tricked into handling host-controlled bytes.
private val APK_NAME = Regex("(?i)pi-remote.*\\.apk")

/**
 * A file pushed from the host (piRemote.sendFile) — offer the system share
 * sheet, which covers both "save to Files" and sending to another app. The
 * payload is written under cache/shared/ (NOT cache/updates/, where the APK
 * updater lives) so the two can't collide. Decode + write happen off the main
 * thread — a multi-MB file would otherwise ANR in the click handler.
 */
@Composable
fun FileDownloadDialog(file: FileDownload, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sizeKb = file.data.length * 3 / 4 / 1024
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (file.source.isNotBlank()) "File from ${file.source}" else "File from pi",
                color = accent, fontFamily = piMono, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "${file.name}\n${file.mimeType} · ~$sizeKb KB",
                color = textSecondary, fontFamily = piMono, fontSize = 12.sp
            )
        },
        confirmButton = {
            val onShare: () -> Unit = {
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        try {
                            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                            var safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "download.bin" }
                            if (APK_NAME.matches(safeName)) safeName = "file_$safeName"
                            val out = File(dir, safeName)
                            out.writeBytes(Base64.decode(file.data, Base64.DEFAULT))
                            FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", out) to out.name
                        } catch (_: Exception) {
                            null  // malformed base64 / IO error
                        }
                    }
                    if (uri != null) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = file.mimeType.ifBlank { "application/octet-stream" }
                            putExtra(Intent.EXTRA_STREAM, uri.first)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { ctx.startActivity(Intent.createChooser(send, "Share ${uri.second}")) } catch (_: Exception) {}
                    }
                    onDismiss()
                }
            }
            Box(modifier = Modifier
                .minimumInteractiveComponentSize()
                .border(BorderStroke(1.dp, accent), RoundedCornerShape(0.dp))
                .clickable(role = Role.Button, onClickLabel = "share or save file") { onShare() }
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("[SHARE / SAVE]", color = accent, fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Box(modifier = Modifier
                .minimumInteractiveComponentSize()
                .border(BorderStroke(1.dp, borderMuted), RoundedCornerShape(0.dp))
                .clickable(role = Role.Button, onClickLabel = "dismiss") { onDismiss() }
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("[DISMISS]", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
            }
        },
        containerColor = bgSecondary,
        tonalElevation = 4.dp
    )
}

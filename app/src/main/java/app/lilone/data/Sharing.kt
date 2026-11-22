package app.lilone.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Sharing {
    fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "Document" }

    /** Copies a library PDF into cache/share/<title>.pdf so other apps see a nice file name. */
    fun shareableCopy(context: Context, src: File, title: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, safeName(title) + ".pdf")
        src.copyTo(out, overwrite = true)
        return out
    }

    fun sharePdf(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, file.nameWithoutExtension))
    }

    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject))
    }

    fun writeTo(context: Context, src: File, dest: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(dest)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
    }.isSuccess

    /** Hands a file we can't convert to another app (e.g. Google Docs) via the system chooser. */
    fun openElsewhere(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(view, "Open with")) }
    }
}

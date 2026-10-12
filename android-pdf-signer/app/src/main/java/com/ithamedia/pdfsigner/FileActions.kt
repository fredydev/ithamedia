package com.ithamedia.pdfsigner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Ouvrir, partager, lancer un outil sur un fichier. */
object FileActions {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun open(context: Context, file: File) {
        if (file.kind == FileKind.PDF) {
            context.startActivity(Intent(context, ViewerActivity::class.java).putPaths(listOf(file)))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files[0].mimeType)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .setType("*/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun launchTool(context: Context, tool: Tool, files: List<File> = emptyList()) {
        context.startActivity(tool.intent(context).putPaths(files))
    }
}

const val EXTRA_PATHS = "paths"

fun Intent.putPaths(files: List<File>): Intent =
    putStringArrayListExtra(EXTRA_PATHS, ArrayList(files.map { it.absolutePath }))

fun Intent.paths(): List<File> =
    getStringArrayListExtra(EXTRA_PATHS).orEmpty().map(::File).filter { it.exists() }

/**
 * « Enregistrer sur l'appareil » : copie un fichier de la bibliothèque vers un emplacement
 * choisi par l'utilisateur (Téléchargements, Drive…). À créer à l'initialisation de l'activité.
 */
class Exporter(private val activity: ComponentActivity) {
    private var pending: File? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) return@registerForActivityResult
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out)
                        FileInputStream(file).use { it.copyTo(out) }
                    }
                }.isSuccess
            }
            Toast.makeText(activity, if (ok) R.string.exported else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun export(file: File) {
        pending = file
        launcher.launch(file.name)
    }
}

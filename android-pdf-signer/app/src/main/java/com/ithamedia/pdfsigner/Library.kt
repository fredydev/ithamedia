package com.ithamedia.pdfsigner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Mes fichiers » : bibliothèque de documents de l'app, rangée dans le stockage interne
 * (filesDir/Documents). Tous les outils y enregistrent leurs résultats.
 */
object Library {

    fun root(context: Context): File = File(context.filesDir, "Documents").apply { mkdirs() }

    fun isInLibrary(context: Context, file: File): Boolean =
        file.canonicalPath.startsWith(root(context).canonicalPath)

    /** Dossiers d'abord, puis fichiers du plus récent au plus ancien. */
    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })

    fun recent(context: Context, limit: Int): List<File> =
        root(context).walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()

    /** Tous les dossiers (racine comprise), pour « Déplacer vers… ». */
    fun allFolders(context: Context): List<File> =
        root(context).walkTopDown().filter { it.isDirectory }.toList()

    /**
     * Fichier de sortie libre nommé [name]. S'il est rangé dans la bibliothèque, il est créé
     * dans le même dossier que [near], sinon à la racine.
     */
    fun newOutput(context: Context, name: String, near: File? = null): File {
        val dir = near?.parentFile?.takeIf { isInLibrary(context, it) } ?: root(context)
        return uniqueFile(dir, sanitize(name))
    }

    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return candidate
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifEmpty { "document" }

    /** Copie un document externe (sélecteur Android) dans [dir], sous son nom d'origine. */
    fun importUri(context: Context, uri: Uri, dir: File): File {
        val name = displayName(context, uri) ?: "document"
        val target = uniqueFile(dir, sanitize(name))
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "flux illisible" }
            FileOutputStream(target).use { input.copyTo(it) }
        }
        return target
    }

    /** Copie temporaire (cache) d'un document externe, utilisée comme entrée d'un outil. */
    fun cacheCopy(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "inputs").apply { mkdirs() }
        return importUri(context, uri, dir)
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
}

enum class FileKind { FOLDER, PDF, WORD, IMAGE, OTHER }

val File.kind: FileKind
    get() = when {
        isDirectory -> FileKind.FOLDER
        else -> when (extension.lowercase(Locale.ROOT)) {
            "pdf" -> FileKind.PDF
            "docx", "doc" -> FileKind.WORD
            "jpg", "jpeg", "png", "webp", "heic" -> FileKind.IMAGE
            else -> FileKind.OTHER
        }
    }

val File.mimeType: String
    get() = when (kind) {
        FileKind.PDF -> "application/pdf"
        FileKind.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        FileKind.IMAGE -> "image/*"
        else -> "application/octet-stream"
    }

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes o"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#,##0").format(kb)} Ko"
    val mb = kb / 1024.0
    return "${DecimalFormat("#,##0.0").format(mb)} Mo"
}

fun formatDate(time: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE).format(Date(time))

fun File.baseName(): String = name.substringBeforeLast('.')

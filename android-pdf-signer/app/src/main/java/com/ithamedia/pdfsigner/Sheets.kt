package com.ithamedia.pdfsigner

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/** Feuille affichée quand un outil a produit un fichier. */
object ResultSheet {

    fun show(activity: AppCompatActivity, file: File, summary: String?, exporter: Exporter, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_result, null)
        view.findViewById<TextView>(R.id.resultName).text = file.name
        view.findViewById<TextView>(R.id.resultSummary).text =
            summary ?: activity.getString(R.string.result_saved_in_library, formatSize(file.length()))
        view.findViewById<View>(R.id.resultOpen).setOnClickListener { FileActions.open(activity, file) }
        view.findViewById<View>(R.id.resultShare).setOnClickListener { FileActions.share(activity, listOf(file)) }
        view.findViewById<View>(R.id.resultExport).setOnClickListener { exporter.export(file) }
        view.findViewById<View>(R.id.resultMore).setOnClickListener {
            FileActionsSheet.show(activity, file, exporter) {}
        }
        view.findViewById<View>(R.id.resultDone).setOnClickListener {
            dialog.dismiss()
            onDone()
        }
        dialog.setContentView(view)
        dialog.show()
    }
}

/** Toutes les actions possibles sur un fichier ou dossier de la bibliothèque. */
object FileActionsSheet {

    fun show(activity: AppCompatActivity, file: File, exporter: Exporter, onChanged: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_file_actions, null)
        FileRow.bindIcon(view.findViewById(R.id.sheetIcon), file)
        view.findViewById<TextView>(R.id.sheetTitle).text = file.name
        view.findViewById<TextView>(R.id.sheetMeta).text = FileRow.meta(activity, file)
        val container = view.findViewById<LinearLayout>(R.id.sheetActions)

        fun add(@DrawableRes icon: Int, @StringRes label: Int, destructive: Boolean = false, action: () -> Unit) {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_action, container, false)
            val iconView = row.findViewById<ImageView>(R.id.actionIcon)
            val labelView = row.findViewById<TextView>(R.id.actionLabel)
            iconView.setImageResource(icon)
            labelView.setText(label)
            if (destructive) {
                val red = ContextCompat.getColor(activity, R.color.md_error)
                iconView.setColorFilter(red)
                labelView.setTextColor(red)
            }
            row.setOnClickListener {
                dialog.dismiss()
                action()
            }
            container.addView(row)
        }

        val files = listOf(file)
        when (file.kind) {
            FileKind.PDF -> {
                add(R.drawable.ic_eye, R.string.action_open) { FileActions.open(activity, file) }
                add(R.drawable.ic_signature, R.string.action_sign) { FileActions.launchTool(activity, Tool.SIGN, files) }
                add(R.drawable.ic_reorder, R.string.action_reorder) { FileActions.launchTool(activity, Tool.REORDER, files) }
                add(R.drawable.ic_compress, R.string.action_compress) { FileActions.launchTool(activity, Tool.COMPRESS, files) }
                add(R.drawable.ic_merge, R.string.action_merge) { FileActions.launchTool(activity, Tool.MERGE, files) }
            }
            FileKind.WORD -> {
                add(R.drawable.ic_pdf, R.string.action_convert_pdf) { FileActions.launchTool(activity, Tool.WORD, files) }
                add(R.drawable.ic_eye, R.string.action_open) { FileActions.open(activity, file) }
            }
            FileKind.IMAGE -> {
                add(R.drawable.ic_pdf, R.string.action_image_to_pdf) { FileActions.launchTool(activity, Tool.IMAGES, files) }
                add(R.drawable.ic_eye, R.string.action_open) { FileActions.open(activity, file) }
            }
            FileKind.OTHER -> add(R.drawable.ic_eye, R.string.action_open) { FileActions.open(activity, file) }
            FileKind.FOLDER -> Unit
        }
        if (file.isFile) {
            add(R.drawable.ic_share, R.string.share) { FileActions.share(activity, files) }
            add(R.drawable.ic_download, R.string.action_export) { exporter.export(file) }
        }
        add(R.drawable.ic_rename, R.string.action_rename) { rename(activity, file, onChanged) }
        if (file.isFile) add(R.drawable.ic_folder_open, R.string.action_move) { move(activity, file, onChanged) }
        add(R.drawable.ic_delete, R.string.action_delete, destructive = true) { delete(activity, file, onChanged) }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun rename(activity: AppCompatActivity, file: File, onChanged: () -> Unit) {
        val ext = if (file.isFile) file.extension else ""
        promptText(activity, R.string.action_rename, R.string.name_hint, if (file.isFile) file.baseName() else file.name) { input ->
            val newName = Library.sanitize(if (ext.isEmpty()) input else "$input.$ext")
            val target = File(file.parentFile, newName)
            when {
                target == file -> Unit
                target.exists() -> Toast.makeText(activity, R.string.name_taken, Toast.LENGTH_SHORT).show()
                file.renameTo(target) -> onChanged()
                else -> Toast.makeText(activity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun move(activity: AppCompatActivity, file: File, onChanged: () -> Unit) {
        val root = Library.root(activity)
        val folders = Library.allFolders(activity).filter { it != file.parentFile }
        if (folders.isEmpty()) {
            Toast.makeText(activity, R.string.no_other_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = folders.map { folderLabel(activity, root, it) }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.move_to)
            .setItems(labels) { _, which ->
                val target = Library.uniqueFile(folders[which], file.name)
                if (file.renameTo(target)) onChanged()
                else Toast.makeText(activity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun delete(activity: AppCompatActivity, file: File, onChanged: () -> Unit) {
        val message = if (file.isDirectory) R.string.delete_folder_confirm else R.string.delete_file_confirm
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.delete_title, file.name))
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (file.deleteRecursively()) onChanged()
                else Toast.makeText(activity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun folderLabel(activity: AppCompatActivity, root: File, folder: File): String {
        val rel = folder.relativeTo(root).path
        val home = activity.getString(R.string.tab_files)
        return if (rel.isEmpty()) home else "$home / ${rel.replace("/", " / ")}"
    }
}

/** Petite boîte de saisie de texte (nom de fichier, de dossier…). */
fun promptText(
    activity: AppCompatActivity,
    @StringRes title: Int,
    @StringRes hint: Int,
    initial: String,
    onOk: (String) -> Unit,
) {
    val view = LayoutInflater.from(activity).inflate(R.layout.dialog_input, null)
    view.findViewById<TextInputLayout>(R.id.inputLayout).setHint(hint)
    val input = view.findViewById<TextInputEditText>(R.id.input)
    input.setText(initial)
    input.selectAll()
    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(view)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onOk(text)
        }
        .create()
    dialog.setOnShowListener { input.requestFocus() }
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}

/** Présentation d'un fichier dans les listes (icône colorée + métadonnées). */
object FileRow {

    fun bindIcon(view: ImageView, file: File) {
        val (icon, color) = when (file.kind) {
            FileKind.FOLDER -> R.drawable.ic_folder to R.color.file_folder
            FileKind.PDF -> R.drawable.ic_pdf to R.color.file_pdf
            FileKind.WORD -> R.drawable.ic_word to R.color.file_word
            FileKind.IMAGE -> R.drawable.ic_image to R.color.file_image
            FileKind.OTHER -> R.drawable.ic_word to R.color.md_outline
        }
        tintedIcon(view, icon, ContextCompat.getColor(view.context, color))
    }

    fun meta(activity: android.content.Context, file: File): String =
        if (file.isDirectory) {
            val count = file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
            activity.resources.getQuantityString(R.plurals.items_count, count, count)
        } else {
            "${formatSize(file.length())} · ${formatDate(file.lastModified())}"
        }
}

/** Icône teintée sur une pastille de la même couleur, très atténuée. */
fun tintedIcon(view: ImageView, @DrawableRes icon: Int, color: Int) {
    view.setImageResource(icon)
    view.setColorFilter(color)
    view.setBackgroundResource(R.drawable.bg_rounded)
    view.background.mutate().setTint((color and 0x00FFFFFF) or 0x24000000)
}

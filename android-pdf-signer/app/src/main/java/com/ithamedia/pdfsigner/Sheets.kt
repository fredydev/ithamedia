package com.ithamedia.pdfsigner

import android.content.Context
import android.content.Intent
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

/** Construction des listes d'actions des feuilles du bas. */
private class ActionList(private val activity: AppCompatActivity, private val container: LinearLayout, private val dialog: BottomSheetDialog) {
    private val inflater = LayoutInflater.from(activity)

    fun header(title: String) {
        val v = inflater.inflate(R.layout.item_section_header, container, false)
        v.findViewById<TextView>(R.id.sectionTitle).text = title
        container.addView(v)
    }

    fun add(@DrawableRes icon: Int, label: String, sub: String? = null, destructive: Boolean = false, action: () -> Unit) {
        val row = inflater.inflate(R.layout.item_action, container, false)
        val iconView = row.findViewById<ImageView>(R.id.actionIcon)
        val labelView = row.findViewById<TextView>(R.id.actionLabel)
        val subView = row.findViewById<TextView>(R.id.actionSub)
        iconView.setImageResource(icon)
        labelView.text = label
        if (sub != null) {
            subView.text = sub
            subView.visibility = View.VISIBLE
        }
        if (destructive) {
            val red = ContextCompat.getColor(activity, R.color.danger)
            iconView.setColorFilter(red)
            labelView.setTextColor(red)
        }
        row.setOnClickListener {
            dialog.dismiss()
            action()
        }
        container.addView(row)
    }

    fun add(@DrawableRes icon: Int, @StringRes label: Int, destructive: Boolean = false, action: () -> Unit) =
        add(icon, activity.getString(label), null, destructive, action)
}

/** Feuille affichée quand un outil a produit un ou plusieurs fichiers. */
object ResultSheet {

    fun show(activity: AppCompatActivity, file: File, summary: String?, exporter: Exporter, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_result, null)
        view.findViewById<TextView>(R.id.resultName).text = file.name
        view.findViewById<TextView>(R.id.resultSummary).text =
            summary ?: activity.getString(R.string.result_saved_in_library, formatSize(file.length()))
        FileRow.bindIcon(view.findViewById(R.id.resultThumb), file)
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

    fun showMany(activity: AppCompatActivity, folder: File, files: List<File>, summary: String?, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_result, null)
        view.findViewById<TextView>(R.id.resultName).text = folder.name
        view.findViewById<TextView>(R.id.resultSummary).text =
            (summary ?: activity.resources.getQuantityString(R.plurals.files_count, files.size, files.size)) + " · " +
                activity.getString(R.string.folder_in_library)
        FileRow.bindIcon(view.findViewById(R.id.resultThumb), folder)
        view.findViewById<TextView>(R.id.resultOpen).setText(R.string.open_folder)
        view.findViewById<View>(R.id.resultOpen).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(
                Intent(activity, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_FOLDER, folder.absolutePath)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            activity.finish()
        }
        view.findViewById<View>(R.id.resultShare).setOnClickListener { FileActions.share(activity, files) }
        view.findViewById<View>(R.id.resultExport).visibility = View.GONE
        view.findViewById<View>(R.id.resultMore).visibility = View.GONE
        view.findViewById<View>(R.id.resultDone).setOnClickListener {
            dialog.dismiss()
            onDone()
        }
        dialog.setContentView(view)
        dialog.show()
    }
}

/** Tous les outils applicables à un PDF, groupés par rubrique. */
object ToolsSheet {
    fun show(activity: AppCompatActivity, file: File) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_file_actions, null)
        FileRow.bindIcon(view.findViewById(R.id.sheetIcon), file)
        view.findViewById<TextView>(R.id.sheetTitle).text = file.name
        view.findViewById<TextView>(R.id.sheetMeta).setText(R.string.all_tools)
        view.findViewById<View>(R.id.quickRow).visibility = View.GONE
        val list = ActionList(activity, view.findViewById(R.id.sheetActions), dialog)
        addTools(list, activity, file)
        dialog.setContentView(view)
        dialog.show()
    }

    fun addTools(list: Any, activity: AppCompatActivity, file: File) {
        val actions = list as ActionList
        Category.entries.forEach { category ->
            val tools = Tool.entries.filter { it.category == category && it.onPdf }
            if (tools.isEmpty()) return@forEach
            actions.header("${category.number} — ${category.title}")
            tools.forEach { tool ->
                actions.add(tool.icon, tool.title, tool.subtitle) { FileActions.launchTool(activity, tool, listOf(file)) }
            }
        }
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
        val list = ActionList(activity, view.findViewById(R.id.sheetActions), dialog)
        val files = listOf(file)

        val quick = view.findViewById<View>(R.id.quickRow)
        if (file.isFile) {
            view.findViewById<View>(R.id.quickOpen).setOnClickListener {
                dialog.dismiss()
                FileActions.open(activity, file)
            }
            view.findViewById<View>(R.id.quickShare).setOnClickListener {
                dialog.dismiss()
                FileActions.share(activity, files)
            }
            view.findViewById<View>(R.id.quickExport).setOnClickListener {
                dialog.dismiss()
                exporter.export(file)
            }
        } else {
            quick.visibility = View.GONE
        }

        when (file.kind) {
            FileKind.PDF -> ToolsSheet.addTools(list, activity, file)
            FileKind.WORD -> {
                list.header(activity.getString(R.string.convert))
                list.add(R.drawable.ic_pdf, R.string.action_convert_pdf) { FileActions.launchTool(activity, Tool.WORD, files) }
            }
            FileKind.IMAGE -> {
                list.header(activity.getString(R.string.convert))
                list.add(R.drawable.ic_pdf, R.string.action_image_to_pdf) { FileActions.launchTool(activity, Tool.IMAGES, files) }
            }
            else -> Unit
        }
        list.header(activity.getString(if (file.isDirectory) R.string.folder else R.string.file))
        list.add(R.drawable.ic_rename, R.string.action_rename) { rename(activity, file, onChanged) }
        if (file.isFile) list.add(R.drawable.ic_folder_open, R.string.action_move) { move(activity, file, onChanged) }
        list.add(R.drawable.ic_delete, R.string.action_delete, destructive = true) { delete(activity, file, onChanged) }

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

    fun folderLabel(context: Context, root: File, folder: File): String {
        val rel = folder.relativeTo(root).path
        val home = context.getString(R.string.tab_files)
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

/** Présentation d'un fichier : vraie miniature pour les PDF et images, pictogramme sinon. */
object FileRow {

    fun bindIcon(view: ImageView, file: File) {
        val ctx = view.context
        val dp = ctx.resources.displayMetrics.density
        view.clearColorFilter()
        if (file.isFile && (file.kind == FileKind.PDF || file.kind == FileKind.IMAGE)) {
            view.setBackgroundResource(R.drawable.bg_thumb)
            view.setPadding(1, 1, 1, 1)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            Thumbnails.bind(view, file, (64 * dp).toInt())
        } else {
            view.tag = null
            view.setBackgroundResource(R.drawable.bg_icon_tile)
            val pad = (12 * dp).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setImageResource(
                when (file.kind) {
                    FileKind.FOLDER -> R.drawable.ic_folder
                    FileKind.WORD -> R.drawable.ic_word
                    else -> R.drawable.ic_pdf
                },
            )
            view.setColorFilter(ContextCompat.getColor(ctx, if (file.kind == FileKind.FOLDER) R.color.accent else R.color.ink_soft))
        }
    }

    fun meta(context: Context, file: File): String =
        if (file.isDirectory) {
            val count = file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
            context.resources.getQuantityString(R.plurals.items_count, count, count)
        } else {
            "${formatSize(file.length())} · ${formatDate(file.lastModified())}"
        }
}

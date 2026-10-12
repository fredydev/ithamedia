package com.ithamedia.pdfsigner

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Scanner intelligent et Images → PDF : les pages viennent du scanner (bords détectés,
 * perspective corrigée, filtres) ou de la galerie ; on les ordonne puis on crée le PDF.
 */
class ScanActivity : BaseToolActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SCAN = "scan"
        const val MODE_GALLERY = "gallery"
    }

    private lateinit var grid: RecyclerView
    private lateinit var emptyView: View
    private lateinit var createButton: Button
    private lateinit var formatGroup: MaterialButtonToggleGroup
    private val thumbs by lazy { ImageThumbLoader(480) }
    private val pages = mutableListOf<GridPage>()
    private lateinit var adapter: PageGridAdapter
    private val workDir by lazy { File(cacheDir, "scan").apply { mkdirs() } }
    private val isScanMode get() = intent.getStringExtra(EXTRA_MODE) != MODE_GALLERY
    private var pendingPhoto: File? = null

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }.orEmpty()
        } else {
            emptyList()
        }
        addImages(uris)
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        addImages(uris)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val photo = pendingPhoto
        pendingPhoto = null
        if (ok && photo != null && photo.length() > 0) {
            pages += GridPage(photo)
            refresh()
        } else {
            finishIfEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(R.layout.activity_scan, if (isScanMode) R.string.tool_scan else R.string.tool_images)

        grid = findViewById(R.id.pageGrid)
        emptyView = findViewById(R.id.scanEmpty)
        createButton = findViewById(R.id.createButton)
        formatGroup = findViewById(R.id.formatGroup)
        grid.layoutManager = GridLayoutManager(this, gridColumns())
        adapter = PageGridAdapter(
            pages,
            loadThumb = { page, done -> thumbs.load(page.key as File, done) },
            onDelete = ::deletePage,
        )
        adapter.attachTo(grid)

        findViewById<Button>(R.id.scanButton).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.galleryButton).setOnClickListener { pickFromGallery() }
        createButton.setOnClickListener { askNameAndCreate() }

        // Images transmises depuis « Mes fichiers »
        intent.paths().filter { it.kind == FileKind.IMAGE }.forEach { pages += GridPage(it) }
        refresh()
        if (savedInstanceState == null && pages.isEmpty()) {
            if (isScanMode) startScanner() else pickFromGallery()
        }
    }

    override fun onDestroy() {
        thumbs.shutdown()
        if (isFinishing) workDir.listFiles()?.forEach { it.delete() }
        super.onDestroy()
    }

    private fun pickFromGallery() {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { showScannerUnavailable() }
    }

    /** Sans Google Play Services : photo simple ou galerie. */
    private fun showScannerUnavailable() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_unavailable_title)
            .setMessage(R.string.scanner_unavailable_body)
            .setPositiveButton(R.string.take_photo) { _, _ -> takePhoto() }
            .setNeutralButton(R.string.gallery) { _, _ -> pickFromGallery() }
            .setNegativeButton(R.string.cancel) { _, _ -> finishIfEmpty() }
            .setOnCancelListener { finishIfEmpty() }
            .show()
    }

    private fun takePhoto() {
        val photo = File(workDir, "${UUID.randomUUID()}.jpg")
        pendingPhoto = photo
        cameraLauncher.launch(FileActions.uriFor(this, photo))
    }

    private fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finishIfEmpty()
            return
        }
        runTask(getString(R.string.loading), {
            uris.mapNotNull { uri ->
                runCatching {
                    val target = File(workDir, "${UUID.randomUUID()}.jpg")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        FileOutputStream(target).use { input.copyTo(it) }
                    }
                    target
                }.getOrNull()
            }
        }) { files ->
            files.forEach { pages += GridPage(it) }
            refresh()
        }
    }

    private fun finishIfEmpty() {
        if (pages.isEmpty()) finish()
    }

    private fun deletePage(position: Int) {
        val removed = pages.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, pages.size - position)
        refresh(notify = false)
        Snackbar.make(grid, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                val at = position.coerceAtMost(pages.size)
                pages.add(at, removed)
                adapter.notifyItemInserted(at)
                adapter.notifyItemRangeChanged(at, pages.size - at)
                refresh(notify = false)
            }
            .show()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun refresh(notify: Boolean = true) {
        if (notify) adapter.notifyDataSetChanged()
        emptyView.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        createButton.isEnabled = pages.isNotEmpty()
        toolbar.subtitle = if (pages.isEmpty()) null else resources.getQuantityString(R.plurals.pages_count, pages.size, pages.size)
    }

    private fun askNameAndCreate() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH'h'mm", Locale.FRANCE).format(Date())
        val prefix = getString(if (isScanMode) R.string.scan_default_name else R.string.images_default_name)
        promptText(this, R.string.pdf_name_title, R.string.name_hint, "$prefix $stamp") { name ->
            create(name)
        }
    }

    private fun create(name: String) {
        val format = if (formatGroup.checkedButtonId == R.id.formatFit) PdfTools.PageFormat.FIT_IMAGE else PdfTools.PageFormat.A4
        val specs = pages.map { PdfTools.ImagePage(it.key as File, it.rotation) }
        runTask(getString(R.string.creating_pdf), {
            val output = Library.newOutput(this, "$name.pdf")
            try {
                PdfTools.imagesToPdf(specs, output, format)
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
            output
        }) { output -> showResult(output) }
    }

    private fun gridColumns(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 120).toInt().coerceIn(2, 6)
    }
}

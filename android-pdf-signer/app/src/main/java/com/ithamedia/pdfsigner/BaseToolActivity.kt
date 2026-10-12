package com.ithamedia.pdfsigner

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Socle commun des écrans d'outils : barre d'outils, indicateur de travail, écran de résultat. */
abstract class BaseToolActivity : AppCompatActivity() {

    protected val exporter = Exporter(this)
    protected lateinit var toolbar: MaterialToolbar
    private var busyOverlay: View? = null
    private var busyText: TextView? = null

    protected val isBusy: Boolean get() = busyOverlay?.visibility == View.VISIBLE

    private var hasInput = false

    /** Sélecteur de PDF utilisé quand l'outil est lancé sans fichier. */
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (!hasInput) finish()
            return@registerForActivityResult
        }
        runTask(getString(R.string.loading), { Library.cacheCopy(this, uri) }) { file ->
            hasInput = true
            onInputPdf(file)
        }
    }

    /**
     * Démarre un outil qui travaille sur un PDF : celui transmis par « Mes fichiers »,
     * sinon le sélecteur Android s'ouvre (et l'écran se ferme si l'utilisateur annule).
     */
    protected fun startWithPdfInput(savedInstanceState: Bundle?) {
        val file = intent.paths().firstOrNull()
        when {
            file != null -> {
                hasInput = true
                onInputPdf(file)
            }
            savedInstanceState == null -> pickPdf.launch(arrayOf("application/pdf"))
            else -> finish()
        }
    }

    protected fun pickAnotherPdf() = pickPdf.launch(arrayOf("application/pdf"))

    /** Appelé avec le PDF à traiter (voir [startWithPdfInput]). */
    protected open fun onInputPdf(file: File) {}

    protected fun setupScreen(@LayoutRes layout: Int, @StringRes title: Int) {
        setContentView(layout)
        PDFBoxResourceLoader.init(applicationContext)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitle(title)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        busyOverlay = findViewById(R.id.busyOverlay)
        busyText = findViewById(R.id.busyText)
    }

    protected fun setBusy(message: String?) {
        busyOverlay?.visibility = if (message != null) View.VISIBLE else View.GONE
        busyText?.text = message
    }

    /** Exécute [work] en arrière-plan avec un indicateur, puis [onSuccess] sur le fil principal. */
    protected fun <T> runTask(message: String, work: suspend () -> T, onSuccess: (T) -> Unit) {
        setBusy(message)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                setBusy(null)
                onSuccess(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setBusy(null)
                showError(e)
            }
        }
    }

    protected fun showError(e: Throwable) {
        val message = when (e) {
            is OutOfMemoryError -> getString(R.string.error_memory)
            is UserMessageException -> e.message
            else -> e.localizedMessage ?: e.javaClass.simpleName
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Affiche le fichier produit avec les actions Ouvrir / Partager / Enregistrer. */
    protected fun showResult(file: File, summary: String? = null) {
        ResultSheet.show(this, file, summary, exporter) { finish() }
    }

    protected fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    protected fun toast(@StringRes message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Erreur dont le message est destiné tel quel à l'utilisateur. */
class UserMessageException(message: String) : Exception(message)

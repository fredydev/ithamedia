package com.ithamedia.pdfsigner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Accueil : onglet « Outils » et onglet « Mes fichiers ». */
class HomeActivity : AppCompatActivity() {

    val exporter = Exporter(this)
    private lateinit var bottomNav: BottomNavigationView

    private val toolsFragment get() = supportFragmentManager.findFragmentByTag(Tags.TOOLS) as ToolsFragment
    private val filesFragment get() = supportFragmentManager.findFragmentByTag(Tags.FILES) as FilesFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bottomNav = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            val files = FilesFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.homeContainer, ToolsFragment(), Tags.TOOLS)
                .add(R.id.homeContainer, files, Tags.FILES)
                .hide(files)
                .commitNow()
        }
        bottomNav.setOnItemSelectedListener { item ->
            val (show, hide) = when (item.itemId) {
                R.id.nav_files -> filesFragment to toolsFragment
                else -> toolsFragment to filesFragment
            }
            switchTo(show, hide)
            true
        }
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun switchTo(show: Fragment, hide: Fragment) {
        supportFragmentManager.beginTransaction().hide(hide).show(show).commitNow()
        (show as? Refreshable)?.refresh()
    }

    fun showFiles() {
        bottomNav.selectedItemId = R.id.nav_files
    }

    /** PDF reçu via « Ouvrir avec… » ou « Partager » : importé puis proposé avec ses actions. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_FOLDER)?.let { path ->
            showFiles()
            filesFragment.openFolder(java.io.File(path))
            return
        }
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        } ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Library.importUri(this@HomeActivity, uri, Library.root(this@HomeActivity)) }.getOrNull()
            }
            if (file == null) {
                Toast.makeText(this@HomeActivity, R.string.import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showFiles()
            filesFragment.openRoot()
            FileActionsSheet.show(this@HomeActivity, file, exporter) { filesFragment.refresh() }
        }
    }

    private object Tags {
        const val TOOLS = "tools"
        const val FILES = "files"
    }
}

interface Refreshable {
    fun refresh()
}

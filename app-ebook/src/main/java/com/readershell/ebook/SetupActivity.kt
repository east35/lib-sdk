package com.readershell.ebook

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SetupActivity : AppCompatActivity() {

    private var pickedPath: String? = null
    private lateinit var localRootPath: TextView
    private lateinit var grantFileAccess: Button
    private lateinit var status: TextView

    /**
     * SAF folder picker. Returns a tree URI; we convert it to a filesystem path
     * so LocalIndex (java.io.File-based) can read it directly.
     */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Persist the permission grant so we can re-read later if we ever
        // switch to true SAF reads.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist URI permission: ${e.message}")
        }
        val path = treeUriToFsPath(uri)
        if (path == null) {
            localRootPath.text = "Couldn't resolve filesystem path for that folder"
            return@registerForActivityResult
        }
        pickedPath = path
        localRootPath.text = path
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val app = application as EbookApp
        val cloudUrl = findViewById<EditText>(R.id.cloud_url)
        val password = findViewById<EditText>(R.id.password)
        localRootPath = findViewById(R.id.local_root_path)
        status = findViewById(R.id.status)
        grantFileAccess = findViewById(R.id.grant_file_access)
        grantFileAccess.setOnClickListener { openAllFilesAccessSettings() }

        cloudUrl.setText(app.prefs.getString(EbookApp.KEY_CLOUD_URL, "") ?: "")
        password.setText(app.auth.password ?: "")
        app.prefs.getString(EbookApp.KEY_LOCAL_ROOT, null)?.let {
            pickedPath = it
            localRootPath.text = it
        }

        findViewById<Button>(R.id.pick_folder).setOnClickListener {
            pickFolder.launch(null)
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = cloudUrl.text.toString().trim().trimEnd('/')
            val pwd = password.text.toString()
            val root = pickedPath

            if (url.isEmpty() || !url.startsWith("http")) {
                status.text = "Cloud URL must start with http(s)://"; return@setOnClickListener
            }
            if (pwd.isEmpty()) {
                status.text = "Password required"; return@setOnClickListener
            }
            if (root.isNullOrEmpty()) {
                status.text = "Pick a local folder"; return@setOnClickListener
            }
            if (!File(root).isDirectory) {
                status.text = "Folder not readable as a filesystem path: $root"
                return@setOnClickListener
            }
            if (!hasAllFilesAccess()) {
                status.text = "Grant file access first (button above)."
                return@setOnClickListener
            }

            app.prefs.edit()
                .putString(EbookApp.KEY_CLOUD_URL, url)
                .putString(EbookApp.KEY_LOCAL_ROOT, root)
                .apply()
            app.auth.password = pwd
            app.reload()
            Log.i(TAG, "Setup saved. Local index size = ${app.index?.size()}")

            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFileAccessUi()
    }

    private fun refreshFileAccessUi() {
        val granted = hasAllFilesAccess()
        grantFileAccess.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // On API < 30 there's no "all files access" concept; storage perm
            // is granted at install time via the manifest entry.
            true
        }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * Convert a SAF tree URI (content://com.android.externalstorage.documents/tree/primary%3ABooks)
     * into a real filesystem path. Works for the primary external volume that
     * the Boox library lives on. Non-primary volumes (SD/USB) return a
     * best-effort path that may not be readable.
     */
    private fun treeUriToFsPath(uri: Uri): String? {
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
        val parts = docId.split(":", limit = 2)
        val volume = parts.getOrNull(0) ?: return null
        val rel = parts.getOrNull(1).orEmpty()
        return when (volume) {
            "primary" -> "/storage/emulated/0" + if (rel.isEmpty()) "" else "/$rel"
            else -> "/storage/$volume" + if (rel.isEmpty()) "" else "/$rel"
        }
    }

    companion object { private const val TAG = "ReaderShellSetup" }
}

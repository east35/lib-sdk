package com.readershell.manga

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

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
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

        val app = application as MangaApp
        val cloudUrl = findViewById<EditText>(R.id.cloud_url)
        val password = findViewById<EditText>(R.id.password)
        localRootPath = findViewById(R.id.local_root_path)
        status = findViewById(R.id.status)
        grantFileAccess = findViewById(R.id.grant_file_access)
        grantFileAccess.setOnClickListener { openAllFilesAccessSettings() }

        cloudUrl.setText(app.prefs.getString(MangaApp.KEY_CLOUD_URL, "")?.ifEmpty { BuildConfig.DEFAULT_CLOUD_URL } ?: BuildConfig.DEFAULT_CLOUD_URL)
        password.setText(app.auth.password?.ifEmpty { BuildConfig.DEFAULT_PASSWORD } ?: BuildConfig.DEFAULT_PASSWORD)
        app.prefs.getString(MangaApp.KEY_LOCAL_ROOT, null)?.let {
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
                .putString(MangaApp.KEY_CLOUD_URL, url)
                .putString(MangaApp.KEY_LOCAL_ROOT, root)
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

    companion object { private const val TAG = "MangaShellSetup" }
}

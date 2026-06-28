package com.readershell.ebook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.readershell.core.Auth
import com.readershell.core.CloudClient
import com.readershell.core.LocalIndex
import com.readershell.core.ProgressQueue
import com.readershell.core.ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the long-lived shell components: config, auth, local index, progress
 * queue, proxy server. Started once at app launch; [reload] rebuilds the
 * stack after the setup screen saves new config.
 */
class EbookApp : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var auth: Auth
    lateinit var queue: ProgressQueue

    var config: EbookConfig? = null
        private set
    var cloud: CloudClient? = null
        private set
    var index: LocalIndex? = null
        private set
    var proxy: ProxyServer? = null
        private set
    var router: EbookRouter? = null
        private set

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ebook_shell", Context.MODE_PRIVATE)
        auth = Auth(this, namespace = "ebook")
        queue = ProgressQueue(this, namespace = "ebook")
        if (isConfigured()) reload()
        registerConnectivityFlush()
    }

    /**
     * Watch network state. Each time the device gains a validated internet
     * connection, try to push any dirty progress rows to cloud. Cheap if
     * nothing's dirty.
     */
    private fun registerConnectivityFlush() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ioScope.launch {
                    val r = router ?: return@launch
                    val n = r.flushDirty()
                    if (n > 0) Log.i(TAG, "auto-flushed $n dirty progress row(s) on connectivity")
                }
            }
        })
    }

    fun isConfigured(): Boolean {
        val url = prefs.getString(KEY_CLOUD_URL, null) ?: return false
        if (!url.startsWith("http")) return false
        if (auth.password.isNullOrEmpty()) return false
        return true
    }

    /** Build/rebuild the proxy stack from current prefs. Stops any running proxy first. */
    fun reload() {
        proxy?.stop()

        val url = prefs.getString(KEY_CLOUD_URL, null)
            ?: error("reload() called without a cloud URL set")
        val cfg = EbookConfig(url)
        val c = CloudClient(cfg, auth)
        val idx = LocalIndex(cfg).apply {
            prefs.getString(KEY_LOCAL_ROOT, null)?.takeIf { it.isNotEmpty() }?.let { setRoot(it) }
        }
        val r = EbookRouter(this, c, idx, queue, cfg.cloudBaseUrl)
        val p = ProxyServer(this, cfg, c, assets, r).also { it.start() }

        config = cfg
        cloud = c
        index = idx
        router = r
        proxy = p
    }

    companion object {
        const val KEY_CLOUD_URL = "cloud_url"
        const val KEY_LOCAL_ROOT = "local_root"
        private const val TAG = "ReaderShellApp"
    }
}

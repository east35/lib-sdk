package com.readershell.manga

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val longPressMs = 500L
    private var longPressFired = false
    private var keyDown = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        @Suppress("DEPRECATION") onBackPressed()
    }
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MangaApp
        if (!app.isConfigured()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(webView)
        hideSystemBars()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleExternalNavigation(uri)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let { Uri.parse(it) } ?: return false
                return handleExternalNavigation(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(OFFLINE_AWARE_REFRESH_JS, null)
                view?.evaluateJavascript(SHELL_SETTINGS_BUTTON_JS, null)
                pushOnlineState(isOnline())
            }
        }
        registerNetworkCallback()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // WebView routes HTML5 requestFullscreen() through here; a bare
                // WebChromeClient would drop it, so the reader's fullscreen mode
                // never engaged on Android.
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (window.decorView as FrameLayout).addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webView.visibility = View.GONE
                hideSystemBars()
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                (window.decorView as FrameLayout).removeView(view)
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                hideSystemBars()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val port = app.config!!.proxyPort
            webView.loadUrl("http://127.0.0.1:$port/")
        }
    }

    private fun handleExternalNavigation(uri: Uri): Boolean {
        if (uri.scheme == "shell" && uri.host == "settings") {
            startActivity(Intent(this, SetupActivity::class.java))
            return true
        }
        val host = uri.host ?: return false
        val local = host == "127.0.0.1" || host == "localhost"
        if (local) return false
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        return true
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun pushOnlineState(online: Boolean) {
        mainHandler.post {
            webView.evaluateJavascript(
                "window.__readerShellSetOffline && window.__readerShellSetOffline(${!online});",
                null,
            )
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { pushOnlineState(true) }
            override fun onLost(network: Network) { pushOnlineState(false) }
            override fun onUnavailable() { pushOnlineState(false) }
        }
        cm.registerNetworkCallback(req, networkCallback!!)
    }

    override fun onDestroy() {
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!keyDown) {
                    keyDown = true
                    longPressFired = false
                    mainHandler.postDelayed(longPressRunnable, longPressMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                keyDown = false
                mainHandler.removeCallbacks(longPressRunnable)
                if (!longPressFired) {
                    val dir = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) "next" else "prev"
                    webView.evaluateJavascript("window.mangaTurnPage && window.mangaTurnPage('$dir')", null)
                }
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        private const val OFFLINE_AWARE_REFRESH_JS = """
            (function() {
              if (window.__readerShellSetOffline) return;
              window.__readerShellOffline = false;
              window.__readerShellSetOffline = function(off) {
                window.__readerShellOffline = !!off;
                const btn = document.getElementById('refresh-library');
                if (!btn) return;
                btn.disabled = !!off;
                btn.title = off ? 'Refresh unavailable offline' : 'Refresh library';
                btn.style.opacity = off ? '0.4' : '';
              };
            })();
        """

        /**
         * Both backends ship a pre-styled #app-settings link with shell://
         * href that's hidden for browser users. Reveal it on library view,
         * hide while #reader is showing. Handles both styles of hiding
         * (HTML `hidden` attribute and `.hidden` CSS class).
         */
        private const val SHELL_SETTINGS_BUTTON_JS = """
            (function() {
              if (window.__readerShellSettingsInstalled) { window.__readerShellEnsureSettings && window.__readerShellEnsureSettings(); return; }
              window.__readerShellSettingsInstalled = true;
              function ensure() {
                var settings = document.getElementById('app-settings');
                if (!settings) return;
                var reader = document.getElementById('reader');
                var inReader = reader && !reader.classList.contains('hidden') && !reader.hasAttribute('hidden');
                if (inReader) {
                  settings.setAttribute('hidden', '');
                  settings.classList.add('hidden');
                } else {
                  settings.removeAttribute('hidden');
                  settings.classList.remove('hidden');
                }
              }
              window.__readerShellEnsureSettings = ensure;
              ensure();
              var mo = new MutationObserver(ensure);
              mo.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """
    }
}

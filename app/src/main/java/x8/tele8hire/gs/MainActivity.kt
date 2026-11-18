package x8.tele8hire.gs

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var errorView: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var splashTimeout = false
    private var errorShown = false
    private var pendingConversation: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCallback = null
        }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        instance = this
        splash.setKeepOnScreenCondition { !pageReady && !splashTimeout }
        ui.postDelayed({ splashTimeout = true }, 2500)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        root = FrameLayout(this)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        root.addView(web)
        buildErrorView()
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (errorShown) {
                    moveTaskToBack(true)
                    return
                }
                web.evaluateJavascript("(window.t8Back&&window.t8Back())===true") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })

        handleIntent(intent)
        web.loadUrl(START_URL)

        FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
            Prefs.setToken(this, t)
            deliverToken(t)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val cid = i?.getStringExtra("conversation_id")
        if (!cid.isNullOrEmpty()) {
            pendingConversation = cid
            if (pageReady) web.evaluateJavascript("window.t8OpenPending&&window.t8OpenPending()", null)
        }
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
        getSystemService(NotificationManager::class.java).cancelAll()
        registerNetworkWatcher()
    }

    override fun onStop() {
        inForeground = false
        unregisterNetworkWatcher()
        super.onStop()
    }

    override fun onDestroy() {
        instance = null
        ui.removeCallbacksAndMessages(null)
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /* ---------------- WebView ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString + " Tele8HireApp/" + BuildConfig.VERSION_NAME
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        web.addJavascriptInterface(Bridge(), "T8Native")

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                // فشل الصفحة الرئيسية أو مكتبة Supabase (لا إنترنت)
                if (request.isForMainFrame || request.url.toString().contains("supabase.min.js")) {
                    pageReady = true
                    showError(true)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val mime = mimeType ?: "application/octet-stream"
            when {
                url.startsWith("blob:") -> fetchBlob(url, URLUtil.guessFileName(url, contentDisposition, mime), mime)
                url.startsWith("data:") -> saveDataUrl(url, contentDisposition, mime)
                else -> enqueueDownload(url, userAgent, contentDisposition, mime)
            }
        })
    }

    /* ---------------- شاشة عدم الاتصال ---------------- */

    private fun buildErrorView() {
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            visibility = android.view.View.GONE
            isClickable = true
        }
        val title = TextView(this).apply {
            text = getString(R.string.error_title)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text))
        }
        val body = TextView(this).apply {
            text = getString(R.string.error_text)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 40)
            setTextColor(ContextCompat.getColor(context, R.color.text2))
        }
        val retry = Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener { retryLoad() }
        }
        errorView.addView(title)
        errorView.addView(body)
        errorView.addView(retry)
        root.addView(errorView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showError(show: Boolean) {
        errorShown = show
        errorView.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun retryLoad() {
        showError(false)
        web.loadUrl(START_URL)
    }

    private fun registerNetworkWatcher() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (errorShown) runOnUiThread { retryLoad() }
            }
        }
        netCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = netCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        netCallback = null
    }

    /* ---------------- ألوان شريطَي الحالة والتنقل ---------------- */

    private fun applyBars(color: String, dark: Boolean) {
        val c = parseColor(color) ?: return
        root.setBackgroundColor(c)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = !dark
        ctl.isAppearanceLightNavigationBars = !dark
    }

    private fun parseColor(s: String): Int? = try {
        Color.parseColor(s)
    } catch (_: Exception) {
        null
    }

    /* ---------------- التنزيل ---------------- */

    private fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    private fun enqueueDownload(url: String, ua: String?, cd: String?, mime: String) {
        if (needsLegacyStoragePermission()) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                if (!ua.isNullOrEmpty()) addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDirectory(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(R.string.download_started)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun fetchBlob(url: String, name: String, mime: String) {
        val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
        val safeMime = mime.replace("'", "")
        val js = "(async()=>{try{const r=await fetch('${url.replace("'", "")}');const b=await r.blob();" +
            "const f=new FileReader();f.onloadend=()=>T8Native.saveBase64('$safeName','$safeMime',String(f.result).split(',')[1]);" +
            "f.readAsDataURL(b)}catch(e){}})()"
        web.evaluateJavascript(js, null)
    }

    private fun saveDataUrl(url: String, cd: String?, mime: String) {
        try {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val data = url.substring(comma + 1)
            val bytes = if (meta.endsWith(";base64")) Base64.decode(data, Base64.DEFAULT) else Uri.decode(data).toByteArray()
            saveBytes(URLUtil.guessFileName(url, cd, mime), mime, bytes)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        if (needsLegacyStoragePermission()) {
            runOnUiThread { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException()
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
            toast(R.string.download_done)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun toast(res: Int) = runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }

    /* ---------------- الجسر بين الصفحة والتطبيق ---------------- */

    inner class Bridge {
        @JavascriptInterface
        fun getToken(): String = Prefs.token(this@MainActivity)

        @JavascriptInterface
        fun requestNotifications() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        @JavascriptInterface
        fun setBars(color: String, dark: Boolean) {
            runOnUiThread { applyBars(color, dark) }
        }

        @JavascriptInterface
        fun saveBase64(name: String, mime: String, b64: String) {
            try {
                saveBytes(name, mime, Base64.decode(b64, Base64.DEFAULT))
            } catch (_: Exception) {
                toast(R.string.download_failed)
            }
        }

        @JavascriptInterface
        fun takePendingConversation(): String {
            val c = pendingConversation ?: ""
            pendingConversation = null
            return c
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME
    }

    companion object {
        private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"

        @Volatile
        var inForeground = false

        @Volatile
        private var instance: MainActivity? = null

        /** يُستدعى عند تجديد التوكن لتحديثه في Supabase إن كانت الصفحة تعمل. */
        fun deliverToken(token: String) {
            val a = instance ?: return
            a.runOnUiThread {
                if (a.pageReady) a.web.evaluateJavascript("window.t8OnToken&&window.t8OnToken('$token')", null)
            }
        }
    }
}

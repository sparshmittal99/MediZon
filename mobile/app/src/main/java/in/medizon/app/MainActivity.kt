package `in`.medizon.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.print.PrintManager
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "in.medizon.app.PATH"
        private const val SPLASH_MS = 1100L
        val baseHost: String = (Uri.parse(BuildConfig.BASE_URL).host ?: "").lowercase()

        fun openIntent(c: Context, path: String?): Intent =
            Intent(c, MainActivity::class.java)
                .putExtra(EXTRA_PATH, path ?: "/")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun urlFor(path: String?): String {
            val p = path?.trim().orEmpty()
            // Only same-site absolute paths; anything else falls back to home.
            return if (p.startsWith("/") && !p.startsWith("//")) BuildConfig.BASE_URL + p else BuildConfig.BASE_URL + "/"
        }
    }

    /** Latest top-level URL, read by the JS bridge from a binder thread. */
    @Volatile var currentUrl: String? = null
        private set

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorBody: TextView
    private lateinit var retryButton: Button
    private lateinit var splashView: View

    private var splashDone = false
    private var pageLoading = false
    private var mainFrameError = false
    private var lastRequestedUrl: String = BuildConfig.BASE_URL + "/"

    // file chooser
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingPickerMimes: List<String> = emptyList()

    // geolocation
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    // fixAlarmSettings flow
    private var fixing = false
    private val fixAttempted = mutableSetOf<String>()

    // ---------------------------------------------------------------- activity result launchers

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && fixing && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Permanently denied: the only way left is the app's notification settings.
            launchSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            return@registerForActivityResult
        }
        if (fixing) continueFix() else notifyPageStatus()
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (fixing) continueFix() else notifyPageStatus()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else {
            toast("Camera permission is needed to take a photo")
            deliverFiles(null)
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        deliverFiles(if (ok && uri != null) arrayOf(uri) else null)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) { deliverFiles(null); return@registerForActivityResult }
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        else data.data?.let { uris += it }
        deliverFiles(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res.values.any { it }
        geoCallback?.invoke(geoOrigin, granted, false)
        geoCallback = null
        geoOrigin = null
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hand over from the system splash immediately; our overlay looks identical.
        systemSplash.setOnExitAnimationListener { it.remove() }

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorBody = findViewById(R.id.errorBody)
        retryButton = findViewById(R.id.retryButton)
        splashView = findViewById(R.id.splash)

        // Status bar dark green with light icons; nav bar stays dark green until the splash is gone.
        window.statusBarColor = ContextCompat.getColor(this, R.color.mz_header)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.mz_header)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setupSplash()
        setupWebView()
        setupBack()

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.mz_accent))
        swipe.setOnRefreshListener {
            if (errorView.visibility == View.VISIBLE) retry() else webView.reload()
        }
        webView.viewTreeObserver.addOnScrollChangedListener { swipe.isEnabled = webView.scrollY == 0 }
        retryButton.setOnClickListener { retry() }

        cleanOldCameraFiles()
        loadUrl(urlFor(intent?.getStringExtra(EXTRA_PATH)))

        if (Store.isLinked(this)) {
            Sync.syncNow(this)
            Sync.ensurePeriodic(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { loadUrl(urlFor(it)) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        notifyPageStatus()
        applyThemeColors()
        callPage("window.mzApplyTheme && window.mzApplyTheme()")
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- splash

    private fun setupSplash() {
        val mark = findViewById<View>(R.id.splashMark)
        val wordmark = findViewById<View>(R.id.splashWordmark)
        val density = resources.displayMetrics.density
        wordmark.alpha = 0f
        // Put the mark at the exact centre of the window, where the system splash drew it.
        splashView.post {
            val loc = IntArray(2)
            splashView.getLocationInWindow(loc)
            val windowCenter = window.decorView.height / 2f
            val viewCenter = loc[1] + splashView.height / 2f
            val dy = windowCenter - viewCenter
            mark.translationY = dy
            wordmark.translationY = dy + 72 * density
            wordmark.scaleX = 0.96f
            wordmark.scaleY = 0.96f
            wordmark.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(320).start()
        }
        val sinceProcessStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        val delay = if (sinceProcessStart in 0..SPLASH_MS) SPLASH_MS - sinceProcessStart else SPLASH_MS
        splashView.postDelayed({ dismissSplash() }, delay.coerceAtLeast(250))
    }

    private fun dismissSplash() {
        if (splashDone) return
        splashDone = true
        applyNavBar()
        splashView.animate().alpha(0f).setDuration(200).withEndAction {
            splashView.visibility = View.GONE
            askNotificationsOnFirstRun()
        }.start()
        updateProgress()
    }

    private fun askNotificationsOnFirstRun() {
        if (Build.VERSION.SDK_INT < 33 || Store.askedNotifications(this)) return
        Store.setAskedNotifications(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.mz_bg))
        webView.isHapticFeedbackEnabled = true
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true // needed to read FileProvider camera captures
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // The site has its own dark CSS: never let the WebView auto-darken it.
            if (Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
            userAgentString = "$userAgentString ${Api.UA_TOKEN}"
            Store.saveUserAgent(this@MainActivity, userAgentString)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(Bridge(this), "MediZonApp")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun loadUrl(url: String) {
        lastRequestedUrl = url
        webView.loadUrl(url)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleNavigation(request.url, request.isForMainFrame)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            currentUrl = url
            mainFrameError = false
            pageLoading = true
            updateProgress()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentUrl = url
            pageLoading = false
            swipe.isRefreshing = false
            updateProgress()
            if (!mainFrameError) hideError()
            retryButton.isEnabled = true
            retryButton.setText(R.string.try_again)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            currentUrl = url
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameError = true
                showError()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    /** true = we handled it (don't load in WebView). */
    private fun handleNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "http" || scheme == "https") {
            if (!mainFrame) return false // iframes (maps, embeds) load normally
            val host = uri.host?.lowercase() ?: ""
            if (host == baseHost) {
                if (scheme == "http") { loadUrl(uri.buildUpon().scheme("https").build().toString()); return true }
                return false
            }
            if (scheme == "https" && (host == "razorpay.com" || host.endsWith(".razorpay.com"))) return false
        }
        if (scheme == "about" || scheme == "javascript" || scheme == "blob" || scheme == "data") return false
        openExternal(uri)
        return true
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    startActivity(intent)
                    return
                } catch (e: ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (fallback != null) { openExternal(Uri.parse(fallback)); return }
                }
            } catch (e: Exception) { /* malformed */ }
            toast("No app found to open this link")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast("No app found to open this link")
        } catch (e: SecurityException) {
            toast("Can't open this link")
        }
    }

    private fun updateProgress() {
        progress.visibility = if (splashDone && pageLoading && !swipe.isRefreshing) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- error view

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showError() {
        if (isOnline()) {
            errorTitle.setText(R.string.unreachable_title)
            errorBody.setText(R.string.unreachable_body)
        } else {
            errorTitle.setText(R.string.offline_title)
            errorBody.setText(R.string.offline_body)
        }
        errorView.visibility = View.VISIBLE
        swipe.isRefreshing = false
        retryButton.isEnabled = true
        retryButton.setText(R.string.try_again)
    }

    private fun hideError() {
        errorView.visibility = View.GONE
    }

    private fun retry() {
        retryButton.isEnabled = false
        retryButton.text = "Connecting…"
        val url = webView.url
        if (url.isNullOrBlank() || url.startsWith("about:") || url.startsWith("data:")) loadUrl(lastRequestedUrl)
        else webView.reload()
    }

    // ---------------------------------------------------------------- back

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    hideError()
                    webView.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    // ---------------------------------------------------------------- chrome client: files, location

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val mimes = acceptMimes(params.acceptTypes)
            val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            pendingPickerMimes = mimes
            val imagesAllowed = mimes.any { it.startsWith("image/") || it == "*/*" }
            if (!imagesAllowed) {
                pickFiles(mimes, multiple)
                return true
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add a file")
                .setItems(arrayOf("Take a photo", "Choose from phone")) { _, which ->
                    if (which == 0) startCamera() else pickFiles(mimes, multiple)
                }
                .setOnCancelListener { deliverFiles(null) }
                .show()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            val host = try { Uri.parse(origin).host?.lowercase() } catch (e: Exception) { null }
            if (host != baseHost) { callback.invoke(origin, false, false); return }
            if (hasLocationPermission()) { callback.invoke(origin, true, false); return }
            geoOrigin = origin
            geoCallback = callback
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun acceptMimes(accept: Array<String>?): List<String> {
        val out = linkedSetOf<String>()
        accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { t ->
            if (t.startsWith(".")) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1))?.let { out += it }
            } else {
                out += t
            }
        }
        if (out.isEmpty()) { out += "image/*"; out += "application/pdf" }
        return out.toList()
    }

    private fun pickFiles(mimes: List<String>, multiple: Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        try {
            pickFiles.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker found")
            deliverFiles(null)
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhoto()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun takePhoto() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Couldn't open the camera")
            deliverFiles(null)
        }
    }

    private fun deliverFiles(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun cleanOldCameraFiles() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(cacheDir, "camera").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ---------------------------------------------------------------- downloads

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) { toast("This file can't be downloaded in the app"); return }
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                setTitle(name)
                setDescription("MediZon")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val canWritePublic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (canWritePublic) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name)
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) // for next time
                }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name…")
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    // ---------------------------------------------------------------- bridge helpers (main thread)

    fun printPage(title: String) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(title, webView.createPrintDocumentAdapter(title), null)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- dark mode

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** MainActivity handles uiMode itself (no recreate, so the WebView keeps its page): recolour by hand. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeColors()
        callPage("window.mzApplyTheme && window.mzApplyTheme()")
    }

    /** Called by the bridge after setTheme(). */
    fun onThemeChanged() {
        applyThemeColors()
        callPage("window.mzApplyTheme && window.mzApplyTheme()")
    }

    private fun applyThemeColors() {
        val bg = ContextCompat.getColor(this, R.color.mz_bg)
        findViewById<View>(R.id.root).setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
        errorView.setBackgroundColor(bg)
        errorTitle.setTextColor(ContextCompat.getColor(this, R.color.mz_text))
        errorBody.setTextColor(ContextCompat.getColor(this, R.color.mz_text2))
        val accent = ContextCompat.getColor(this, R.color.mz_accent)
        progress.indeterminateTintList = ColorStateList.valueOf(accent)
        swipe.setColorSchemeColors(accent)
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, if (isNight()) R.color.mz_surface else R.color.mz_white))
        window.statusBarColor = ContextCompat.getColor(this, R.color.mz_header)
        if (splashDone) applyNavBar()
    }

    private fun applyNavBar() {
        window.navigationBarColor = ContextCompat.getColor(this, R.color.mz_navbar)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !isNight()
    }

    private fun callPage(js: String) {
        val host = try { Uri.parse(currentUrl ?: "").host?.lowercase() } catch (e: Exception) { null }
        if (host == baseHost) webView.evaluateJavascript(js, null)
    }

    private fun notifyPageStatus() {
        val host = try { Uri.parse(currentUrl ?: "").host?.lowercase() } catch (e: Exception) { null }
        if (host == baseHost) webView.evaluateJavascript("window.mzAppStatusChanged && window.mzAppStatusChanged()", null)
    }

    fun startFixAlarmSettings() {
        fixing = true
        fixAttempted.clear()
        continueFix()
    }

    /** One missing setting at a time; each step's result callback comes back here. */
    private fun continueFix() {
        if (!fixing) return
        val s = Bridge.status(this)
        fun step(key: String) = !fixAttempted.contains(key).also { if (!it) fixAttempted += key }
        when {
            !s.getBoolean("notifications") && step("notifications") -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launchSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !s.getBoolean("exactAlarms") && step("exact") ->
                launchSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            Build.VERSION.SDK_INT >= 34 && !s.getBoolean("fullScreen") && step("fullScreen") ->
                launchSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            !s.getBoolean("batteryUnrestricted") && step("battery") ->
                launchSettings(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                    fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            else -> {
                fixing = false
                // Permissions may have just been granted: re-arm alarms that might have been set inexactly.
                AlarmScheduler.rescheduleAllFromStore(this)
                val after = Bridge.status(this)
                val allOk = after.getBoolean("notifications") && after.getBoolean("exactAlarms") &&
                    after.getBoolean("fullScreen") && after.getBoolean("batteryUnrestricted")
                if (allOk) toast("Alarms are all set")
                notifyPageStatus()
            }
        }
    }

    private fun launchSettings(intent: Intent, fallback: Intent? = null) {
        try {
            settingsLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) {
                try { settingsLauncher.launch(fallback); return } catch (e2: ActivityNotFoundException) { }
            }
            try {
                settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e3: ActivityNotFoundException) {
                continueFix()
            }
        }
    }
}

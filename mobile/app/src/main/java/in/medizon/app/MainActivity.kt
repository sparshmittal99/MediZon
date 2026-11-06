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
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.net.Network
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "in.medizon.app.PATH"

        /** True while MainActivity is resumed (push messages are then shown live by the page). */
        @Volatile var foreground = false
        private const val SPLASH_MS = 1100L
        private const val AUTO_RETRY_MS = 8000L
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

    // Status bar: the colour the page's header asked for (null = the default dark green)
    private var pageStatusColor: Int? = null
    private var statusAnimator: ValueAnimator? = null

    // Auto refresh: how long the app was away, and retrying the offline screen by itself
    private var pausedAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val autoRetry = object : Runnable {
        override fun run() {
            if (errorView.visibility != View.VISIBLE) return
            if (isOnline() && retryButton.isEnabled) retry()
            webView.postDelayed(this, AUTO_RETRY_MS)
        }
    }

    // file chooser
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingPickerMimes: List<String> = emptyList()

    // geolocation
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    // Razorpay checkout: popup window (net banking) + redirect flow in the main WebView
    private var razorpayFlow = false
    @Volatile private var checkoutSeenAt = 0L
    private var popupContainer: View? = null
    private var popupWebView: WebView? = null
    private var popupTitle: TextView? = null
    private var popupDecided = false

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

        retryButton.setOnClickListener { retry() }
        watchNetwork()

        cleanOldCameraFiles()
        loadUrl(urlFor(intent?.getStringExtra(EXTRA_PATH)))

        if (Store.isLinked(this)) {
            Sync.syncNow(this)
            Sync.ensurePeriodic(this)
            Push.ensureToken(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { loadUrl(urlFor(it)) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        webView.onResume()
        notifyPageStatus()
        applyThemeColors()
        callPage("window.mzApplyTheme && window.mzApplyTheme()")
        // Back from the background: the page decides whether it's stale enough to refresh itself (app.js)
        if (pausedAt != 0L) {
            val away = SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0L
            if (errorView.visibility == View.VISIBLE) retry()
            else callPage("window.mzAppResumed && window.mzAppResumed($away)")
        }
    }

    override fun onPause() {
        foreground = false
        // Away to pick a file / take a photo or fix alarm settings: the page is waiting on that, so no refresh after
        pausedAt = if (fileCallback != null || fixing) 0L else SystemClock.elapsedRealtime()
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        networkCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (e: Exception) { }
        }
        networkCallback = null
        webView.removeCallbacks(autoRetry)
        statusAnimator?.cancel()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        closePopup()
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
        setPageStatusColor(pageStatusColor)
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
            // Razorpay net banking opens the bank in a popup window (window.open) and posts back to it.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
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

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
            if (isRazorpayHost(request.url.host)) checkoutSeenAt = SystemClock.elapsedRealtime()
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val host = url?.let { Uri.parse(it).host?.lowercase() }
            if (host == baseHost) razorpayFlow = false else if (isRazorpayHost(host)) razorpayFlow = true
            // Our pages set their own header colour once loaded (app.js); anything else gets the default
            if (host != baseHost) setPageStatusColor(null)
            currentUrl = url
            mainFrameError = false
            pageLoading = true
            updateProgress()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentUrl = url
            pageLoading = false
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
            if (scheme == "https" && isRazorpayHost(host)) { razorpayFlow = true; return false }
            // Any https page reached from a Razorpay page (bank / 3-D Secure redirect) stays in the app.
            if (scheme == "https" && razorpayFlow) return false
        }
        if (scheme == "about" || scheme == "javascript" || scheme == "blob" || scheme == "data") return false
        openExternal(uri)
        return true
    }

    private fun isRazorpayHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "razorpay.com" || h.endsWith(".razorpay.com")
    }

    // ---------------------------------------------------------------- popup windows (Razorpay net banking)

    private fun checkoutActive(): Boolean =
        razorpayFlow || (checkoutSeenAt != 0L && SystemClock.elapsedRealtime() - checkoutSeenAt < 30 * 60 * 1000L)

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPopupWindow(resultMsg: android.os.Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        closePopup()
        val child = WebView(this)
        child.setBackgroundColor(ContextCompat.getColor(this, R.color.mz_bg))
        with(child.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // nested popups load in place
            userAgentString = webView.settings.userAgentString
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                popupNavigation(request.url, request.isForMainFrame)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                val uri = url?.let { Uri.parse(it) } ?: return
                if (!popupDecided && (uri.scheme == "http" || uri.scheme == "https")) {
                    if (popupNavigation(uri, true)) { view.stopLoading(); return }
                }
                popupTitle?.text = uri.host ?: ""
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
        }
        popupWebView = child
        popupDecided = checkoutActive()
        if (popupDecided) showPopup(child)
        transport.webView = child
        resultMsg.sendToTarget()
        return true
    }

    /**
     * Inside the popup every http(s) page loads in place (bank, Razorpay, 3-D Secure). For a popup
     * outside a checkout (e.g. a target=_blank link) the first URL decides: MediZon pages open in
     * the main view, other sites in the browser. Non-http schemes (upi:, intent:, tel:) go out.
     */
    private fun popupNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "http" || scheme == "https") {
            if (!popupDecided && mainFrame) {
                val host = uri.host?.lowercase()
                popupDecided = true
                if (isRazorpayHost(host)) {
                    popupWebView?.let { showPopup(it) }
                    return false
                }
                if (host == baseHost) loadUrl(uri.toString()) else openExternal(uri)
                webView.post { closePopup() }
                return true
            }
            return false
        }
        if (scheme == "about" || scheme == "javascript" || scheme == "blob" || scheme == "data") return false
        openExternal(uri)
        return true
    }

    private fun showPopup(child: WebView) {
        if (popupContainer != null) return
        val dp = resources.displayMetrics.density
        val root = findViewById<android.widget.FrameLayout>(R.id.root)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.mz_bg))
            isClickable = true
        }
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.mz_header))
            setPadding((4 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        }
        bar.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.round_ripple_white)
            contentDescription = "Close"
            setOnClickListener { closePopup() }
        }, android.widget.LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        val title = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.mz_white))
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((8 * dp).toInt(), 0, 0, 0)
            text = child.url?.let { Uri.parse(it).host } ?: "Secure payment"
        }
        bar.addView(title, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(bar, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        (child.parent as? android.view.ViewGroup)?.removeView(child)
        box.addView(child, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(box, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        popupContainer = box
        popupTitle = title
    }

    /** Closing the popup (X, Back, window.close()) destroys it; Razorpay sees the window closed. */
    private fun closePopup() {
        popupContainer?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        popupContainer = null
        popupTitle = null
        popupWebView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        popupWebView = null
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
        progress.visibility = if (splashDone && pageLoading) View.VISIBLE else View.GONE
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
        retryButton.isEnabled = true
        retryButton.setText(R.string.try_again)
        setPageStatusColor(null)
        // Keep trying by itself; the network callback also retries the moment a connection comes back
        webView.removeCallbacks(autoRetry)
        webView.postDelayed(autoRetry, AUTO_RETRY_MS)
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        webView.removeCallbacks(autoRetry)
    }

    /** A connection came back while the offline screen is up: load again without waiting for a tap. */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!isFinishing && errorView.visibility == View.VISIBLE && retryButton.isEnabled) retry()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Too many callbacks registered (rare): the timed retry still covers it
        }
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
                if (popupContainer != null) {
                    closePopup()
                } else if (webView.canGoBack()) {
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
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
            openPopupWindow(resultMsg)

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
            val pdfAllowed = mimes.any { it == "application/pdf" || it == "*/*" }
            showUploadChoice(if (pdfAllowed) "Add prescription" else "Add photo", mimes, multiple)
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

    /** "Take a photo" / "Choose from files". Any way of closing it without a choice cancels the input. */
    private fun showUploadChoice(title: String, mimes: List<String>, multiple: Boolean) {
        var chosen = false
        lateinit var dialog: AlertDialog
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        fun option(icon: Int, label: String, action: () -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = (64 * dp).toInt()
                setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
                val ripple = ColorStateList.valueOf(0x220E6B57)
                background = android.graphics.drawable.RippleDrawable(ripple, null, android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
                setOnClickListener { chosen = true; dialog.dismiss(); action() }
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.mz_accent))
            })
            row.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(ContextCompat.getColor(context, R.color.mz_text))
                setPadding((20 * dp).toInt(), 0, 0, 0)
            })
            box.addView(row)
        }
        option(R.drawable.ic_camera, "Take a photo") { startCamera() }
        option(R.drawable.ic_folder, "Choose from files") { pickFiles(mimes, multiple) }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (!chosen) deliverFiles(null) }
        dialog.show()
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
        val callback = fileCallback ?: return
        fileCallback = null
        if (uris == null) { callback.onReceiveValue(null); return }
        // Big phone photos are shrunk first (off the main thread): over 5 MB the upload is refused
        Thread {
            val ready = ImageShrink.shrinkAll(applicationContext, uris)
            runOnUiThread { callback.onReceiveValue(ready) }
        }.start()
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
        statusAnimator?.cancel()
        window.statusBarColor = pageStatusColor ?: ContextCompat.getColor(this, R.color.mz_header)
        if (splashDone) applyNavBar()
    }

    /**
     * The status bar takes the colour of the header under it (bridge setStatusBarColor; null = default green),
     * fading across like the header does when the home banner slides. Icons stay light: every header is dark.
     */
    fun setPageStatusColor(color: Int?) {
        pageStatusColor = color
        if (!splashDone) return   // the splash keeps its own green; dismissSplash() applies this
        val target = color ?: ContextCompat.getColor(this, R.color.mz_header)
        val from = window.statusBarColor
        statusAnimator?.cancel()
        if (from == target) return
        if (AppSettings.reduceMotion(this)) { window.statusBarColor = target; return }
        statusAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 450
            addUpdateListener { window.statusBarColor = it.animatedValue as Int }
            start()
        }
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

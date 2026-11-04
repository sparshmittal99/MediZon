# Minify is off. If you ever turn it on, keep the JavaScript bridge:
-keepclassmembers class in.medizon.app.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

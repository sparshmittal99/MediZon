import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Firebase Cloud Messaging config (no google-services plugin). Missing/blank values = push off.
val firebaseProps = Properties().apply {
    val f = rootProject.file("firebase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun firebase(key: String): String =
    (firebaseProps.getProperty("firebase.$key") ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "in.medizon.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "in.medizon.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The site the app shows. Links to it (in emails, etc.) open the app: see the App Links filter in the manifest
        val siteHost = "medizon-mze9.onrender.com"
        buildConfigField("String", "BASE_URL", "\"https://$siteHost\"")
        manifestPlaceholders["siteHost"] = siteHost
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase("apiKey")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase("appId")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase("projectId")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebase("senderId")}\"")
    }

    signingConfigs {
        create("release") {
            val store = keystoreProps.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.firebase:firebase-messaging:23.4.1")
    // Sign in with Google: the phone's own account picker (Google blocks its sign-in page inside WebViews)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}

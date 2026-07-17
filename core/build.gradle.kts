plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readershell.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // NanoHTTPD: simple embedded HTTP server.
    // api/ — NanoHTTPD types appear in ProxyServer's public surface
    // (Router.route returns Response), so consumers need them on classpath.
    api("org.nanohttpd:nanohttpd:2.3.1")
    // OkHttp for cloud calls (cookie jar built-in).
    // api/ — Request/Response appear in CloudClient's signature; app-modules build their own requests.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json is ambient on Android; no dep needed.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

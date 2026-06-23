plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readershell.manga"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readershell.manga"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"https://manga.razerblade.dev\"")
            buildConfigField("String", "DEFAULT_PASSWORD", "\"wade-buster-hugely\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_CLOUD_URL", "\"\"")
            buildConfigField("String", "DEFAULT_PASSWORD", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
        }
    }
}

val webUiSourceStatic = file("${rootDir}/../manga-library/static")
val webUiSourceFonts  = file("${rootDir}/../manga-library/fonts")

val copyWebAssets by tasks.registering(Copy::class) {
    val dest = layout.buildDirectory.dir("generated/webAssets/web")
    into(dest)
    from(webUiSourceStatic) { into(".") }
    if (webUiSourceFonts.isDirectory) {
        from(webUiSourceFonts)  { into("fonts") }
    }
    doFirst {
        require(webUiSourceStatic.isDirectory) {
            "manga-library static dir not found at $webUiSourceStatic — adjust path in app-manga/build.gradle.kts"
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(copyWebAssets) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

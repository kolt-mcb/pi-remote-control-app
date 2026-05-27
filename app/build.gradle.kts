plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// versionCode comes from the master-branch commit count. Monotonically
// increasing as long as we only ship from master; lets the in-app updater
// compare what's installed vs what's on GitHub. versionName is the short
// SHA so the user can identify a build at a glance.
//
// Outside a git checkout (e.g. some IDE sync edge cases) we fall back to
// versionCode=1 / versionName="0.1.0".
//
// VERSION_CODE_OFFSET: this repo was split out of grunt3714-lgtm/pi-remote-control,
// where the rolling `latest` build had already reached versionCode 89. The split
// reset the commit count, so we add an offset to stay strictly above 89 and keep
// the updater monotonic. The CI workflow (build-apk.yml) applies the SAME offset
// when it stamps the artifact name / release body — keep the two in sync.
val gitVersionCode: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

val VERSION_CODE_OFFSET = 100

val gitVersionName: String = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.map { it.trim().ifBlank { "0.1.0" } }.getOrElse("0.1.0")

android {
    namespace = "com.piremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piremote"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode + VERSION_CODE_OFFSET
        versionName = gitVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used to detect app foreground state so we only
    // post "pi done" notifications when the user isn't already watching the chat.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Room DB — chat persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")


    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Syntax highlighting for fenced code blocks — same hljs grammar lineage as pi-tui
    implementation("dev.snipme:highlights:1.0.0")
}

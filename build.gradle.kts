plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // KSP for Room compiler — replaces kapt, which can't read Kotlin 2.x
    // metadata stamps and so blocks consuming newer Kotlin-MP libraries.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

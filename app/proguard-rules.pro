# Pi Remote Control — R8 rules for release builds.
#
# KEEP: Custom data classes used in JSON (de)serialization. The hand-rolled
# JP parser accesses map keys by string — no reflection needed — but keeping
# these classes avoids confusion and protects against accidental renaming.
-keepclassmembers class com.piremote.** { *; }
-keep class com.piremote.** { *; }
-keep class com.piremote.db.** { *; }
#
# The `applicationId com.piremote` line that used to be here was Gradle DSL
# accidentally pasted into a ProGuard file — R8 doesn't parse it and the
# whole rules file fails to compile, which kills minifyReleaseWithR8.

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines — defensive; the library ships consumer rules but
# pinning these silences a stray warning on some R8 versions.
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Highlights (dev.snipme:highlights) uses kotlinx.serialization annotations
# on its model classes. Keep them so the runtime can construct them via
# reflection; the parsed-tokens path doesn't need it but the @Serializable
# constructors get scanned on class load.
-keepclassmembers class dev.snipme.highlights.model.** { *; }
-keepnames class dev.snipme.highlights.model.**

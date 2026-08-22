# ==============================================================================
# FarmBot ProGuard 规则
# ==============================================================================

# ---------- OpenCV (Stub 类 + 真实 AAR) ----------
-keep class org.opencv.** { *; }
-keep class org.opencv.android.** { *; }
-keep class org.opencv.core.** { *; }
-keep class org.opencv.imgproc.** { *; }
-keep class org.opencv.imgcodecs.** { *; }
-dontwarn org.opencv.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Google ML Kit ----------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.odml.**

# ---------- Kotlin Coroutines ----------
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.coroutines.**

# ---------- Jetpack Compose ----------
-keep class androidx.compose.** { *; }
-keep class androidx.ui.** { *; }
-keep class androidx.activity.compose.** { *; }
-dontwarn androidx.compose.**

# ---------- Lifecycle / Service ----------
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }

# ---------- AccessibilityService / MediaProjection ----------
-keep class com.farmbot.accessibility.** { *; }
-keep class com.farmbot.screenshot.** { *; }

# ---------- 数据模型（避免反射/JSON 出错） ----------
-keep class com.farmbot.config.** { *; }
-keep class com.farmbot.engine.FarmState** { *; }
-keep class com.farmbot.engine.ActionExecutor** { *; }
-keep class com.farmbot.vision.** { *; }
-keep class com.farmbot.recognition.** { *; }
-keep class com.farmbot.ocr.** { *; }
-keep class com.farmbot.policy.** { *; }
-keep class com.farmbot.metrics.** { *; }

# ---------- kotlinx.serialization（@Serializable 类的 $$serializer） ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.farmbot.policy.CropPolicy$$serializer { *; }

# ---------- WorkManager Worker ----------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class com.farmbot.work.** { *; }

# ---------- Timber ----------
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# ---------- DataStore ----------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---------- Accompanist ----------
-keep class com.google.accompanist.** { *; }
-dontwarn com.google.accompanist.**

# ---------- 去除调试/Log ----------
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

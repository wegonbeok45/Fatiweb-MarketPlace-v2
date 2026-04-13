# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Firebase
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Google Sign-In
-keep class com.google.android.gms.auth.** { *; }

# Keep data classes used with Firestore serialization
-keepclassmembers class isim.ia2y.myapplication.** {
    <fields>;
    <init>(...);
}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# OkHttp (used by GeminiChatService)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# org.json (used for Gemini API response parsing)
-keep class org.json.** { *; }

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Firebase / Play services public SDK types used through generated code and reflection
-keepattributes Signature
-keepattributes *Annotation*

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Google Sign-In
-keep class com.google.android.gms.auth.** { *; }

# Keep only app model classes that are converted to/from Firebase maps.
-keepclassmembers class isim.ia2y.myapplication.Product { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.ProductReview { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.AppOrder { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.OrderItem { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.OrderStatusEntry { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.DeliveryAddress { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.DeliveryAddressSnapshot { <fields>; <init>(...); }
-keepclassmembers class isim.ia2y.myapplication.FirestoreService$* { <fields>; <init>(...); }

# Coil/Okio are used by Coil's image loader internals.
-dontwarn okio.**

# org.json (used for Gemini API response parsing)
-keep class org.json.** { *; }

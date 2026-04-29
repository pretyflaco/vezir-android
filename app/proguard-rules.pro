# Vezir Android — release shrink/obfuscation rules.
#
# Kotlinx Serialization needs its generated serializers preserved.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.vezir.android.**$$serializer { *; }
-keepclassmembers class com.vezir.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.vezir.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio platform stubs that aren't on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- NewPipe Extractor (+ its Rhino JS engine, used reflectively) ----------
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**

# ---------- LiteRT (successor of TensorFlow Lite; JNI looks these up by name) ----------
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.google.ai.edge.litert.**

# ---------- ML Kit face detection ----------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-dontwarn com.google.mlkit.**

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.youtubly.app.**$$serializer { *; }
-keepclassmembers class com.youtubly.app.** { *** Companion; }
-keepclasseswithmembers class com.youtubly.app.** { kotlinx.serialization.KSerializer serializer(...); }

# ---------- Retrofit / OkHttp ----------
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.youtubly.app.data.remote.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- Room entities / DAOs ----------
-keep class com.youtubly.app.data.local.entity.** { *; }

# ---------- Glance widget + Media3 service (referenced from the manifest) ----------
-keep class com.youtubly.app.widget.** { *; }
-keep class com.youtubly.app.playback.PlaybackService { *; }
-dontwarn androidx.media3.**

# ---------- Keep line numbers for readable crash traces ----------
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

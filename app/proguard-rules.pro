-keep class com.example.remoty.** { *; }
# libzt is called from native code (JNI): never rename or strip it.
-keep class com.zerotier.sockets.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

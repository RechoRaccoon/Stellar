# Stellar release shrinking (see androidApp/build.gradle.kts).
#
# Conservative on purpose: R8 only removes code nothing uses (mostly unused
# Material icons and library features). Nothing is renamed, and Stellar's
# own classes plus every library that talks to native code or reads classes
# by name are kept whole.

-dontobfuscate
-ignorewarnings
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

# Stellar itself (Gson/kotlinx models, WorkManager workers, widgets,
# services, receivers — everything).
-keep class com.mediaviewer.** { *; }
-keep class rechoraccoon.stellar.** { *; }

# Native-backed libraries: their C++ side calls back into Java by name.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.utils.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }

# Reflection-based JSON.
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }

# Compose resources (fonts, the logo) are looked up through generated classes.
-keep class org.jetbrains.compose.resources.** { *; }

# kotlinx.serialization: serializers of every @Serializable class.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# MediaPipe logs through Flogger, which loads its backend by class name.
-keep class com.google.common.flogger.** { *; }
-keep class com.google.mediapipe.proto.** { *; }

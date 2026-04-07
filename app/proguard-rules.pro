# Ktor / Netty — heavy use of reflection and service loaders
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.ktor.**

# LiteRT-LM — loaded via JNI/reflection
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class dev.thenets.pocketd.**$$serializer { *; }
-keepclassmembers class dev.thenets.pocketd.** {
    *** Companion;
}
-keepclasseswithmembers class dev.thenets.pocketd.** {
    kotlinx.serialization.KSerializer serializer(...);
}

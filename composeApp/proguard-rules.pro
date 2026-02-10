# GCC Worker App - ProGuard Rules

# Ktor client
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gcc.shared.model.**$$serializer { *; }
-keepclassmembers class com.gcc.shared.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.gcc.shared.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Decompose
-keep class com.arkivanov.decompose.** { *; }

# Koin
-keep class org.koin.** { *; }

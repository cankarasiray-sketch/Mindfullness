# kotlinx.serialization: keep generated serializers for the app's @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.mindfullness.weather.**$$serializer { *; }
-keepclassmembers class com.mindfullness.weather.** {
    *** Companion;
}
-keepclasseswithmembers class com.mindfullness.weather.** {
    kotlinx.serialization.KSerializer serializer(...);
}

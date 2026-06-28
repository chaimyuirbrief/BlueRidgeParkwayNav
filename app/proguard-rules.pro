# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.blueridge.parkwaynav.** {
    *** Companion;
}
-keepclasseswithmembers class com.blueridge.parkwaynav.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.blueridge.parkwaynav.**$$serializer { *; }

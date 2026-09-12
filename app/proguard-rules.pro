# Glance instantiates widget action callbacks reflectively by class name
-keep class * implements androidx.glance.appwidget.action.ActionCallback

# kotlinx.serialization: keep serializers for our own @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.transdroid.** {
    *** Companion;
}
-keepclasseswithmembers class org.transdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

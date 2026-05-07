# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.mlkit.common.annotation.Keep *;
}
-dontwarn com.google.mlkit.**

# ONNX Runtime Mobile
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Room
-keep class androidx.room.** { *; }
# Keep model contract parsing
-keep class ai.sentinel.mobile.model.** { *; }
-dontwarn org.slf4j.**

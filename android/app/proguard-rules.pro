# Retrofit + kotlinx.serialization keep rules
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,includedescriptorclasses class com.flatexpense.data.api.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }

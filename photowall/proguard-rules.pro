# Add project specific ProGuard rules here.

# Keep application classes
-keep class com.hxfssc.activity.** { *; }

# JCIFS-NG rules (SMB library)
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class javax.crypto.** { *; }
-dontwarn javax.crypto.**

# JSON
-keep class org.json.** { *; }

# Standard Android
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Ignore warnings for classes that might be missing in older Android versions
-dontwarn java.nio.file.**
-dontwarn sun.misc.Unsafe
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

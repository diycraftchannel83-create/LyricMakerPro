# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Preserve source file and line information in R8 mapping for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Project files are serialized with Moshi's Kotlin reflection adapter.
# Keep constructor parameter/field names and Kotlin metadata stable in minified releases;
# otherwise Moshi can report missing required fields with obfuscated names (for example "SZ").
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class kotlin.Metadata { *; }
-keep class com.winsmediatama.lyricmakerpro.model.** { *; }
-keep class androidx.compose.ui.text.style.TextAlign { *; }

# Keep Moshi's reflective Kotlin adapter available in release builds.
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }
-keepclassmembers class com.winsmediatama.lyricmakerpro.data.ProjectFileCodec { *; }

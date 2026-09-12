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

# Activities reference these handlers directly from XML layouts. They are not always visible to
# R8's whole-program analysis, especially when OEM resources are overlaid at install time.
-keepclassmembers class * {
    public void *(android.view.View);
}

# JNI entry points and their names are an ABI. Retain only classes which actually expose native
# methods instead of keeping an entire application package.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Keep metadata needed by framework components and reflective OEM compatibility checks.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

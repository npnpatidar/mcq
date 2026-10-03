# R8 rules for the release build.
#
# The libraries used here all ship consumer rules, so this file is mostly
# belt-and-braces. Anything kept for reflection is listed with the reason.

# kotlinx.serialization generates serializers and looks them up by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.mcqapp.** {
    *** Companion;
}
-keepclasseswithmembers class com.mcqapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generates an implementation that is found by name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# The FileProvider authority is resolved from the manifest.
-keep class androidx.core.content.FileProvider { *; }

# Our JavascriptInterface bridges are called from the bundled MathLive and
# MathJax pages by name.
-keepclassmembers class com.mcqapp.ui.editor.MathLiveEditor$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Line numbers make release crash reports readable; the source file name is
# still obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

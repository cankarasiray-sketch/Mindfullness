-dontobfuscate
-dontoptimize
-dontpreverify
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class !kotlin.**,!org.intellij.**,!org.jetbrains.** { *; }
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontnote **

# ThreeTen backport: DateTimeUtils converts java.sql types, which exist on Android but not in java.base.
-dontwarn java.sql.**

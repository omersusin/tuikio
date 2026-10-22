# Tuikio release shrinking (ProGuard 7; the same rules work with R8 under AGP).
# Activities, the Application and manifest components are kept by aapt2's generated rules.

-optimizationpasses 5
-allowaccessmodification
-repackageclasses 't'
# The optimizations Android's proguard-android-optimize.txt excludes for ART/dex safety.
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable

-dontusemixedcaseclassnames
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# No reflection, serialization frameworks or JNI in Tuikio: nothing else needs keeping.
# Kotlin's compile-time-only annotations and JDK-only classes referenced by the stdlib:
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**

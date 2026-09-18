# The core reaches this entry point by name.
-keep class dev.appatlas.sdk.crash.AtlasCrash { public *; }
# A stack trace is only retraceable with its line table, and an exception's
# runtime type must survive class merging to match the mapping.
-keepattributes LineNumberTable,SourceFile
-keep,allowshrinking,allowobfuscation class * extends java.lang.Throwable
# AGP 7.3's R8 strips enum members a stack trace still names.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# The bridge is reached by name from atlas-crash and by JNI from the .so.
-keep class dev.appatlas.sdk.crash.ndk.NativeBridge { public *; }
-keepclasseswithmembernames class dev.appatlas.sdk.crash.ndk.NativeBridge { native <methods>; }

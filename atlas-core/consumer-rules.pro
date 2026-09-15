# Frames from this SDK stay nameable in a stack trace, and the entry point is
# reached from application code that R8 must not strip.
-keeppackagenames dev.appatlas.sdk.**
-keep class dev.appatlas.sdk.Atlas { public *; }

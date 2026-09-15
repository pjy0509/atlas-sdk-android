# The core reaches this entry point by name; a consumer without the referrer
# library must not warn on the guarded classes.
-keep class dev.appatlas.sdk.links.AtlasLinks { public *; }
-dontwarn com.android.installreferrer.**

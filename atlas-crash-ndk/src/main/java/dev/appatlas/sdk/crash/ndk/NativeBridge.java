package dev.appatlas.sdk.crash.ndk;

/**
 * The Java side of the native capture core. One call in, a report path out;
 * the C handler never calls back, so there is nothing else here. Public
 * because atlas-crash reaches it by name: the artifact is optional, and a
 * compile-time reference would make it mandatory.
 */
public final class NativeBridge {

    static {
        System.loadLibrary("atlas-native");
    }

    private NativeBridge() {
    }

    /** Called by AtlasCrash through reflection; not application API. */
    public static native int install(String reportPath);
}

// atlas_jni.c: the only JNI in the module. It hands the C core a path to
// write to and nothing else — the handler never calls back into the VM,
// which is the rule that keeps it async-signal-safe.
#include <jni.h>

#include "atlas_crash.h"

JNIEXPORT jint JNICALL
Java_dev_appatlas_sdk_crash_ndk_NativeBridge_install(JNIEnv *env, jclass klass, jstring report_path) {
    (void) klass;

    const char *path = (*env)->GetStringUTFChars(env, report_path, 0);

    if (path == 0) {
        return -1;
    }

    int result = atlas_crash_install(path);
    (*env)->ReleaseStringUTFChars(env, report_path, path);

    return result;
}

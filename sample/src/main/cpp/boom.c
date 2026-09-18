// boom.c: the sample's own native code, dying three calls deep so the report
// has frames to symbolicate.
#include <jni.h>

static int *volatile nowhere;

__attribute__((noinline)) static void sample_inner(void) { *nowhere = 7; }
__attribute__((noinline)) static void sample_middle(void) { sample_inner(); __asm__("nop"); }

JNIEXPORT void JNICALL
Java_dev_appatlas_sample_MainActivity_nativeBoom(JNIEnv *env, jclass klass) {
    (void) env;
    (void) klass;
    sample_middle();
}

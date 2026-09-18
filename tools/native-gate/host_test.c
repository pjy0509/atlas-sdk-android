// The host harness for the native capture core: installs the handler, then
// crashes on a named thread inside libvictim, and leaves a report file the
// gate script inspects. Not shipped — a gate fixture only.
#define _GNU_SOURCE
#include <pthread.h>
#include <string.h>
#include "../../atlas-crash-ndk/src/main/cpp/atlas_crash.h"

void victim_null(void);
void victim_abort(void);
void victim_overflow(void);
void victim_div(int zero);

static void *on_thread(void *how) {
    pthread_setname_np(pthread_self(), "worker-7");
    ((void (*)(void)) how)();

    return 0;
}

int main(int argc, char **argv) {
    (void) argc;

    if (atlas_crash_install(argv[1]) != 0) return 2;

    const char *how = argv[2];

    if (!strcmp(how, "null")) victim_null();
    if (!strcmp(how, "abort")) victim_abort();
    if (!strcmp(how, "overflow")) victim_overflow();
    if (!strcmp(how, "div")) victim_div(0);
    if (!strcmp(how, "thread")) {
        pthread_t thread;
        pthread_create(&thread, 0, on_thread, (void *) victim_null);
        pthread_join(thread, 0);
    }

    return 0;
}

// atlas_crash.h: the native capture core, free of JNI so a host build can test it.
#ifndef ATLAS_CRASH_H
#define ATLAS_CRASH_H

#ifdef __cplusplus
extern "C" {
#endif

// Installs the signal handlers; a crash writes its report to `report_path`.
// Returns 0, or -1 when the path does not fit or no handler could be set.
int atlas_crash_install(const char *report_path);

#ifdef __cplusplus
}
#endif

#endif

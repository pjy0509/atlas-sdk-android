package dev.appatlas.sdk.crash;

import java.util.Map;

/**
 * What only the platform can answer at the moment of a report. Kept behind
 * an interface so the reporter stays plain Java and the gate can run it.
 * Both calls happen on a thread that may be dying: they must never throw.
 */
interface CrashPlatform {

    /** Memory, disk, foreground and the like, merged into the report's context. */
    Map<String, Object> context();

    /** Called before the crash is written: the platform may forbid disk I/O here. */
    void beforeDiskWrite();

    /** The UI thread, whose death must be reported inside the ANR budget. */
    Thread mainThread();
}

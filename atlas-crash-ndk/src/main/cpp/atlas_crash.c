// atlas_crash.c: native crash capture.
//
// Everything reachable from the signal handler is async-signal-safe: no
// allocation, no stdio, no locks; open/read/write/close and memory already
// reserved at install. The one exception is the CFI unwind, which runs last,
// after a frame-pointer walk is already on disk, under an alarm that ends
// the process if the unwinder deadlocks on a lock the crashed thread held.
//
// The report is line-based text; the Java half reads it at the next start
// (NativeReport.java). FROZEN once shipped: the line vocabulary.
#define _GNU_SOURCE
#include "atlas_crash.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#define MAX_FRAMES 128
#define MAX_MAPS 4096
#define PATH_POOL (512 * 1024)
#define MAX_PATH 1024
#define ALT_STACK (64 * 1024)
#define UNWIND_GUARD_SECONDS 3

static const int SIGNALS[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP, SIGSYS};
#define SIGNAL_COUNT ((int) (sizeof(SIGNALS) / sizeof(SIGNALS[0])))

struct map_entry {
    uintptr_t start;
    uintptr_t end;
    uintptr_t offset;
    char readable;
    char executable;
    uint32_t path;  // offset into the pool; 0 is the empty path
};

static struct sigaction previous[SIGNAL_COUNT];
static char installed[SIGNAL_COUNT];
static char report_path[MAX_PATH];
static unsigned char alt_stack[ALT_STACK];
static volatile sig_atomic_t handling;

static struct map_entry maps[MAX_MAPS];
static int map_count;
static char pool[PATH_POOL];
static uint32_t pool_used;

// --- output: a small buffer over write() ---------------------------------------

static int out_fd = -1;
static char out_buf[4096];
static size_t out_len;

static void out_flush(void) {
    size_t done = 0;

    while (done < out_len) {
        ssize_t wrote = write(out_fd, out_buf + done, out_len - done);

        if (wrote < 0) {
            if (errno == EINTR) continue;
            break;
        }

        done += (size_t) wrote;
    }

    out_len = 0;
}

static void out_char(char c) {
    if (out_len == sizeof(out_buf)) out_flush();
    out_buf[out_len++] = c;
}

static void out_str(const char *text) {
    while (*text) out_char(*text++);
}

static void out_hex(uint64_t value) {
    char digits[16];
    int count = 0;

    do {
        digits[count++] = "0123456789abcdef"[value & 0xf];
        value >>= 4;
    } while (value);

    while (count) out_char(digits[--count]);
}

static void out_dec(int64_t value) {
    char digits[24];
    int count = 0;
    uint64_t rest = value < 0 ? (uint64_t) (-(value + 1)) + 1 : (uint64_t) value;

    if (value < 0) out_char('-');

    do {
        digits[count++] = (char) ('0' + rest % 10);
        rest /= 10;
    } while (rest);

    while (count) out_char(digits[--count]);
}

// --- /proc/self/maps, parsed by hand ---------------------------------------------

static uintptr_t parse_hex(const char **cursor) {
    uintptr_t value = 0;

    for (;; (*cursor)++) {
        char c = **cursor;

        if (c >= '0' && c <= '9') value = (value << 4) | (uintptr_t) (c - '0');
        else if (c >= 'a' && c <= 'f') value = (value << 4) | (uintptr_t) (c - 'a' + 10);
        else return value;
    }
}

static void parse_map_line(const char *line) {
    if (map_count == MAX_MAPS) return;

    struct map_entry *entry = &maps[map_count];
    const char *cursor = line;

    entry->start = parse_hex(&cursor);
    if (*cursor++ != '-') return;
    entry->end = parse_hex(&cursor);
    if (*cursor++ != ' ') return;

    entry->readable = cursor[0] == 'r';
    entry->executable = cursor[2] == 'x';
    cursor += 4;
    if (*cursor++ != ' ') return;
    entry->offset = parse_hex(&cursor);

    // Skip " dev inode", then the run of spaces before the path.
    for (int fields = 0; fields < 2; fields++) {
        while (*cursor == ' ') cursor++;
        while (*cursor && *cursor != ' ') cursor++;
    }
    while (*cursor == ' ') cursor++;

    entry->path = 0;

    size_t length = strlen(cursor);

    if (length > 0 && pool_used + length + 1 <= PATH_POOL) {
        entry->path = pool_used;
        memcpy(pool + pool_used, cursor, length + 1);
        pool_used += (uint32_t) length + 1;
    }

    map_count++;
}

static void read_maps(void) {
    static char line[MAX_PATH + 128];
    static char chunk[8192];
    size_t line_len = 0;
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);

    map_count = 0;
    pool_used = 1;  // pool[0] stays '\0': the empty path
    pool[0] = '\0';

    if (fd < 0) return;

    for (;;) {
        ssize_t got = read(fd, chunk, sizeof(chunk));

        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) break;

        for (ssize_t i = 0; i < got; i++) {
            if (chunk[i] == '\n') {
                line[line_len] = '\0';
                parse_map_line(line);
                line_len = 0;
            } else if (line_len < sizeof(line) - 1) {
                line[line_len++] = chunk[i];
            }
        }
    }

    close(fd);
}

static const struct map_entry *map_of(uintptr_t address) {
    for (int i = 0; i < map_count; i++) {
        if (address >= maps[i].start && address < maps[i].end) return &maps[i];
    }

    return NULL;
}

// Whether [address, address + size) may be read without faulting again.
static int readable(uintptr_t address, size_t size) {
    while (size > 0) {
        const struct map_entry *entry = map_of(address);

        if (entry == NULL || !entry->readable) return 0;

        size_t here = entry->end - address;

        if (here >= size) return 1;

        address += here;
        size -= here;
    }

    return 1;
}

// --- the module behind a pc: its ELF base and build id -----------------------------

// The highest ELF header at or below `pc` in mappings of the same file. A
// library run straight out of the APK sits at a non-zero file offset, so
// "offset 0" is not the test; the magic is.
static const struct map_entry *elf_base_of(const struct map_entry *hit, uintptr_t pc) {
    const struct map_entry *best = NULL;

    for (int i = 0; i < map_count; i++) {
        const struct map_entry *entry = &maps[i];

        if (entry->start > pc || !entry->readable || entry->end - entry->start < SELFMAG) continue;
        if (entry->path != hit->path && strcmp(pool + entry->path, pool + hit->path) != 0) continue;
        if (memcmp((const void *) entry->start, ELFMAG, SELFMAG) != 0) continue;
        if (best == NULL || entry->start > best->start) best = entry;
    }

    return best;
}

static void out_build_id(uintptr_t base) {
    if (!readable(base, sizeof(ElfW(Ehdr)))) goto none;

    const ElfW(Ehdr) *header = (const ElfW(Ehdr) *) base;
    uintptr_t table = base + header->e_phoff;

    if (!readable(table, (size_t) header->e_phnum * sizeof(ElfW(Phdr)))) goto none;

    const ElfW(Phdr) *segments = (const ElfW(Phdr) *) table;
    uintptr_t lowest = UINTPTR_MAX;

    for (int i = 0; i < header->e_phnum; i++) {
        if (segments[i].p_type == PT_LOAD && segments[i].p_vaddr < lowest) lowest = segments[i].p_vaddr;
    }

    if (lowest == UINTPTR_MAX) goto none;

    for (int i = 0; i < header->e_phnum; i++) {
        if (segments[i].p_type != PT_NOTE) continue;

        uintptr_t cursor = base + (segments[i].p_vaddr - lowest);
        uintptr_t end = cursor + segments[i].p_filesz;

        if (!readable(cursor, segments[i].p_filesz)) continue;

        while (cursor + sizeof(ElfW(Nhdr)) <= end) {
            const ElfW(Nhdr) *note = (const ElfW(Nhdr) *) cursor;
            uintptr_t name = cursor + sizeof(ElfW(Nhdr));
            uintptr_t desc = name + ((note->n_namesz + 3u) & ~3u);

            if (desc + note->n_descsz > end) break;

            if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz == 4 && memcmp((const void *) name, "GNU", 4) == 0
                    && note->n_descsz > 0 && note->n_descsz <= 64) {
                const unsigned char *bytes = (const unsigned char *) desc;

                for (uint32_t b = 0; b < note->n_descsz; b++) {
                    out_char("0123456789abcdef"[bytes[b] >> 4]);
                    out_char("0123456789abcdef"[bytes[b] & 0xf]);
                }

                return;
            }

            cursor = desc + ((note->n_descsz + 3u) & ~3u);
        }
    }

none:
    out_char('-');
}

// frame <pc> <pc relative to the ELF base> <build id|-> <path|->
static void out_frame(uintptr_t pc) {
    const struct map_entry *hit = map_of(pc);
    const struct map_entry *base = hit && hit->path ? elf_base_of(hit, pc) : NULL;

    out_str("frame ");
    out_hex(pc);
    out_char(' ');

    if (base != NULL) {
        out_hex(pc - base->start);
        out_char(' ');
        out_build_id(base->start);
    } else {
        out_str("- -");
    }

    out_char(' ');
    out_str(hit && hit->path ? pool + hit->path : "-");
    out_char('\n');
}

// --- unwinding ---------------------------------------------------------------------

struct registers {
    uintptr_t pc;
    uintptr_t sp;
    uintptr_t fp;
    uintptr_t lr;
    int walkable;  // the frame-pointer chain means something on this ABI
};

static void read_registers(const ucontext_t *context, struct registers *regs) {
    memset(regs, 0, sizeof(*regs));
#if defined(__aarch64__)
    regs->pc = (uintptr_t) context->uc_mcontext.pc;
    regs->sp = (uintptr_t) context->uc_mcontext.sp;
    regs->fp = (uintptr_t) context->uc_mcontext.regs[29];
    regs->lr = (uintptr_t) context->uc_mcontext.regs[30];
    regs->walkable = 1;
#elif defined(__x86_64__)
    regs->pc = (uintptr_t) context->uc_mcontext.gregs[REG_RIP];
    regs->sp = (uintptr_t) context->uc_mcontext.gregs[REG_RSP];
    regs->fp = (uintptr_t) context->uc_mcontext.gregs[REG_RBP];
    regs->walkable = 1;
#elif defined(__arm__)
    regs->pc = (uintptr_t) context->uc_mcontext.arm_pc;
    regs->sp = (uintptr_t) context->uc_mcontext.arm_sp;
    regs->fp = (uintptr_t) context->uc_mcontext.arm_fp;
    regs->lr = (uintptr_t) context->uc_mcontext.arm_lr;
#elif defined(__i386__)
    regs->pc = (uintptr_t) context->uc_mcontext.gregs[REG_EIP];
    regs->sp = (uintptr_t) context->uc_mcontext.gregs[REG_ESP];
    regs->fp = (uintptr_t) context->uc_mcontext.gregs[REG_EBP];
    regs->walkable = 1;
#endif
}

static uintptr_t strip_pointer_auth(uintptr_t address) {
#if defined(__aarch64__)
    // A signed return address carries its signature above the 48-bit user range.
    return address & ((UINT64_C(1) << 48) - 1);
#else
    return address;
#endif
}

// Every read is checked against the maps first, so a smashed stack ends the
// walk instead of faulting inside the handler.
static void walk_frame_pointers(const struct registers *regs) {
    uintptr_t fp = regs->fp;
    int frames = 1;

    out_str("unwind fp\n");
    out_frame(regs->pc);

    while (regs->walkable && frames < MAX_FRAMES && fp != 0 && (fp & (sizeof(uintptr_t) - 1)) == 0
            && readable(fp, 2 * sizeof(uintptr_t))) {
        uintptr_t next = ((const uintptr_t *) fp)[0];
        uintptr_t ret = strip_pointer_auth(((const uintptr_t *) fp)[1]);
        const struct map_entry *code = map_of(ret);

        if (code == NULL || !code->executable) break;

        out_frame(ret);
        frames++;

        // The chain only ever climbs; anything else is a corrupt record.
        if (next <= fp) break;

        fp = next;
    }
}

struct cfi_trace {
    uintptr_t ips[MAX_FRAMES + 32];
    int count;
};

static _Unwind_Reason_Code on_cfi_frame(struct _Unwind_Context *context, void *argument) {
    struct cfi_trace *trace = (struct cfi_trace *) argument;
    uintptr_t ip = strip_pointer_auth((uintptr_t) _Unwind_GetIP(context));

    if (ip == 0 || trace->count == (int) (sizeof(trace->ips) / sizeof(trace->ips[0]))) return _URC_END_OF_STACK;

    trace->ips[trace->count++] = ip;

    return _URC_NO_REASON;
}

// The unwinder starts in this handler and crosses the signal frame; the
// crash's own frames begin where its pc appears. Absent, the unwinder did
// not make it across and the frame-pointer walk stands.
static void walk_cfi(const struct registers *regs) {
    static struct cfi_trace trace;
    int first = -1;

    trace.count = 0;
    _Unwind_Backtrace(on_cfi_frame, &trace);

    for (int i = 0; i < trace.count; i++) {
        if (trace.ips[i] == regs->pc) {
            first = i;
            break;
        }
    }

    if (first < 0 || trace.count - first < 2) return;

    out_str("unwind cfi\n");

    for (int i = first; i < trace.count && i - first < MAX_FRAMES; i++) out_frame(trace.ips[i]);
}

// --- the handler -------------------------------------------------------------------

static void out_thread_name(pid_t tid) {
    // /proc/self/task/<tid>/comm, built by hand.
    char path[64] = "/proc/self/task/";
    char digits[16];
    char name[32];
    int count = 0;
    size_t at = strlen(path);

    for (pid_t rest = tid; rest > 0; rest /= 10) digits[count++] = (char) ('0' + rest % 10);
    while (count) path[at++] = digits[--count];
    memcpy(path + at, "/comm", 6);

    int fd = open(path, O_RDONLY | O_CLOEXEC);

    if (fd < 0) return;

    ssize_t got = read(fd, name, sizeof(name) - 1);
    close(fd);

    if (got <= 0) return;
    if (name[got - 1] == '\n') got--;
    name[got] = '\0';

    out_str("thread ");
    out_str(name);
    out_char('\n');
}

static void hand_over(int index, int signo, siginfo_t *info) {
    // Whoever was here first gets the signal next: debuggerd's handler is what
    // writes the tombstone and ends the process.
    sigaction(signo, &previous[index], NULL);

    // A fault re-executes on return and lands in the restored handler. A
    // signal that was sent (abort, kill, tgkill) does not repeat by itself.
    if (signo == SIGABRT || info == NULL || info->si_code <= 0) {
        syscall(SYS_tgkill, getpid(), (pid_t) syscall(SYS_gettid), signo);
    }
}

static void on_signal(int signo, siginfo_t *info, void *raw_context) {
    int saved_errno = errno;
    int index = 0;

    while (index < SIGNAL_COUNT - 1 && SIGNALS[index] != signo) index++;

    // A second fault, in another thread or in this handler: step aside.
    if (handling) {
        hand_over(index, signo, info);
        errno = saved_errno;

        return;
    }

    handling = 1;

    struct registers regs;
    pid_t tid = (pid_t) syscall(SYS_gettid);

    read_registers((const ucontext_t *) raw_context, &regs);
    out_fd = open(report_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);

    if (out_fd >= 0) {
        struct timespec now = {0, 0};
        clock_gettime(CLOCK_REALTIME, &now);

        out_str("atlas-native-crash 1\ntime ");
        out_dec((int64_t) now.tv_sec);
        out_str("\nsignal ");
        out_dec(signo);
        out_char(' ');
        out_dec(info ? info->si_code : 0);
        out_char(' ');
        out_hex(info ? (uintptr_t) info->si_addr : 0);
        out_str("\npid ");
        out_dec(getpid());
        out_str(" tid ");
        out_dec(tid);
        out_char('\n');
        out_thread_name(tid);
        out_str("reg pc ");
        out_hex(regs.pc);
        out_str(" sp ");
        out_hex(regs.sp);
        out_str(" fp ");
        out_hex(regs.fp);
        out_str(" lr ");
        out_hex(regs.lr);
        out_char('\n');

        read_maps();
        walk_frame_pointers(&regs);
        out_str("end fp\n");
        out_flush();

        // From here on the report is already whole. If the unwinder waits on
        // a lock the crashed thread held, the alarm ends the process.
        signal(SIGALRM, SIG_DFL);
        alarm(UNWIND_GUARD_SECONDS);
        walk_cfi(&regs);
        alarm(0);

        out_str("end\n");
        out_flush();
        close(out_fd);
        out_fd = -1;
    }

    hand_over(index, signo, info);
    errno = saved_errno;
}

int atlas_crash_install(const char *path) {
    size_t length = path ? strlen(path) : 0;
    int set = 0;

    if (length == 0 || length >= sizeof(report_path)) return -1;

    memcpy(report_path, path, length + 1);

    // This thread's fallback; bionic gives every other thread its own.
    stack_t current;

    if (sigaltstack(NULL, &current) == 0 && (current.ss_flags & SS_DISABLE)) {
        stack_t stack = {.ss_sp = alt_stack, .ss_size = sizeof(alt_stack), .ss_flags = 0};
        sigaltstack(&stack, NULL);
    }

    for (int i = 0; i < SIGNAL_COUNT; i++) {
        struct sigaction action;
        struct sigaction existing;

        // An ignored signal stays ignored: turning it into a crash is our bug.
        if (installed[i] || sigaction(SIGNALS[i], NULL, &existing) != 0 || existing.sa_handler == SIG_IGN) continue;

        memset(&action, 0, sizeof(action));
        sigemptyset(&action.sa_mask);
        action.sa_sigaction = on_signal;
        // Not SA_RESETHAND: hand_over restores the previous action itself.
        action.sa_flags = SA_SIGINFO | SA_ONSTACK;

        if (sigaction(SIGNALS[i], &action, &previous[i]) == 0) {
            installed[i] = 1;
            set++;
        }
    }

    return set > 0 ? 0 : -1;
}

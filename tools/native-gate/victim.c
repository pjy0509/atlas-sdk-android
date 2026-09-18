// Dies in a named way, inside a shared library, so the gate can prove a
// crash in other code (not the handler's own) is captured with its frames.
static int *volatile nowhere;

__attribute__((noinline)) static void inner_null(void) { *nowhere = 42; }
__attribute__((noinline)) static void middle_null(void) { inner_null(); __asm__("nop"); }
__attribute__((noinline)) void victim_null(void) { middle_null(); __asm__("nop"); }

__attribute__((noinline)) void victim_abort(void) { __builtin_trap(); }

__attribute__((noinline)) static int deeper(int d) { volatile char pad[512]; pad[0] = (char) d; return deeper(d + 1) + pad[0]; }
__attribute__((noinline)) void victim_overflow(void) { deeper(0); }

__attribute__((noinline)) void victim_div(int zero) { volatile int one = 1; one = one / zero; }

/*
 * libemux-linkfix — LD_PRELOAD shim that implements link(2) in terms of
 * linkat(2).
 *
 * WHY (v0.1.53 field round, Ubuntu/arm64 device):
 *   dpkg backs up its status database with  unlink(status-old) +
 *   link(status, status-old).  Under the bundled Termux proot, the LEGACY
 *   link(2) syscall is translated through proot's seccomp "avoider"
 *   (SIGSYS fallback) and on several Android kernels that translation
 *   returns a bogus errno — EEXIST on kernel 3.4 (termux/proot#377) and
 *   EACCES on the v0.1.52 field device — while linkat(2) is translated
 *   correctly.  Result on affected devices: every dpkg/apt run dies with
 *       dpkg: error: error creating new backup file
 *       '/var/lib/dpkg/status-old': Permission denied
 *   which bricks package installation, the desktop component install
 *   (Xvfb) and dpkg --configure -a alike.
 *
 *   The shim is the workaround proven in termux/proot#377: redefine link()
 *   as linkat(AT_FDCWD, old, AT_FDCWD, new, 0) — identical semantics
 *   (link() == linkat() with flags 0).
 *
 * BUILD/PORTABILITY NOTES:
 *   - Freestanding: -nostdlib, no DT_NEEDED.  Raw syscalls via inline asm
 *     so ONE binary per arch loads in glibc AND musl guests.
 *   - errno: the only external symbol is __errno_location, exported by
 *     both glibc and musl; the dynamic loader resolves it against the
 *     preloading process' libc at load time.
 *   - Installed by BootstrapManager into <rootfs>/usr/local/lib/emux/ and
 *     registered in <rootfs>/etc/ld.so.preload (honoured by glibc & musl).
 *
 * Build (see build.sh): <cross-gcc> -shared -fPIC -nostdlib -O2 ...
 */

#define AT_FDCWD (-100)

extern int *__errno_location(void);

#if defined(__aarch64__)
#define SYS_LINKAT 37
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long x8 __asm__("x8") = SYS_LINKAT;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    return x0;
}
#elif defined(__arm__)
#define SYS_LINKAT 330
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long r7 __asm__("r7") = SYS_LINKAT;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    register long r4 __asm__("r4") = e;
    __asm__ volatile("svc 0"
                     : "+r"(r0)
                     : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4)
                     : "memory");
    return r0;
}
#elif defined(__x86_64__)
#define SYS_LINKAT 265
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long rax __asm__("rax") = SYS_LINKAT;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    register long rdx __asm__("rdx") = c;
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    __asm__ volatile("syscall"
                     : "+r"(rax)
                     : "r"(rdi), "r"(rsi), "r"(rdx), "r"(r10), "r"(r8)
                     : "rcx", "r11", "memory");
    return rax;
}
#elif defined(__i386__)
#define SYS_LINKAT 303
static long linkat_sc(long a, long b, long c, long d, long e)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\t"
                     "movl %1, %%ebx\n\t"
                     "int $0x80\n\t"
                     "popl %%ebx\n\t"
                     : "=a"(r)
                     : "g"(a), "a"((long)SYS_LINKAT), "c"(b), "d"(c),
                       "S"(d), "D"(e)
                     : "memory");
    return r;
}
#else
#error "unsupported arch"
#endif

int link(const char *oldpath, const char *newpath)
{
    long r = linkat_sc(AT_FDCWD, (long)oldpath, AT_FDCWD, (long)newpath, 0);
    if (r < 0) {
        *__errno_location() = (int)-r;
        return -1;
    }
    return 0;
}

/* NOTE: no explicit .note.GNU-stack asm here — gcc/clang emit their own
 * non-exec-stack note at the end of the translation unit.  A hand-rolled
 * top-level __asm__ of it is mis-placed ABOVE the function at -O2 (gcc
 * reorders top-level asm), which puts the code into the note section and
 * makes the linker emit `link` as an absolute symbol (calls jump to 0). */

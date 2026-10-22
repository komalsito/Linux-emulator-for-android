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
#define EACCES 13
#define EEXIST 17
#define EPERM 1
#define ENOSYS 38

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

/* Freestanding syscall helpers (no libc). */
#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000
#define MODE 0644

/* per-arch generic syscall (openat/read/write/close) */
#if defined(__aarch64__)
static long sys3(long n, long a, long b, long c)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}
static long sys4(long n, long a, long b, long c, long d)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
#elif defined(__arm__)
static long sys3(long n, long a, long b, long c)
{
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2) : "memory");
    return r0;
}
static long sys4(long n, long a, long b, long c, long d)
{
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3) : "memory");
    return r0;
}
#elif defined(__x86_64__)
static long sys3(long n, long a, long b, long c)
{
    register long rax __asm__("rax") = n;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    register long rdx __asm__("rdx") = c;
    __asm__ volatile("syscall" : "+r"(rax) : "r"(rdi), "r"(rsi), "r"(rdx)
                     : "rcx", "r11", "memory");
    return rax;
}
static long sys4(long n, long a, long b, long c, long d)
{
    register long rax __asm__("rax") = n;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    register long rdx __asm__("rdx") = c;
    register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall" : "+r"(rax) : "r"(rdi), "r"(rsi), "r"(rdx), "r"(r10)
                     : "rcx", "r11", "memory");
    return rax;
}
#else /* i386 */
static long sys3(long n, long a, long b, long c)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\t"
                     "movl %1, %%ebx\n\t"
                     "int $0x80\n\t"
                     "popl %%ebx\n\t"
                     : "=a"(r)
                     : "g"(a), "a"(n), "c"(b), "d"(c)
                     : "memory");
    return r;
}
static long sys4(long n, long a, long b, long c, long d)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\t"
                     "movl %1, %%ebx\n\t"
                     "int $0x80\n\t"
                     "popl %%ebx\n\t"
                     : "=a"(r)
                     : "g"(a), "a"(n), "c"(b), "d"(c), "S"(d)
                     : "memory");
    return r;
}
#endif

#define SYS_OPENAT 56   /* patched per arch below */
#if defined(__aarch64__)
# define N_OPENAT 56
# define N_READ 63
# define N_WRITE 64
# define N_CLOSE 57
#elif defined(__arm__)
# define N_OPENAT 322
# define N_READ 3
# define N_WRITE 4
# define N_CLOSE 6
#elif defined(__x86_64__)
# define N_OPENAT 257
# define N_READ 0
# define N_WRITE 1
# define N_CLOSE 3
#else
# define N_OPENAT 295
# define N_READ 3
# define N_WRITE 4
# define N_CLOSE 6
#endif

static long do_open_rd(const char *p) { return sys3(N_OPENAT, AT_FDCWD, (long)p, O_RDONLY); }
/* v0.1.71: openat WITH the mode argument - the v0.1.67 copy fallback passed
 * only 3 args, so the mode register was garbage; on the field device that
 * made the fallback open fail and LINK_FAIL survived even with shim v2
 * loaded (selfcheck proof: SHIM_LOADED LINK_FAIL). */
static long do_open_wr(const char *p) { return sys4(N_OPENAT, AT_FDCWD, (long)p, O_WRONLY | O_CREAT | O_TRUNC, MODE); }
static long do_read(long fd, void *b, long n) { return sys3(N_READ, fd, (long)b, n); }
static long do_write(long fd, const void *b, long n) { return sys3(N_WRITE, fd, (long)b, n); }
static long do_close(long fd) { return sys3(N_CLOSE, fd, 0, 0); }

/* copy oldpath -> newpath; returns 0 or -errno */
static long copy_file(const char *src, const char *dst)
{
    char buf[8192];
    long si = do_open_rd(src);
    if (si < 0) return si;
    long di = do_open_wr(dst);
    if (di < 0) { do_close(si); return di; }
    long rc = 0;
    for (;;) {
        long r = do_read(si, buf, sizeof buf);
        if (r == 0) break;
        if (r < 0) { rc = r; break; }
        long off = 0;
        while (off < r) {
            long w = do_write(di, buf + off, r - off);
            if (w < 0) { rc = w; goto out; }
            off += w;
        }
    }
out:
    do_close(si);
    do_close(di);
    return rc;
}

extern char *getenv(const char *);

/* linkat() with copy fallback: dpkg's status->status-old backup only needs
 * the CONTENTS at the new name; several Android kernel/proot combinations
 * answer the real hardlink syscall with bogus errnos (EACCES/EEXIST per
 * termux/proot#377 and the v0.1.52-66 field devices). When the syscall
 * fails with one of those, copy instead and report success. Legitimate
 * failures (missing source, unwritable dir) reproduce through the copy and
 * keep their errno. EMUX_LINKFIX_FORCE=1 always copies (self-test hook). */
static int linkat_emux(int dfd1, const char *oldpath, int dfd2, const char *newpath)
{
    long r = linkat_sc(dfd1, (long)oldpath, dfd2, (long)newpath, 0);
    if (r == 0) return 0;
    if (getenv("EMUX_LINKFIX_FORCE")) { /* fall through to copy */ }
    else if (!(r == -EACCES || r == -EEXIST || r == -EPERM || r == -ENOSYS)) {
        *__errno_location() = (int)-r;
        return -1;
    }
    r = copy_file(oldpath, newpath);
    if (r < 0) { *__errno_location() = (int)-r; return -1; }
    return 0;
}

int link(const char *oldpath, const char *newpath)
{
    return linkat_emux(AT_FDCWD, oldpath, AT_FDCWD, newpath);
}

int linkat(int dfd1, const char *oldpath, int dfd2, const char *newpath, int flags)
{
    if (flags != 0) { /* AT_SYMLINK_FOLLOW etc: real syscall only */
        long r = linkat_sc(dfd1, (long)oldpath, dfd2, (long)newpath, flags);
        if (r < 0) { *__errno_location() = (int)-r; return -1; }
        return 0;
    }
    return linkat_emux(dfd1, oldpath, dfd2, newpath);
}

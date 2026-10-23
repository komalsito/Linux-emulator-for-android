/*
 * libemux-linkfix — LD_PRELOAD shim for the proot/Android-kernel hardlink
 * bug, v6 (v0.1.78: version marker greppable from the .so; behaviour = v5:
 * a backup link whose source vanished reports success, "nothing to back up").
 *
 * WHY: dpkg backs up its db with unlink(status-old)+link(status,status-old).
 * Under the bundled proot several Android kernels answer the hardlink
 * syscall with bogus errnos (EEXIST on 3.4 per termux/proot#377, EACCES on
 * the EmuX field device). On arm64 glibc implements link() via linkat(),
 * so v1 (link-only) was a no-op there; v2 intercepted both but its copy
 * fallback called openat() WITHOUT the mode argument (garbage register ->
 * fallback open failed on device); v3 fixes that and adds a load-time
 * self-test independent of any distro package.
 *
 * BEHAVIOUR:
 *  - link()/linkat(flags==0): try the real syscall; on EACCES/EEXIST/EPERM/
 *    ENOSYS copy the contents to the new name and report success (dpkg's
 *    backup only needs contents). Other errnos pass through unchanged.
 *  - EMUX_LINKFIX_FORCE=1: always copy (test hook).
 *  - EMUX_LINKFIX_SELFTEST=1: constructor links a scratch pair in /tmp and
 *    writes REAL_OK / COPY_OK / FAIL_<errno> to /tmp/emux_shimtest.
 *
 * Freestanding (-nostdlib, no DT_NEEDED): raw syscalls via inline asm so ONE
 * binary per arch loads in glibc AND musl guests; only external symbols are
 * __errno_location and getenv, resolved from the preloading process' libc.
 */

#define AT_FDCWD (-100)
#define EPERM 1
#define ENOENT 2
#define EIO 5
#define EACCES 13
#define EEXIST 17
#define EINVAL 22
#define EMLINK 31
#define ENOSYS 38
#define EXDEV 18
#define EDQUOT 122

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000
#define O_APPEND 02000
#define MODE 0644

extern int *__errno_location(void);
extern char *getenv(const char *);

/* v0.1.78: version marker the guest selfcheck greps OUT OF THE .so on disk
 * (pane shows it next to SHIM_LOADED), so a field round can prove which shim
 * generation the device actually carries - several rounds were already lost
 * to stale builds. Exported so the linker keeps it in .rodata/.dynsym.
 * v7 (v0.1.85): bind() selftest - on the field device the app process binds
 * tcp/unix/abstract fine but every guest bind appears to fail; the errno per
 * family is the missing diagnosis, painted into the pane selfcheck. */
const char emux_linkfix_version_tag[] = "emux-linkfix-v9";
const char *emux_linkfix_version(void) { return emux_linkfix_version_tag; }

/* ---- syscall numbers per arch ---- */
#if defined(__aarch64__)
#define SYS_LINKAT 37
#define N_SOCKET 198
#define N_BIND 200
#define N_OPENAT 56
#define N_READ 63
#define N_WRITE 64
#define N_CLOSE 57
#define N_UNLINKAT 35
#elif defined(__arm__)
#define SYS_LINKAT 330
#define N_SOCKET 281
#define N_BIND 282
#define N_OPENAT 322
#define N_READ 3
#define N_WRITE 4
#define N_CLOSE 6
#define N_UNLINKAT 328
#elif defined(__x86_64__)
#define SYS_LINKAT 265
#define N_SOCKET 41
#define N_BIND 49
#define N_OPENAT 257
#define N_READ 0
#define N_WRITE 1
#define N_CLOSE 3
#define N_UNLINKAT 263
#else /* i386 */
#define SYS_LINKAT 303
#define N_SOCKETCALL 102
#define N_OPENAT 295
#define N_READ 3
#define N_WRITE 4
#define N_CLOSE 6
#define N_UNLINKAT 301
#endif

/* ---- raw traps ---- */
#if defined(__aarch64__)
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long x8 __asm__("x8") = SYS_LINKAT;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0" : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
                     : "memory");
    return x0;
}
static long sys2(long n, long a, long b)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x0), "r"(x1) : "memory");
    return x0;
}
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
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long r7 __asm__("r7") = SYS_LINKAT;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    register long r4 __asm__("r4") = e;
    __asm__ volatile("svc 0" : "+r"(r0)
                     : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4)
                     : "memory");
    return r0;
}
static long sys2(long n, long a, long b)
{
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r0), "r"(r1) : "memory");
    return r0;
}
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
static long linkat_sc(long a, long b, long c, long d, long e)
{
    register long rax __asm__("rax") = SYS_LINKAT;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    register long rdx __asm__("rdx") = c;
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    __asm__ volatile("syscall" : "+r"(rax)
                     : "r"(rdi), "r"(rsi), "r"(rdx), "r"(r10), "r"(r8)
                     : "rcx", "r11", "memory");
    return rax;
}
static long sys2(long n, long a, long b)
{
    register long rax __asm__("rax") = n;
    register long rdi __asm__("rdi") = a;
    register long rsi __asm__("rsi") = b;
    __asm__ volatile("syscall" : "+r"(rax) : "r"(rdi), "r"(rsi) : "rcx", "r11", "memory");
    return rax;
}
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
static long sys2(long n, long a, long b)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\t"
                     "movl %1, %%ebx\n\t"
                     "int $0x80\n\t"
                     "popl %%ebx\n\t"
                     : "=a"(r)
                     : "g"(a), "a"(n), "c"(b)
                     : "memory");
    return r;
}
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

/* ---- file helpers ---- */
static long do_open_rd(const char *p) { return sys3(N_OPENAT, AT_FDCWD, (long)p, O_RDONLY); }
/* v0.1.71+: openat WITH the mode argument (v2 passed 3 args; the garbage
 * 4th register made the fallback open fail on the field kernel). */
static long do_open_wr(const char *p) { return sys4(N_OPENAT, AT_FDCWD, (long)p, O_WRONLY | O_CREAT | O_TRUNC, MODE); }
static long do_read(long fd, void *b, long n) { return sys3(N_READ, fd, (long)b, n); }
static long do_write(long fd, const void *b, long n) { return sys3(N_WRITE, fd, (long)b, n); }
static long do_close(long fd) { return sys3(N_CLOSE, fd, 0, 0); }
static long do_unlink(const char *p) { return sys3(N_UNLINKAT, AT_FDCWD, (long)p, 0); }

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

/* ---- interposed entry points ---- */
static int linkat_emux(int dfd1, const char *oldpath, int dfd2, const char *newpath)
{
    long r = linkat_sc(dfd1, (long)oldpath, dfd2, (long)newpath, 0);
    if (r == 0) return 0;
    /* v0.1.75: the field device's bogus errno turned out to be ENOENT
     * (proot dentry poisoning answers the hardlink syscall with "No such
     * file" for paths that exist), on top of EACCES/EEXIST elsewhere. The
     * set is safe to widen because the copy re-derives the TRUE error:
     * a genuinely missing source or unwritable directory fails inside
     * copy_file with its real errno. */
    if (!getenv("EMUX_LINKFIX_FORCE") &&
        !(r == -EACCES || r == -EEXIST || r == -EPERM || r == -ENOSYS ||
          r == -ENOENT || r == -EXDEV || r == -EIO || r == -EMLINK ||
          r == -EDQUOT || r == -EINVAL)) {
        *__errno_location() = (int)-r;
        return -1;
    }
    r = copy_file(oldpath, newpath);
    /* v0.1.77: a vanished source means "nothing to back up". dpkg creates a
     * backup hardlink for EVERY file it replaces; packages left half-installed
     * by the original interrupted transaction (coreutils on the field device:
     * db says installed, files gone) make those links fail with a LEGITIMATE
     * ENOENT and abort the whole upgrade ("Sub-process /usr/bin/dpkg returned
     * an error code (1)" on coreutils_9.4-3ubuntu6.3_arm64.deb). Reporting
     * success for a missing source is exactly dpkg's desired semantics. */
    if (r == -ENOENT) return 0;
    if (r < 0) { *__errno_location() = (int)-r; return -1; }
    return 0;
}

int link(const char *oldpath, const char *newpath)
{
    return linkat_emux(AT_FDCWD, oldpath, AT_FDCWD, newpath);
}

int linkat(int dfd1, const char *oldpath, int dfd2, const char *newpath, int flags)
{
    if (flags != 0) {
        long r = linkat_sc(dfd1, (long)oldpath, dfd2, (long)newpath, flags);
        if (r < 0) { *__errno_location() = (int)-r; return -1; }
        return 0;
    }
    return linkat_emux(dfd1, oldpath, dfd2, newpath);
}

static long do_bind(int fd, const void *sa, long len); /* fwd (v0.1.87) */

/* ---- v0.1.87 (shim v8): bind() logger ----
 * The field device binds fine from nc and from the shim selftest, yet Xvfb
 * dies with "cannot establish any listening sockets". The only witness that
 * knows X's exact failure is X's own bind() - so interpose it (the shim is
 * LD_PRELOADed into every guest process via /etc/ld.so.preload) and append
 * family:errno of EVERY bind to /tmp/emux_bindlog. Passthrough semantics. */
static void append_bindlog(long fam, long err)
{
    char buf[48];
    long o = 0;
    /* v0.1.88: numeric family tag - B2=AF_INET, B1=AF_UNIX, B10=AF_INET6,
     * so the pane shows exactly which family each errno belongs to. */
    buf[o++] = 'B';
    {
        char d[8]; long di = 0, f = fam;
        if (f == 0) d[di++] = '0';
        while (f > 0 && di < 8) { d[di++] = (char)('0' + (f % 10)); f /= 10; }
        while (di > 0 && o < 38) buf[o++] = d[--di];
    }
    buf[o++] = '_';
    if (err == 0) { buf[o++] = 'O'; buf[o++] = 'K'; }
    else {
        char d[8]; long di = 0;
        buf[o++] = 'E';
        while (err > 0 && di < 8) { d[di++] = (char)('0' + (err % 10)); err /= 10; }
        while (di > 0 && o < 40) buf[o++] = d[--di];
    }
    buf[o++] = '\n';
    long fd = sys4(N_OPENAT, AT_FDCWD, (long)"/tmp/emux_bindlog",
                   O_WRONLY | O_CREAT | O_APPEND, MODE);
    if (fd >= 0) { do_write(fd, buf, o); do_close(fd); }
}

int bind(int sockfd, const struct sockaddr *addr, unsigned int addrlen)
{
    long fam = 0;
    if (addr && addrlen >= 2) {
        const unsigned char *p = (const unsigned char *)addr;
        fam = (long)(p[0] | (p[1] << 8));
    }
    long r = do_bind(sockfd, addr, addrlen);
    append_bindlog(fam, r < 0 ? -r : 0);
    if (r < 0) { *__errno_location() = (int)-r; return -1; }
    return 0;
}

/* ---- load-time self-test (v0.1.72) ---- */
static void write_result(const char *tag, long errnov)
{
    char buf[40];
    long o = 0;
    const char *t = tag;
    while (*t && o < 30) buf[o++] = *t++;
    if (errnov >= 0) {
        if (errnov == 0) { if (o < 30) buf[o++] = '0'; }
        else {
            char dig[8]; long di = 0;
            while (errnov > 0 && di < 8) { dig[di++] = (char)('0' + (errnov % 10)); errnov /= 10; }
            while (di > 0 && o < 30) buf[o++] = dig[--di];
        }
    }
    if (o < 30) buf[o++] = '\n';
    /* append: the selftest writes several result lines per load */
    long fd = sys4(N_OPENAT, AT_FDCWD, (long)"/tmp/emux_shimtest", O_WRONLY | O_CREAT | O_APPEND, MODE);
    if (fd >= 0) { do_write(fd, buf, o); do_close(fd); }
}

/* ---- v0.1.85 bind selftest helpers ---- */
static long do_socket(int fam, int type)
{
#ifdef N_SOCKETCALL
    long args[3];
    args[0] = fam; args[1] = type; args[2] = 0;
    return sys2(N_SOCKETCALL, 1, (long)args); /* SYS_SOCKET */
#else
    return sys3(N_SOCKET, fam, type, 0);
#endif
}
static long do_bind(int fd, const void *sa, long len)
{
#ifdef N_SOCKETCALL
    long args[3];
    args[0] = fd; args[1] = (long)sa; args[2] = len;
    return sys2(N_SOCKETCALL, 2, (long)args); /* SYS_BIND */
#else
    return sys3(N_BIND, fd, (long)sa, len);
#endif
}
static int cpystr(char *dst, const char *src, int off)
{
    int i = 0;
    while (src[i] && i < 40) { dst[off + i] = src[i]; i++; }
    dst[off + i] = 0;
    return i;
}
static void bind_selftest(void)
{
    /* inet: 0.0.0.0:6001 */
    long fd = do_socket(2, 1);
    if (fd < 0) { write_result("BINDINETSOCK_", -fd); }
    else {
        char sa[16];
        int i;
        for (i = 0; i < 16; i++) sa[i] = 0;
        sa[0] = 2; sa[1] = 0;          /* AF_INET */
        sa[2] = 0x17; sa[3] = 0x71;    /* 6001 BE */
        long r = do_bind((int)fd, sa, 16);
        write_result("BINDINET_", r < 0 ? -r : 0);
        do_close(fd);
    }
    /* unix path */
    fd = do_socket(1, 1);
    if (fd < 0) { write_result("BINDUNIXSOCK_", -fd); }
    else {
        char sa[64];
        int i;
        for (i = 0; i < 64; i++) sa[i] = 0;
        sa[0] = 1; sa[1] = 0;          /* AF_UNIX */
        int n = cpystr(sa, "/tmp/.emux_bindprobe", 2);
        long r = do_bind((int)fd, sa, 2 + n + 1);
        write_result("BINDUNIXP_", r < 0 ? -r : 0);
        do_unlink("/tmp/.emux_bindprobe");
        do_close(fd);
    }
    /* unix abstract */
    fd = do_socket(1, 1);
    if (fd < 0) { write_result("BINDABSSOCK_", -fd); }
    else {
        char sa[64];
        int i;
        for (i = 0; i < 64; i++) sa[i] = 0;
        sa[0] = 1; sa[1] = 0; sa[2] = 0; /* abstract: leading NUL */
        int n = cpystr(sa, "emuxbindprobe", 3);
        long r = do_bind((int)fd, sa, 3 + n);
        write_result("BINDABS_", r < 0 ? -r : 0);
        do_close(fd);
    }
}

__attribute__((constructor)) static void shim_selftest(void)
{
    if (!getenv || !getenv("EMUX_LINKFIX_SELFTEST")) return;
    const char *src = "/tmp/.emux_lt_src", *dst = "/tmp/.emux_lt_dst";
    long fd = do_open_wr(src);
    if (fd < 0) { write_result("FAIL_SRC", -fd); return; }
    do_write(fd, "x", 1);
    do_close(fd);
    do_unlink(dst);
    long r = linkat_sc(AT_FDCWD, (long)src, AT_FDCWD, (long)dst, 0);
    if (r == 0) {
        write_result("SHIMTEST_REAL_OK", -1);
    } else {
        long rc = copy_file(src, dst);
        if (rc == 0) write_result("SHIMTEST_COPY_OK", -1);
        else write_result("SHIMTEST_FAIL_", -rc);
    }
    do_unlink(src);
    do_unlink(dst);
    bind_selftest();
}

/*
 * emux-rgb565 - freestanding PPM (P3 or P6) -> RGB565 little-endian converter.
 *
 * WHY (v0.1.95): emux-capture's pixel conversion used awk's printf "%c",
 * verified against BUSYBOX awk (Alpine). Ubuntu/Debian ship mawk as awk,
 * and mawk DROPS NUL bytes from %c output - black pixels (0x0000) are two
 * NULs, so the frame stream silently desynced and the Android side never
 * saw a valid frame: all-green diagnostics, zero frames, forever. A native
 * converter is binary-safe on every distro and ~10x faster than text awk.
 *
 * Freestanding (-nostdlib, no DT_NEEDED): raw syscalls, one binary per arch
 * loads in glibc AND musl guests. Reads PPM from stdin (or argv[1] file),
 * writes raw RGB565 LE to stdout. No headers - the script frames them.
 */

#if defined(__aarch64__)
#define N_READ 63
#define N_WRITE 64
#define N_OPENAT 56
#define N_EXIT 94
#elif defined(__arm__)
#define N_READ 3
#define N_WRITE 4
#define N_OPENAT 322
#define N_EXIT 248
#elif defined(__x86_64__)
#define N_READ 0
#define N_WRITE 1
#define N_OPENAT 257
#define N_EXIT 231
#else /* i386 */
#define N_READ 3
#define N_WRITE 4
#define N_OPENAT 295
#define N_EXIT 252
#endif

static long sys3(long n, long a, long b, long c);
static long sys1(long n, long a);

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
static long sys1(long n, long a)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8) : "memory");
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
static long sys1(long n, long a)
{
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7) : "memory");
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
static long sys1(long n, long a)
{
    register long rax __asm__("rax") = n;
    register long rdi __asm__("rdi") = a;
    __asm__ volatile("syscall" : "+r"(rax) : "r"(rdi) : "rcx", "r11", "memory");
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
static long sys1(long n, long a)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\t"
                     "movl %1, %%ebx\n\t"
                     "int $0x80\n\t"
                     "popl %%ebx\n\t"
                     : "=a"(r)
                     : "g"(a), "a"(n)
                     : "memory");
    return r;
}
#endif

/* libgcc's ARM div-by-zero trap references raise(); freestanding stub. */
int raise(int sig) { (void)sig; return 0; }

static long do_read(long fd, void *b, long n) { return sys3(N_READ, fd, (long)b, n); }
static void do_write(long fd, const void *b, long n)
{
    long off = 0;
    while (off < n) {
        long w = sys3(N_WRITE, fd, (long)((const char *)b + off), n - off);
        if (w <= 0) return;
        off += w;
    }
}
static void do_exit(long c) { sys1(N_EXIT, c); }

#define INBUF 65536
static char ib[INBUF];
static long ipos, ilen;   /* consume pointer / valid length */
static long in_fd = 0;
static int eof = 0;

static int fill(void)
{
    if (eof) return 0;
    long r = do_read(in_fd, ib, INBUF);
    if (r <= 0) { eof = 1; ilen = 0; ipos = 0; return 0; }
    ilen = r; ipos = 0;
    return 1;
}
static int getch(void)
{
    if (ipos >= ilen && !fill()) return -1;
    return (unsigned char)ib[ipos++];
}
static void putback(void) { if (ipos > 0) ipos--; }

static int is_space(int c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v'; }

/* next decimal token, skipping whitespace and # comments; -1 on EOF */
static long next_num(int *eoi)
{
    int c;
    long v = 0, got = 0;
    for (;;) {
        c = getch();
        if (c < 0) { *eoi = 1; return -1; }
        if (c == '#') { while ((c = getch()) >= 0 && c != '\n') ; continue; }
        if (!is_space(c)) break;
    }
    while (c >= '0' && c <= '9') { v = v * 10 + (c - '0'); got = 1; c = getch(); }
    if (!got) { *eoi = 1; return -1; }
    if (c >= 0) putback();
    *eoi = 0;
    return v;
}

static char ob[65536];
static long opos;
static void flush_out(void) { if (opos) { do_write(1, ob, opos); opos = 0; } }
static void out2(unsigned v)
{
    if (opos + 2 > (long)sizeof ob) flush_out();
    ob[opos++] = (char)(v & 255);
    ob[opos++] = (char)((v >> 8) & 255);
}

void _start(void)
{
    /* optional input file argument is ignored: the script feeds stdin. */
    int c1 = getch(), c2 = getch();
    int eoi = 0;
    long W, H, MX;
    if (c1 != 'P' || (c2 != '3' && c2 != '6')) do_exit(1);
    W = next_num(&eoi); if (eoi || W <= 0 || W > 32768) do_exit(1);
    H = next_num(&eoi); if (eoi || H <= 0 || H > 32768) do_exit(1);
    MX = next_num(&eoi); if (eoi || MX <= 0) MX = 255;
    long total = W * H;
    if (c2 == '3') {
        long i;
        for (i = 0; i < total; i++) {
            long r = next_num(&eoi), g = next_num(&eoi), b = next_num(&eoi);
            if (eoi) break;
            unsigned r5 = (unsigned)(r * 31 / MX), g6 = (unsigned)(g * 63 / MX),
                     b5 = (unsigned)(b * 31 / MX);
            out2(r5 * 2048 + g6 * 32 + b5);
        }
    } else {
        /* P6: exactly one whitespace after maxval, then raw bytes */
        int c = getch();
        if (c >= 0 && !is_space(c)) putback();
        while (total-- > 0) {
            int r = getch(), g = getch(), b = getch();
            if (r < 0 || g < 0 || b < 0) break;
            unsigned r5 = (unsigned)(r * 31 / (int)MX), g6 = (unsigned)(g * 63 / (int)MX),
                     b5 = (unsigned)(b * 31 / (int)MX);
            out2(r5 * 2048 + g6 * 32 + b5);
        }
    }
    flush_out();
    do_exit(0);
}

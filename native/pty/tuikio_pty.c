/*
 * tuikio_pty — runs one terminal game on a private pseudo-terminal.
 *
 * Packaged in the APK as lib/<abi>/libtuikio_pty.so (an executable, not a JNI
 * library) so that Android extracts it to the read-only nativeLibraryDir, the
 * only place an app targeting API 29+ may execute files from. The app starts it
 * with java.lang.ProcessBuilder; no JNI is involved.
 *
 *   libtuikio_pty.so <cols> <rows> <game-executable> [game args...]
 *
 * stdout carries the raw bytes the game writes to its terminal.
 * stdin carries frames from the app, each: [type u8][length u16 big-endian][payload]
 *   T_INPUT  0x01  payload = bytes typed into the terminal
 *   T_RESIZE 0x02  payload = cols u16be, rows u16be
 *   T_SIGNAL 0x03  payload = one byte: 1 = SIGINT, 2 = SIGHUP, 3 = SIGTERM, 4 = SIGCONT, 5 = SIGSTOP,
 *                  6 = SIGTSTP
 * EOF on stdin means the app went away: the game is sent SIGHUP, then killed.
 * stderr carries the helper's own messages, and a status line "@mode line" or "@mode keys"
 * whenever the game switches its terminal between reading whole lines (canonical mode) and
 * single keys, so the app knows whether an answer needs Enter after it.
 * The helper exits with the game's exit status (128+signal if it was killed).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

enum { T_INPUT = 1, T_RESIZE = 2, T_SIGNAL = 3 };
enum { MAX_FRAME = 4096 };

#ifndef COALESCE_MS
#define COALESCE_MS 4
#endif

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static volatile sig_atomic_t got_term;
static pid_t child = -1;

static void on_term(int sig) { (void)sig; got_term = 1; }

static int write_all(int fd, const unsigned char *p, size_t n) {
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) {
                struct pollfd pf = { .fd = fd, .events = POLLOUT };
                poll(&pf, 1, 100);
                continue;
            }
            return -1;
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static int parse_dim(const char *s, int lo, int hi) {
    char *end = NULL;
    long v = strtol(s, &end, 10);
    if (!end || *end || v < lo || v > hi) return -1;
    return (int)v;
}

/* Ends the game: polite SIGHUP to its process group, SIGKILL after a grace period. */
static int reap(int grace_ms) {
    int status = 0;
    if (child <= 0) return 0;
    kill(-child, SIGHUP);
    kill(child, SIGHUP);
    for (int waited = 0; waited < grace_ms; waited += 20) {
        pid_t r = waitpid(child, &status, WNOHANG);
        if (r == child) return status;
        struct timespec ts = { 0, 20 * 1000 * 1000 };
        nanosleep(&ts, NULL);
    }
    kill(-child, SIGKILL);
    kill(child, SIGKILL);
    waitpid(child, &status, 0);
    return status;
}

/* On a pty master, TCGETS reads the game's side of the terminal. */
static void report_mode(int master) {
    static int last = -1;
    struct termios t;
    if (tcgetattr(master, &t) != 0) return;
    int canon = (t.c_lflag & ICANON) != 0;
    if (canon == last) return;
    last = canon;
    fprintf(stderr, "@mode %s\n", canon ? "line" : "keys");
    fflush(stderr);
}

static int exit_code(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <cols> <rows> <game> [args...]\n", argv[0]);
        return 64;
    }
    int cols = parse_dim(argv[1], 2, 1000);
    int rows = parse_dim(argv[2], 2, 1000);
    if (cols < 0 || rows < 0) {
        fprintf(stderr, "tuikio_pty: bad terminal size\n");
        return 64;
    }

    struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
    int master = -1;
    child = forkpty(&master, NULL, NULL, &ws);
    if (child < 0) {
        perror("tuikio_pty: forkpty");
        return 71;
    }
    if (child == 0) {
        /* The game dies with us, even if we are SIGKILLed. */
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        signal(SIGPIPE, SIG_DFL);
        execv(argv[3], &argv[3]);
        fprintf(stderr, "tuikio_pty: cannot start %s: %s\r\n", argv[3], strerror(errno));
        _exit(127);
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_term;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);

    unsigned char frame[3 + MAX_FRAME];
    size_t have = 0;
    unsigned char out[16384];
    int stdin_open = 1;
    int status = 0;
    int exited = 0;
    long long last_output = 0;

    while (!got_term) {
        struct pollfd pf[2];
        int n = 0;
        pf[n].fd = master; pf[n].events = POLLIN; pf[n].revents = 0; n++;
        if (stdin_open) { pf[n].fd = 0; pf[n].events = POLLIN; pf[n].revents = 0; n++; }
        /* A game often prints its question and then switches modes to read the answer: look
         * again soon after output, so the app learns the new mode while the question is fresh. */
        int r = poll(pf, (nfds_t)n, now_ms() - last_output < 600 ? 20 : 250);
        if (r < 0 && errno != EINTR) break;
        report_mode(master);

        if (r > 0 && (pf[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t got = read(master, out, sizeof out);
            if (got > 0) {
                /* Many games draw a frame as a burst of tiny writes. Collect what follows within
                 * COALESCE_MS and hand the app one chunk: one wakeup, one parse, one redraw
                 * request instead of dozens. Added latency is bounded well under a 60 Hz frame. */
                size_t used = (size_t)got;
                long long deadline = now_ms() + COALESCE_MS;
                while (used < sizeof out) {
                    long long left = deadline - now_ms();
                    if (left <= 0) break;
                    struct pollfd m = { .fd = master, .events = POLLIN, .revents = 0 };
                    if (poll(&m, 1, (int)left) <= 0 || !(m.revents & POLLIN)) break;
                    ssize_t more = read(master, out + used, sizeof out - used);
                    if (more <= 0) break;
                    used += (size_t)more;
                }
                if (write_all(1, out, used) < 0) break; /* app stopped reading */
                last_output = now_ms();
            } else if (got == 0 || (got < 0 && errno != EAGAIN && errno != EINTR)) {
                /* EIO: every slave fd is closed, the game has exited. */
                break;
            }
        }

        if (stdin_open && r > 0 && (pf[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t got = read(0, frame + have, sizeof frame - have);
            if (got <= 0) {
                if (got < 0 && (errno == EINTR || errno == EAGAIN)) continue;
                stdin_open = 0;
                break;
            }
            have += (size_t)got;
            size_t off = 0;
            while (have - off >= 3) {
                unsigned type = frame[off];
                size_t len = ((size_t)frame[off + 1] << 8) | frame[off + 2];
                if (len > MAX_FRAME) { stdin_open = 0; break; } /* corrupt stream */
                if (have - off < 3 + len) break;
                const unsigned char *payload = frame + off + 3;
                if (type == T_INPUT) {
                    write_all(master, payload, len);
                } else if (type == T_RESIZE && len == 4) {
                    struct winsize nw = { 0 };
                    nw.ws_col = (unsigned short)((payload[0] << 8) | payload[1]);
                    nw.ws_row = (unsigned short)((payload[2] << 8) | payload[3]);
                    if (nw.ws_col >= 2 && nw.ws_row >= 2) {
                        ioctl(master, TIOCSWINSZ, &nw); /* kernel delivers SIGWINCH */
                    }
                } else if (type == T_SIGNAL && len == 1) {
                    int sig = payload[0] == 1 ? SIGINT : payload[0] == 2 ? SIGHUP
                            : payload[0] == 3 ? SIGTERM : payload[0] == 4 ? SIGCONT
                            : payload[0] == 5 ? SIGSTOP : payload[0] == 6 ? SIGTSTP : 0;
                    if (sig) kill(-child, sig);
                }
                off += 3 + len;
            }
            memmove(frame, frame + off, have - off);
            have -= off;
        }

        pid_t w = waitpid(child, &status, WNOHANG);
        if (w == child) {
            exited = 1;
            /* Drain what the game printed just before exiting. */
            for (;;) {
                ssize_t got = read(master, out, sizeof out);
                if (got <= 0) break;
                if (write_all(1, out, (size_t)got) < 0) break;
            }
            break;
        }
    }

    if (!exited) status = reap(got_term || !stdin_open ? 400 : 1500);
    close(master);
    return exit_code(status);
}

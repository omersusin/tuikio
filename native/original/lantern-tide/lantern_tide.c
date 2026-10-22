/*
 * Kio: Lantern Tide — an original Tuikio game.
 *
 * Kio the hermit crab scuttles across a night beach collecting glowing lantern shells
 * before the tide rolls in, dodging gulls, then hurries home. Plain ANSI escapes, no
 * curses, so it runs in any xterm-like terminal (and in Tuikio's runtime on Android).
 *
 * Controls: arrows / WASD / HJKL move, P pauses, Q quits. Best score is saved to
 * $HOME/.lantern-tide.
 *
 * Copyright (C) 2026 The Tuikio authors. GPL-3.0-or-later.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <stdarg.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/time.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define W 32
#define H 15
#define MAX_GULLS 4
#define TICK_MS 100

enum { SAND = 0, ROCK, LANTERN, HOME, SEA };

static unsigned char grid[H][W];
static int kx, ky, hearts, score, best, tide_level, tide_timer, tide_period, lanterns_left, level;
static int gx[MAX_GULLS], gy[MAX_GULLS], gdx[MAX_GULLS], ngulls, gull_timer;
static int paused, game_over, won_level, flash, dirty = 1;
/* What each board cell showed last frame, so a frame only rewrites cells that changed. */
static int shown[H][W];
static int full_redraw = 1;
static unsigned rng_state;
static struct termios saved_tio;
static volatile sig_atomic_t resized = 1, stop_requested;
static int term_cols = 80, term_rows = 24;

static unsigned rnd(unsigned n) {
    rng_state = rng_state * 1103515245u + 12345u;
    return (rng_state >> 16) % n;
}

static char outbuf[32768];
static size_t outlen;
static void out(const char *s) {
    size_t n = strlen(s);
    if (outlen + n >= sizeof outbuf) return;
    memcpy(outbuf + outlen, s, n);
    outlen += n;
}
static void outf(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void outf(const char *fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    out(tmp);
}
/* Cell writer that only emits an SGR sequence when the style actually changes. */
static const char *last_sgr;
static void cell(const char *sgr, const char *glyph) {
    if (last_sgr != sgr && (last_sgr == NULL || strcmp(last_sgr, sgr) != 0)) {
        out(sgr);
        last_sgr = sgr;
    }
    out(glyph);
}

static void flush_out(void) {
    size_t off = 0;
    while (off < outlen) {
        ssize_t w = write(1, outbuf + off, outlen - off);
        if (w < 0) { if (errno == EINTR) continue; break; }
        off += (size_t)w;
    }
    outlen = 0;
}

static char save_path[1024];
static void load_best(void) {
    const char *home = getenv("HOME");
    snprintf(save_path, sizeof save_path, "%s/.lantern-tide", home ? home : ".");
    FILE *f = fopen(save_path, "r");
    if (f) { if (fscanf(f, "best %d", &best) != 1) best = 0; fclose(f); }
}
static void save_best(void) {
    if (score <= best) return;
    best = score;
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.tmp", save_path);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "best %d\n", best);
    fclose(f);
    rename(tmp, save_path); /* atomic replace: a crash never leaves a half-written save */
}

static void restore_terminal(void) {
    tcsetattr(0, TCSANOW, &saved_tio);
    write(1, "\033[0m\033[?25h\033[?1049l", 19);
}
static void on_signal(int sig) { (void)sig; stop_requested = 1; }
static void on_winch(int sig) { (void)sig; resized = 1; }

static void build_level(void) {
    memset(grid, SAND, sizeof grid);
    for (int i = 0; i < 10 + level * 2; i++) {
        int x = (int)rnd(W), y = 3 + (int)rnd(H - 5);
        grid[y][x] = ROCK;
    }
    lanterns_left = 0;
    for (int i = 0; i < 6 + level; i++) {
        int x, y, tries = 0;
        do { x = (int)rnd(W); y = 2 + (int)rnd(H - 4); } while (grid[y][x] != SAND && ++tries < 50);
        if (grid[y][x] == SAND) { grid[y][x] = LANTERN; lanterns_left++; }
    }
    grid[H - 1][W / 2] = HOME;
    kx = W / 2; ky = H - 2;
    grid[ky][kx] = SAND;
    tide_level = 0;
    tide_period = 70 - level * 6 < 30 ? 30 : 70 - level * 6;
    tide_timer = tide_period;
    ngulls = level < MAX_GULLS ? level : MAX_GULLS;
    for (int i = 0; i < ngulls; i++) { gx[i] = (int)rnd(W); gy[i] = 2 + i * 3; gdx[i] = rnd(2) ? 1 : -1; }
    gull_timer = 0;
    won_level = 0;
}

static void new_game(void) {
    hearts = 3; score = 0; level = 1; game_over = 0; paused = 0;
    build_level();
}

static void hurt(void) {
    hearts--;
    flash = 4;
    if (hearts <= 0) { game_over = 1; save_best(); }
}

static void move_kio(int dx, int dy) {
    if (paused || game_over) return;
    int nx = kx + dx, ny = ky + dy;
    if (nx < 0 || nx >= W || ny < 0 || ny >= H) return;
    if (grid[ny][nx] == ROCK) return;
    if (ny < tide_level) return; /* the sea pushes back */
    kx = nx; ky = ny;
    dirty = 1;
    if (grid[ky][kx] == LANTERN) { grid[ky][kx] = SAND; lanterns_left--; score += 10 * level; }
    if (grid[ky][kx] == HOME && lanterns_left == 0) {
        score += 25 * level + tide_period;
        won_level = 1;
        level++;
        save_best();
        build_level();
    }
}

static void tick(void) {
    if (paused || game_over) return;
    if (tide_timer % 6 == 0) dirty = 1; /* sea shimmer */
    if (flash > 0) { flash--; dirty = 1; }
    if (--tide_timer <= 0) {
        tide_timer = tide_period;
        dirty = 1;
        if (tide_level < H - 3) {
            tide_level++;
            for (int x = 0; x < W; x++) {
                if (grid[tide_level - 1][x] == LANTERN) lanterns_left--; /* the sea takes it */
                grid[tide_level - 1][x] = SEA;
            }
            if (ky < tide_level) { ky = tide_level; hurt(); }
        }
    }
    if (++gull_timer >= 3) {
        gull_timer = 0;
        if (ngulls > 0) dirty = 1;
        for (int i = 0; i < ngulls; i++) {
            if (gy[i] < tide_level) gy[i] = tide_level;
            gx[i] += gdx[i];
            if (gx[i] <= 0 || gx[i] >= W - 1) gdx[i] = -gdx[i];
            if (rnd(9) == 0) gy[i] += (rnd(2) ? 1 : -1);
            if (gy[i] < tide_level) gy[i] = tide_level;
            if (gy[i] > H - 2) gy[i] = H - 2;
            if (gx[i] == kx && gy[i] == ky) hurt();
        }
    }
    if (lanterns_left < 0) lanterns_left = 0;
}

static const char *KIO_ART[] = {
    "        .---------.",
    "    o o | >_      |",
    "    \\|/ |_________|",
    "  (\\___/)  o   o  ",
    "   (o.o)~~~~~~~~~ ",
};

static void draw(void) {
    int ox = (term_cols - (W + 2)) / 2;
    int oy = (term_rows - (H + 5)) / 2;
    if (ox < 0) ox = 0;
    if (oy < 0) oy = 0;
    if (resized) { out("\033[0m\033[2J"); resized = 0; full_redraw = 1; }
    /* Two header lines exactly as wide as the board (W + 2 columns), padded so stale digits
     * never linger and nothing wraps on a narrow terminal. */
    char right[32];
    int rlen = snprintf(right, sizeof right, "Score %d", score);
    int pad = (W + 2) - 17 - rlen;
    outf("\033[%d;%dH\033[0;1;38;5;209mKio: Lantern Tide\033[0m%*s\033[38;5;222m%s\033[0m",
         oy + 1, ox + 1, pad < 1 ? 1 : pad, "", right);
    outf("\033[%d;%dH\033[0;38;5;204m", oy + 2, ox + 1);
    for (int i = 0; i < 3; i++) out(i < hearts ? "\xe2\x99\xa5 " : "\033[38;5;240m\xe2\x99\xa1 \033[38;5;204m");
    char mid[32];
    int mlen = snprintf(mid, sizeof mid, "Tide %d  \xe2\x9c\xa6 %d", level, lanterns_left) - 2; /* \u2726 is 3 bytes, 1 column */
    rlen = snprintf(right, sizeof right, "Best %d", best);
    pad = (W + 2) - 6 - 1 - mlen - rlen;
    outf("\033[0m %s\033[0m%*s\033[38;5;146m%s\033[0m", mid, pad < 1 ? 1 : pad, "", right);
    if (full_redraw) {
        outf("\033[%d;%dH\033[38;5;61m\xe2\x95\xad", oy + 3, ox + 1);
        for (int x = 0; x < W; x++) out("\xe2\x94\x80");
        out("\xe2\x95\xae");
    }
    for (int y = 0; y < H; y++) {
        if (full_redraw) outf("\033[%d;%dH\033[0;38;5;61m\xe2\x94\x82", oy + 4 + y, ox + 1);
        int cursor_x = full_redraw ? 0 : -1; /* where the terminal cursor is, in board columns */
        for (int x = 0; x < W; x++) {
            int gull = 0;
            for (int i = 0; i < ngulls; i++) if (gx[i] == x && gy[i] == y) gull = 1;
            int near = abs(x - kx) + abs(y - ky) <= 3;
            int sig = (x == kx && y == ky) ? 100 + (flash & 1)
                    : gull ? 200
                    : grid[y][x] == SEA ? 300 + ((x + y + tide_timer / 6) % 3 != 0)
                    : grid[y][x] == HOME ? 400 + (lanterns_left == 0)
                    : grid[y][x] * 10 + near;
            if (!full_redraw && shown[y][x] == sig) continue;
            shown[y][x] = sig;
            if (cursor_x != x) { outf("\033[%d;%dH", oy + 4 + y, ox + 2 + x); last_sgr = NULL; }
            cursor_x = x + 1;
            if (x == kx && y == ky) {
                cell(flash & 1 ? "\033[0;1;38;5;231;48;5;203m" : "\033[0;1;38;5;209;48;5;235m", "K");
            } else if (gull) {
                cell("\033[0;1;38;5;231;48;5;235m", "v");
            } else switch (grid[y][x]) {
                case SEA:
                    if ((x + y + tide_timer / 6) % 3) cell("\033[0;38;5;37;48;5;24m", "\xe2\x89\x88");
                    else cell("\033[0;38;5;80;48;5;24m", "~");
                    break;
                case ROCK: cell("\033[0;38;5;244;48;5;235m", "\xe2\x96\x93"); break;
                case LANTERN: cell("\033[0;1;38;5;221;48;5;235m", "\xe2\x9c\xa6"); break;
                case HOME:
                    cell(lanterns_left == 0 ? "\033[0;1;38;5;48;48;5;235m" : "\033[0;38;5;66;48;5;235m", "\xe2\x8c\x82");
                    break;
                default: {
                    int d = abs(x - kx) + abs(y - ky);
                    if (d <= 3) cell("\033[0;38;5;180;48;5;236m", "\xc2\xb7");
                    else cell("\033[0;38;5;238;48;5;235m", "\xc2\xb7");
                }
            }
        }
        last_sgr = NULL;
        if (full_redraw) outf("\033[%d;%dH\033[0;38;5;61m\xe2\x94\x82", oy + 4 + y, ox + 2 + W);
    }
    if (full_redraw) {
        outf("\033[%d;%dH\033[38;5;61m\xe2\x95\xb0", oy + 4 + H, ox + 1);
        for (int x = 0; x < W; x++) out("\xe2\x94\x80");
        out("\xe2\x95\xaf\033[0m");
    }
    full_redraw = 0;
    /* Status line, centered under the board. Every message fits in W + 2 columns. */
    const char *msg; int mcols;
    if (game_over) { msg = "\033[1;38;5;209mThe tide won! R retry \xc2\xb7 Q quit"; mcols = 30; }
    else if (paused) { msg = "\033[1;38;5;222mPaused \xc2\xb7 P play \xc2\xb7 Q quit"; mcols = 24; }
    else if (lanterns_left == 0) { msg = "\033[38;5;48mAll lanterns lit! Home to \xe2\x8c\x82"; mcols = 27; }
    else { msg = "\033[38;5;146mArrows move \xc2\xb7 P pause \xc2\xb7 Q quit"; mcols = 30; }
    /* Reset colors first: ESC[2K erases with the current background. */
    outf("\033[0m\033[%d;%dH\033[2K\033[%d;%dH%s\033[0m", oy + 5 + H, 1, oy + 5 + H, ox + 1 + ((W + 2) - mcols) / 2, msg);
    flush_out();
}

static void title_screen(void) {
    out("\033[0m\033[2J");
    int oy = (term_rows - 14) / 2, ox = (term_cols - 34) / 2;
    if (oy < 0) oy = 0;
    if (ox < 0) ox = 0;
    for (int i = 0; i < 5; i++) outf("\033[%d;%dH\033[38;5;209m%s", oy + 1 + i, ox + 1, KIO_ART[i]);
    outf("\033[%d;%dH\033[1;38;5;222mK I O :  L A N T E R N   T I D E", oy + 7, ox + 1);
    outf("\033[%d;%dH\033[0;38;5;146mCollect every \033[1;38;5;221m\xe2\x9c\xa6\033[0;38;5;146m before the sea", oy + 9, ox + 1);
    outf("\033[%d;%dHrises, dodge the gulls \033[1;38;5;231mv\033[0;38;5;146m, then", oy + 10, ox + 1);
    outf("\033[%d;%dHscuttle home to \033[1;38;5;48m\xe2\x8c\x82\033[0;38;5;146m.  Best: %d", oy + 11, ox + 1, best);
    outf("\033[%d;%dH\033[1;38;5;209mPress any key to start\033[0m", oy + 13, ox + 1);
    flush_out();
}

static int read_byte(int timeout_ms) {
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(0, &fds);
    struct timeval tv = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
    int r = select(1, &fds, NULL, NULL, &tv);
    if (r <= 0) return -1;
    unsigned char c;
    ssize_t n = read(0, &c, 1);
    if (n == 1) return c;
    return n == 0 ? -2 : -1;
}

/* One key, with arrow-key escape sequences (CSI or SS3 form) folded into hjkl. */
static int read_key(int timeout_ms) {
    int c = read_byte(timeout_ms);
    if (c != 27) return c;
    int c2 = read_byte(30);
    if (c2 != '[' && c2 != 'O') return 27;
    switch (read_byte(30)) {
        case 'A': return 'k';
        case 'B': return 'j';
        case 'C': return 'l';
        case 'D': return 'h';
    }
    return -1;
}

static long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--version") == 0) { puts("Kio: Lantern Tide 1.0"); return 0; }
    if (!isatty(0)) { fputs("lantern-tide needs a terminal\n", stderr); return 1; }
    tcgetattr(0, &saved_tio);
    struct termios raw = saved_tio;
    raw.c_lflag &= ~(tcflag_t)(ICANON | ECHO);
    raw.c_cc[VMIN] = 1;
    raw.c_cc[VTIME] = 0;
    tcsetattr(0, TCSANOW, &raw);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sa.sa_handler = on_winch;
    sigaction(SIGWINCH, &sa, NULL);
    rng_state = (unsigned)time(NULL);
    load_best();
    out("\033[?1049h\033[?25l");

    int started = 0;
    new_game();
    long next_tick = now_ms() + TICK_MS;
    while (!stop_requested) {
        struct winsize ws;
        if (resized && ioctl(1, TIOCGWINSZ, &ws) == 0 && ws.ws_col > 0) { term_cols = ws.ws_col; term_rows = ws.ws_row; }
        if (!started) {
            title_screen();
            resized = 0;
            int k = read_key(500);
            if (k == -2) break;
            if (k == 'q' || k == 'Q') break;
            if (k >= 0) { started = 1; resized = 1; next_tick = now_ms() + TICK_MS; }
            continue;
        }
        if (dirty || resized) { draw(); dirty = 0; }
        long wait = next_tick - now_ms();
        int k = read_key(wait > 0 ? (int)wait : 0);
        if (k == -2) break;
        if (k >= 0) dirty = 1;
        switch (k) {
            case 'k': case 'w': case 'W': case 'K': move_kio(0, -1); break;
            case 'j': case 's': case 'S': case 'J': move_kio(0, 1); break;
            case 'h': case 'a': case 'A': case 'H': move_kio(-1, 0); break;
            case 'l': case 'd': case 'D': case 'L': move_kio(1, 0); break;
            case 'p': case 'P': case ' ': if (!game_over) paused = !paused; break;
            case 'r': case 'R': if (game_over) new_game(); break;
            case 'q': case 'Q': stop_requested = 1; break;
        }
        /* Fixed-rate simulation: input never speeds the tide up or slows it down. */
        long t = now_ms();
        while (t >= next_tick) { tick(); next_tick += TICK_MS; }
    }
    save_best();
    restore_terminal();
    return 0;
}

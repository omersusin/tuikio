/*
 * tuikio_pager: a minimal "more" for games that page their instructions through $PAGER.
 *
 * Android has no pager an app can count on, so the app points PAGER at this executable. It
 * reads text from the named files or standard input and shows it a screen at a time on the
 * game's terminal, reading keys from /dev/tty: space or f for the next page, enter or j for
 * one more line, b for the previous page, q to quit. Without a terminal it copies the text.
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static char *text;
static size_t len, cap;
static size_t *lines; /* start offset of each screen line (long lines wrap) */
static size_t nlines, lcap;

static void slurp(FILE *in)
{
	char buf[8192];
	size_t n;
	while ((n = fread(buf, 1, sizeof buf, in)) > 0) {
		if (len + n + 1 > cap) {
			cap = (len + n + 1) * 2;
			text = realloc(text, cap);
			if (!text)
				exit(1);
		}
		memcpy(text + len, buf, n);
		len += n;
	}
}

static void add_line(size_t off)
{
	if (nlines == lcap) {
		lcap = lcap ? lcap * 2 : 256;
		lines = realloc(lines, lcap * sizeof *lines);
		if (!lines)
			exit(1);
	}
	lines[nlines++] = off;
}

/* Splits the text into screen lines of at most cols columns (UTF-8 aware, tabs to 8). */
static void layout(int cols)
{
	size_t i = 0;
	nlines = 0;
	while (i < len) {
		int col = 0;
		add_line(i);
		while (i < len && text[i] != '\n') {
			unsigned char c = (unsigned char)text[i];
			int w = c == '\t' ? 8 - col % 8 : (c & 0xC0) == 0x80 ? 0 : 1;
			if (col + w > cols)
				break;
			col += w;
			i++;
		}
		if (i < len && text[i] == '\n')
			i++;
	}
}

static void show(int from, int count)
{
	for (int l = from; l < from + count && l < (int)nlines; l++) {
		size_t end = l + 1 < (int)nlines ? lines[l + 1] : len;
		size_t n = end - lines[l];
		if (n && text[lines[l] + n - 1] == '\n')
			n--;
		fwrite(text + lines[l], 1, n, stdout);
		fputs("\r\n", stdout);
	}
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		slurp(stdin);
	} else {
		for (int i = 1; i < argc; i++) {
			FILE *f = fopen(argv[i], "r");
			if (!f) {
				perror(argv[i]);
				continue;
			}
			slurp(f);
			fclose(f);
		}
	}
	int tty = open("/dev/tty", O_RDWR);
	struct winsize ws = {0};
	if (tty < 0 || ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) < 0 || ws.ws_row < 2 || ws.ws_col < 10) {
		fwrite(text, 1, len, stdout);
		return 0;
	}
	struct termios saved, raw;
	tcgetattr(tty, &saved);
	raw = saved;
	raw.c_lflag &= ~(ICANON | ECHO);
	raw.c_cc[VMIN] = 1;
	raw.c_cc[VTIME] = 0;
	tcsetattr(tty, TCSANOW, &raw);

	layout(ws.ws_col);
	int page = ws.ws_row - 1, top = 0;
	fputs("\033[H\033[2J", stdout);
	show(0, page);
	int shown = page;
	while (shown < (int)nlines) {
		/* The longest prompt that fits on one line: a wrapped one can't be cleared. */
		static const char *const prompts[] = {
			"--More-- (%d%%)  space: next  enter: line  b: back  q: quit",
			"--More-- (%d%%)  space: next  q: quit",
			"--More-- (%d%%)",
		};
		char prompt[96];
		for (size_t i = 0; i < sizeof prompts / sizeof *prompts; i++) {
			snprintf(prompt, sizeof prompt, prompts[i], (int)(100L * shown / (long)nlines));
			if (strlen(prompt) < ws.ws_col)
				break;
		}
		printf("\033[7m%.*s\033[0m", ws.ws_col - 1, prompt);
		fflush(stdout);
		char c;
		if (read(tty, &c, 1) != 1)
			break;
		fputs("\r\033[2K", stdout);
		if (c == 'q' || c == 'Q' || c == 3 || c == 27)
			break;
		if (c == '\r' || c == '\n' || c == 'j') {
			show(shown, 1);
			shown++;
		} else if (c == 'b' || c == 'B') {
			top = shown - 2 * page;
			if (top < 0)
				top = 0;
			fputs("\033[H\033[2J", stdout);
			show(top, page);
			shown = top + page;
		} else {
			show(shown, page);
			shown += page;
		}
	}
	fflush(stdout);
	tcsetattr(tty, TCSANOW, &saved);
	return 0;
}

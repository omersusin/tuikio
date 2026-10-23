/*
 * Tuikio: the BSD games page their instructions and lists through $PAGER, which they start with
 * "/bin/sh -c". Android 8 and 9 have no /bin/sh, and games get no shell anyway, so the pager
 * (the app sets PAGER to its own) is run directly; if it can't run, the text is shown as it is.
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: BSD-3-Clause
 */
#ifndef TUIKIO_PAGER_H
#define TUIKIO_PAGER_H

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* In a child whose standard input is the text: becomes the pager, or copies the text out. */
static void __attribute__((unused, noreturn)) tuikio_exec_pager(const char *pager)
{
	char buf[4096];
	ssize_t n;

	execlp(pager, pager, (char *) NULL);
	while ((n = read(0, buf, sizeof buf)) > 0)
		if (write(1, buf, (size_t) n) < 0)
			break;
	_exit(0);
}

static pid_t tuikio_pager_pid;

/* popen(pager, "w") without a shell. */
static FILE * __attribute__((unused)) tuikio_page_open(const char *pager)
{
	int fds[2];

	if (pipe(fds) == -1)
		return NULL;
	fflush(stdout);
	switch (tuikio_pager_pid = fork()) {
	case -1:
		close(fds[0]);
		close(fds[1]);
		return NULL;
	case 0:
		dup2(fds[0], 0);
		close(fds[0]);
		close(fds[1]);
		tuikio_exec_pager(pager);
	}
	close(fds[0]);
	return fdopen(fds[1], "w");
}

static int __attribute__((unused)) tuikio_page_close(FILE *f)
{
	int status = 0;

	fclose(f);
	while (waitpid(tuikio_pager_pid, &status, 0) == -1 && errno == EINTR)
		;
	return status;
}

#endif

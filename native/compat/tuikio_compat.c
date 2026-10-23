/*
 * Linked into every game with -Wl,--wrap=<symbol> (see native/tk.py): small fixes for code
 * written for multi-user Unix systems that would crash or misbehave inside an Android app.
 *
 * - Android's seccomp filter for apps kills a process that makes any uid/gid-changing system
 *   call (SIGSYS), even one that changes nothing. Old games "revoke setgid privileges" with
 *   setregid(getgid(), getgid()) first thing. An app has no privileges to drop, so these
 *   succeed without a system call when they would not change anything, and fail with EPERM
 *   otherwise.
 * - Android has no /etc/passwd, so getpwuid() and getpwnam() return NULL, which many games
 *   dereference. The current user is described from $USER and $HOME instead.
 * - glibc flushes stdout whenever a program reads from stdin, so prompts printed without a
 *   newline appear; musl does not, and text games would wait on a blank screen. Standard
 *   output is made unbuffered before main() runs (curses games buffer their own output).
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <errno.h>
#include <grp.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

__attribute__((constructor)) static void unbuffered_stdout(void)
{
	setvbuf(stdout, NULL, _IONBF, 0);
}

struct passwd *__real_getpwuid(uid_t uid);
struct passwd *__real_getpwnam(const char *name);

static int same_uid(uid_t a) { return a == (uid_t)-1 || a == getuid(); }
static int same_gid(gid_t a) { return a == (gid_t)-1 || a == getgid(); }

static int unchanged(int ok)
{
	if (ok)
		return 0;
	errno = EPERM;
	return -1;
}

int __wrap_setuid(uid_t u) { return unchanged(same_uid(u)); }
int __wrap_setgid(gid_t g) { return unchanged(same_gid(g)); }
int __wrap_seteuid(uid_t u) { return unchanged(same_uid(u)); }
int __wrap_setegid(gid_t g) { return unchanged(same_gid(g)); }
int __wrap_setreuid(uid_t r, uid_t e) { return unchanged(same_uid(r) && same_uid(e)); }
int __wrap_setregid(gid_t r, gid_t e) { return unchanged(same_gid(r) && same_gid(e)); }
int __wrap_setresuid(uid_t r, uid_t e, uid_t s) { return unchanged(same_uid(r) && same_uid(e) && same_uid(s)); }
int __wrap_setresgid(gid_t r, gid_t e, gid_t s) { return unchanged(same_gid(r) && same_gid(e) && same_gid(s)); }
int __wrap_setgroups(size_t n, const gid_t *list) { (void)list; return unchanged(n == 0); }

static struct passwd *self(void)
{
	static struct passwd pw;
	static char name[33];
	const char *user = getenv("USER");
	const char *home = getenv("HOME");
	strncpy(name, user && *user ? user : "player", sizeof name - 1);
	pw.pw_name = name;
	pw.pw_passwd = (char *)"x";
	pw.pw_uid = getuid();
	pw.pw_gid = getgid();
	pw.pw_gecos = name;
	pw.pw_dir = (char *)(home && *home ? home : ".");
	pw.pw_shell = (char *)"/system/bin/sh";
	return &pw;
}

struct passwd *__wrap_getpwuid(uid_t uid)
{
	struct passwd *p = __real_getpwuid(uid);
	return p ? p : uid == getuid() ? self() : NULL;
}

struct passwd *__wrap_getpwnam(const char *name)
{
	struct passwd *p = __real_getpwnam(name);
	if (p || !name)
		return p;
	p = self();
	return strcmp(name, p->pw_name) == 0 ? p : NULL;
}

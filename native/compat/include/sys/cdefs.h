/*
 * <sys/cdefs.h> for code from BSD and glibc systems built against musl, which has no such
 * header: the declaration and attribute macros such code expects.
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef TUIKIO_SYS_CDEFS_H
#define TUIKIO_SYS_CDEFS_H

#ifdef __cplusplus
#define __BEGIN_DECLS extern "C" {
#define __END_DECLS }
#else
#define __BEGIN_DECLS
#define __END_DECLS
#endif

#define __P(args) args
#define __PMT(args) args
#define __CONCAT(x, y) x ## y
#define __STRING(x) #x
#define __THROW
#define __dead __attribute__((__noreturn__))
#define __dead2 __attribute__((__noreturn__))
#define __pure2 __attribute__((__const__))
#define __used __attribute__((__used__))
#define __packed __attribute__((__packed__))
#define __aligned(x) __attribute__((__aligned__(x)))
#define __printflike(f, a) __attribute__((__format__(__printf__, f, a)))
#define __scanflike(f, a) __attribute__((__format__(__scanf__, f, a)))
#define __RCSID(s)
#define __COPYRIGHT(s)
#define __IDSTRING(name, s)
#define __FBSDID(s)
#define __SCCSID(s)

#endif

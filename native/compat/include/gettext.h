/*
 * Stand-in for GNU gettext's gettext.h with native language support off: the games run in
 * English (C.UTF-8), and musl's libintl stubs are not needed.
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef TUIKIO_GETTEXT_H
#define TUIKIO_GETTEXT_H
#define gettext(msgid) ((const char *)(msgid))
#define dgettext(domain, msgid) ((void)(domain), (const char *)(msgid))
#define ngettext(one, many, n) ((n) == 1 ? (const char *)(one) : (const char *)(many))
#define textdomain(domain) ((const char *)(domain))
#define bindtextdomain(domain, dir) ((void)(domain), (const char *)(dir))
#define bind_textdomain_codeset(domain, codeset) ((void)(domain), (const char *)(codeset))
#define gettext_noop(s) s
#endif

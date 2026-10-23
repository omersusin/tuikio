/*
 * <termio.h> (System V terminal interface) for musl, which only has <termios.h>. On Linux the
 * TCGETS/TCSETSW ioctls fill musl's struct termios directly (musl's tcgetattr does just that),
 * so old code that uses struct termio with TCGETA/TCSETAW keeps working.
 *
 * Copyright (C) 2026 The Tuikio authors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef TUIKIO_TERMIO_H
#define TUIKIO_TERMIO_H
#include <termios.h>
#include <sys/ioctl.h>
#define termio termios
#undef TCGETA
#define TCGETA TCGETS
#undef TCSETA
#define TCSETA TCSETS
#undef TCSETAW
#define TCSETAW TCSETSW
#undef TCSETAF
#define TCSETAF TCSETSF
#endif

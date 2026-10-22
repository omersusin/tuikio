# Performance, battery and thermal

Everything here was measured. Where something could not be measured in this environment
(no Android device or emulator was available), it says so and gives the method. Raw outputs
are in `docs/perf/`.

Host: x86_64 container, 4 vCPU, JDK 21. Games: the same static binaries the APK ships (x86_64
build), run through the real PTY helper and frame protocol.

## What a running game costs

`tools/perf/measure_games.py` runs each game for 5 s idle (no input) and 5 s active (a key
every 150 ms). It records terminal output (what the app must parse and draw), how often
output arrives (each arrival wakes the app's reader thread and requests a redraw), and the CPU
time of helper + game.

| Game | Idle B/s | Idle wakeups/s | Active B/s | Active wakeups/s | Active CPU (6 s, incl. 1 s warm-up) |
|---|---:|---:|---:|---:|---:|
| Lantern Tide | 894 | 1.8 | 5,941 | 11.6 | 13 ms |
| 2048 | 0 | 0 | 19,386 | 7.0 | 14 ms |
| vitetris | 0 | 0 | 2,083 | 4.8 | 8 ms |
| robotfindskitten | 0 | 0 | 843 | 5.8 | 28 ms |
| Moon-buggy | 0 | 0 | 449 | 15.0 | 22 ms |
| freesweep | 0 | 0 | 220 | 6.8 | 14 ms |
| nInvaders | 733 | 7.0 | 68,775 | 50.0 | 214 ms |
| greed | 0 | 0 | 169 | 2.2 | 12 ms |
| nsnake | 7,126 | 24.8 | 1,101 | 2.6 | 67 ms |

Most games are silent when idle, which is what lets the phone sleep between keypresses.
Lantern Tide, Tuikio's own game, redraws only cells that changed and skips repeated color
codes. Its idle cost is a 1.8 Hz blink. nsnake animates its title screen, and nInvaders keeps
the aliens moving. That is game behaviour, and it stops completely when the game screen is in
the background (SIGSTOP).

## Optimization: output coalescing (measured)

nInvaders drew each frame as many tiny writes: **339 output wakeups per second** for 69 KB/s.
The PTY helper now collects whatever follows the first chunk within 4 ms and passes it on as
one write:

| Game (active) | Wakeups/s before | After (4 ms) | Change |
|---|---:|---:|---:|
| nInvaders | 339.2 | 50.0 | −85% |
| 2048 | 10.0 | 7.0 | −30% |
| nsnake | 5.0 | 2.8 | −44% |
| nsnake (idle title animation) | 30.3 | 24.8 | −18% |
| Lantern Tide | 11.2 | 11.6 | unchanged (already one write per frame) |

The bytes are identical: nothing is dropped, only batched. The added latency is at most 4 ms,
a quarter of a 60 Hz frame. Windows of 2, 4 and 8 ms measured the same, and 4 ms leaves
headroom for slower phone schedulers (`docs/perf/coalescing-2026-10-22.txt`).

## Terminal engine throughput

`./gradlew :core:terminal:bench` feeds the recorded game streams, plus a synthetic worst case
(full-screen repaints with a 24-bit color change on every cell), through `TerminalEmulator`:

| Stream | MB/s (host JVM, two runs) |
|---|---:|
| 2048 | 84–138 |
| nInvaders | 64–102 |
| vitetris | 40–93 |
| synthetic truecolor repaint | 94–98 |

The heaviest real stream, nInvaders at 69 KB/s, needs at most 0.2% of one host core to parse
(69 KB/s ÷ 40 MB/s). A phone's ART would have to be about 20× slower than this JVM for parsing
to reach 4% of one core. The engine allocates nothing per cell (attributes are packed into one
`Long`).

## Battery and thermal design (in code)

- **Background = zero CPU.** Leaving the game screen sends SIGSTOP and returning sends SIGCONT.
  The process keeps its state but is not scheduled.
- **Redraw only what changed.** Per-row damage tracking means `TerminalView` repaints dirty
  rows into its bitmap once per frame, however many writes arrived.
- **No idle animation.** The cursor stops blinking a few seconds after the last output or
  input, so an idle game requests no frames at all. UI motion is
  reduced or removed when the system animator scale is 0, Battery Saver is on, the device
  reports moderate or worse thermal status, or the user picks Reduce motion.
- **No background network or wakeups.** Nothing is scheduled. The catalog refreshes only on
  request.

## Size

| | Debug | Release (ProGuard 7.10, 5 passes) |
|---|---:|---:|
| APK | 5.04 MB | **4.03 MB** |
| classes.dex | 3,370 KB | **514 KB** (−85%) |

Release APK contents, compressed in the APK:

| Part | Size |
|---|---:|
| Native code, per ABI | arm64 1.14 MB, x86_64 1.11 MB, armv7 1.04 MB |
| Fonts | 361 KB |
| Dex | 235 KB |
| License texts | 68 KB |
| Catalog | 14 KB |
| Resources | 19 KB |

- Unbounded (display font) is subset to Latin scripts (`tools/brand/subset_fonts.py`): 778 →
  493 KB, which saves 152 KB compressed. Characters outside Latin fall back to system fonts.
- Native code dominates. Per-ABI APKs (or an App Bundle on Play) would cut a download to about
  1.9 MB. That is not done yet.
- Installed size is larger than the APK: `extractNativeLibs` unpacks the device's ABI, about
  2.4 MB, which the runtime needs (`docs/RUNTIME.md`).

## Not measured yet (needs a device)

Nothing below has been measured. These are the methods to use:

| What | How |
|---|---|
| Frame time while playing | `adb shell dumpsys gfxinfo dev.tuikio.app framestats` during nInvaders (heaviest) |
| Battery drain per hour of play | `adb shell dumpsys batterystats --reset`, play 30 min unplugged, `dumpsys batterystats dev.tuikio.app` |
| Thermal behaviour | `adb shell dumpsys thermalservice` over a 20 min session. Check that reduced motion engages at THERMAL_STATUS_MODERATE |
| Cold start | `adb shell am start -W -n dev.tuikio.app/.LauncherTuikio` (TotalTime) |
| Runtime on real ABIs | Settings › Diagnostics › Run the runtime check (starts 2048 through the PTY helper) |

Until then, no number in this document should be read as a phone figure. The host numbers
show relative cost and catch regressions.

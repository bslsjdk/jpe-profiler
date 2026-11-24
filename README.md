# JPE Profiler

Independent diagnostic subproject for the Minecraft Java Optimization project.

**Target:** Minecraft 26.3 · NeoForge 26.3.0.115 · Java 25.

JPE Profiler is an observation tool, not an optimizer. It records runtime and public game lifecycle telemetry so optimization work can be based on measured bottlenecks rather than guesses.

## Current telemetry
- JVM heap/non-heap usage and GC count/time deltas, sampled once per second.
- Per-thread CPU-time and allocated-byte deltas, where the runtime exposes those management counters.
- Thread name, id, state and daemon status to help identify worker contention.
- Allocation counters are detected reflectively; runtimes without the optional JDK management extension continue with CPU/heap/GC telemetry.

## Planned telemetry
- Server/client tick and frame wall-time.
- Chunk load/generation/unload and chunk data load/save.
- Entity join/leave, block-neighbor notifications and world lifecycle.
- Optional JFR recording when supported by the runtime.

## Output
`<game directory>/logs/jpe-profiler/`
- `runtime-<session>.csv`: heap, non-heap and GC deltas.
- `threads-<session>.csv`: per-thread CPU and allocation deltas.
- planned `events-<session>.jsonl` and optional `profile-<session>.jfr`.

All event logging must use a bounded queue and a dedicated writer thread. Never perform file I/O on a game/render thread.

## Build
Requires JDK 25:
```bash
gradle build --no-daemon
```

## Important limitations
NeoForge has no public wildcard event for every internal Minecraft operation. Public events alone cannot identify exact allocation hot methods, every world-generation substage, or GPU execution time. Those require carefully scoped instrumentation/JFR/renderer-specific probes. Frame wall-time is not GPU time.

## Project rule
Keep this repository independent from the main optimization mod. The profiler measures and exports evidence; the optimization project consumes that evidence and implements fixes. Do not claim a bottleneck is proven from correlation alone.

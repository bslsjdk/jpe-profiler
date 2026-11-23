# JPE Profiler

Independent diagnostics subproject for the Minecraft Java Optimization project.

**Target:** Minecraft 26.3 · NeoForge 26.3 · Java 25.

JPE Profiler observes the game. It does not patch or optimize Minecraft. Its job is to produce measurements that the separate optimization mod can use.

## Current telemetry

The NeoForge 26.3 game-bus and physical-client event listeners collect:

- **JVM/runtime:** heap and non-heap usage, GC count/time deltas, process/system CPU load, live thread counts.
- **Tick timing:** server tick, client tick, and per-dimension level tick wall time. Each interval exports count, total, maximum, and count over its configured slow threshold.
- **Frame timing:** full frame CPU wall-clock interval measured around RenderFrameEvent.Pre/Post.
- **Render checkpoints:** elapsed wall time between successive RenderLevelStageEvent checkpoints. These are intervals ending at a checkpoint, not isolated GPU timings or exact GPU stage durations.
- **Chunk lifecycle:** full chunk load/unload, new-chunk flag, chunk-data load/save, player watch/sent/unwatch, and chunk ticket-level changes.
- **Entity/world lifecycle:** entity tick completion count, joins (including disk-loaded), leaves, section transitions, transitions that cross chunk boundaries, and level load/unload/save.

High-frequency callbacks update in-memory counters/timers only. CSV formatting and disk I/O run on a dedicated background writer behind a bounded queue. dropped_records makes queue overflow visible instead of silently hiding it.

## Output files

All output is written under the game directory at \`logs/jpe-profiler/\`.

Each launch gets a session timestamp in its filenames:

- \`runtime-<session>.csv\` — JVM, GC, CPU and thread snapshots.
- \`events-<session>.csv\` — per-second tick timings and lifecycle event counts.
- \`level-ticks-<session>.csv\` — per-dimension level tick timing.
- \`render-stages-<session>.csv\` — elapsed intervals ending at render checkpoints.
- \`profile-<session>.jfr\` — optional Java Flight Recorder capture, written on clean shutdown.

See \`docs/TELEMETRY_SCHEMA.md\` for column meanings and interpretation notes.

## Optional method and allocation profiling (JFR)

JFR is opt-in because stack-sampled profiling can add overhead. Add this JVM argument to the Minecraft client or dedicated-server launch:

    -Djpe.profiler.jfr=true

The recording uses the JDK profile configuration and explicitly enables:

- \`jdk.ExecutionSample\` with stack traces, for sampled CPU hot methods.
- \`jdk.ObjectAllocationSample\` with stack traces, for sampled allocation sites.
- \`jdk.ThreadAllocationStatistics\` at one-second intervals.

The recording is capped at 30 minutes and 256 MiB. It is dumped when the game process shuts down cleanly. If JFR is unavailable or cannot start, CSV telemetry continues and a warning is logged.

With JDK 25 command-line tools, inspect a capture with:

    jfr summary profile-<session>.jfr
    jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample profile-<session>.jfr

Allocation sampling is statistical, not a complete count of every object. Use repeated captures and compare them against an unprofiled baseline.

## Install and test

1. Use a Minecraft **26.3** instance with **NeoForge 26.3.0.115 or newer in the 26.3 line**, running **Java 25**.
2. Download the \`jpe-profiler-0.2.0.jar\` test artifact from the latest successful GitHub Actions run (Artifacts section), or build it with \`gradle build --no-daemon\` and take the jar from \`build/libs/\`.
3. Put the jar in that instance's \`mods\` folder. Do not put the sources jar in the mods folder.
4. Launch a client, create a disposable new world, and explore previously unseen terrain for 3–5 minutes. This exercises the new-chunk counter. Also move around an existing area to exercise chunk load/unload and render telemetry.
5. Exit normally so queued CSV rows are flushed. Find the session files in \`<game directory>/logs/jpe-profiler/\`.
6. Repeat with \`-Djpe.profiler.jfr=true\` to create a JFR capture. JFR has additional overhead, so do not use that run as the unprofiled performance baseline.
7. For server-side coverage, repeat on a dedicated NeoForge server and inspect its game directory's output folder.

### First test checklist

- [ ] Mod appears in the loaded-mod list and startup log says JPE Profiler initialized.
- [ ] Four CSV files exist and contain a header plus data rows after normal shutdown.
- [ ] \`runtime\` rows change over time; GC deltas are non-negative and \`dropped_records\` stays at zero.
- [ ] \`events\` shows client/frame activity in a client run.
- [ ] \`new_chunk_loads\` increases while exploring genuinely new terrain on an integrated or dedicated server.
- [ ] \`level-ticks\` contains the active dimension(s).
- [ ] \`render-stages\` contains rows in a client run.
- [ ] JFR run produces a readable \`.jfr\` file and \`jfr summary\` succeeds.
- [ ] Compare a 3–5 minute run with and without the profiler to estimate overhead.

**Important:** CI compilation and packaging do not prove the mod loads or that every event fires in-game. The checklist above requires a real game run. Chunk lifecycle counters do not provide per-stage world-generation timings; that needs additional version-specific instrumentation after the baseline is validated.

## Interpretation limits

- RenderFrameEvent measures CPU-side elapsed wall time around the frame. It is not GPU execution time and can include time spent waiting for the GPU or the operating system.
- Render checkpoint values measure the interval from the preceding checkpoint to the current one. They do not isolate an individual rendering method.
- ChunkEvent.Load is emitted when a full chunk is loaded into a level. \`isNewChunk()\` flags newly generated chunks on the server; it does not time each world-generation stage.
- Entity tick counts describe completed entity-tick events, not per-entity CPU cost. Use JFR execution samples to locate methods consuming CPU time.
- Tick durations are wall-clock intervals. They can include scheduling delays and waits, not just CPU execution.
- Public events cannot expose every internal operation. Treat counters as evidence of workload, not automatic proof of a bottleneck.

## Build

Requires JDK 25:

    gradle build --no-daemon

## Project boundary

Keep this repository independent from the actual optimization mod. The profiler measures and exports evidence; the optimization project consumes that evidence and implements fixes. Do not mix profiler instrumentation with optimization patches.

# JPE Profiler

Independent diagnostic subproject for the Minecraft Java Optimization project.

**Target:** Minecraft 26.3 · NeoForge 26.3 · Java 25.

JPE Profiler is an observation tool, not an optimizer. It records runtime telemetry so optimization work can be based on measured bottlenecks rather than guesses.

## Implemented

- JVM heap used/committed/max and non-heap used, sampled once per second.
- Garbage-collection count and time deltas between samples.
- Bounded in-memory queue and a dedicated background CSV writer.
- Dropped-record counter when the queue is full.
- Shutdown path that stops the sampler and lets the writer drain queued records.

## Not implemented yet

- Server/client tick and frame timing.
- Chunk, entity, block and world lifecycle events.
- Allocation-rate attribution and sampled stack traces.
- JFR recording and GPU timing.

These are planned work, not current features. Do not infer a specific bottleneck from heap size or GC totals alone.

## Output

Files are written under:

`<game directory>/logs/jpe-profiler/`

- `runtime-<session>.csv`

CSV columns:

`timestamp,heap_used,heap_committed,heap_max,nonheap_used,gc_count_delta,gc_time_ms_delta,dropped_records`

All file I/O runs on the writer thread. Game threads do not wait for disk writes.

## Build

Requires JDK 25 and a Gradle installation:

```bash
gradle build --no-daemon
```

## Important limitations

NeoForge has no public wildcard event for every internal Minecraft operation. Public events alone cannot identify exact allocation hot methods, every world-generation substage, or GPU execution time. Those require carefully scoped instrumentation, JFR, or renderer-specific probes. Frame wall-time is not GPU time.

## Project rule

Keep this repository independent from the main optimization mod. The profiler measures and exports evidence; the optimization project consumes that evidence and implements fixes. Do not claim a bottleneck is proven from correlation alone.

# Roadmap

## Phase 1 — reliable baseline telemetry
- [x] Define independent profiler subproject and output contract.
- [x] Sample JVM heap/non-heap usage and GC count/time deltas.
- [x] Use a bounded queue and background CSV writer.
- [x] Drain queued records during normal shutdown.
- [x] Add process/system CPU load and JVM thread counts.

## Phase 2 — game-loop and lifecycle telemetry
- [x] Measure server tick, client tick and per-dimension level tick wall time.
- [x] Measure full-frame CPU wall time; label it separately from GPU time.
- [x] Record render checkpoint intervals without mislabeling them as pure stage/GPU durations.
- [x] Count chunk full-load/unload, generated-chunk flag, data load/save, watch/send/unwatch and ticket changes.
- [x] Count entity tick completion, join/disk-load/leave and section/chunk transitions.
- [x] Count world/level load, unload and save.
- [x] Aggregate hot-path events in memory; write only from a background sampler/writer.
- [ ] Validate mod loading, event registration and output in a real 26.3 client and dedicated server.
- [ ] Measure profiler overhead and queue overflow under synthetic chunk/entity load.

## Phase 3 — method and allocation attribution
- [x] Add opt-in JFR execution samples with stack traces.
- [x] Add opt-in JFR allocation samples with stack traces.
- [x] Add periodic per-thread allocation statistics.
- [ ] Compare JFR overhead against a no-profiler baseline on representative hardware.
- [ ] Produce a repeatable hotspot report from JFR captures.

## Phase 4 — chunk pipeline attribution
- [ ] Determine whether the target runtime exposes reliable per-stage chunk-generation task hooks.
- [ ] Separate generation, lighting, disk I/O and scheduling time where direct instrumentation is supported.
- [ ] Correlate chunk event spikes with JFR execution samples and server tick delays.

## Phase 5 — rendering attribution
- [x] Record client frame wall time and render checkpoint intervals.
- [ ] Add renderer-specific CPU queue/rebuild timings where supported by the selected renderer.
- [ ] Add GPU timer queries only where the active graphics backend exposes reliable asynchronous queries.

## Phase 6 — optimization handoff
- [ ] Export reproducible baseline bundles.
- [ ] Compare repeated runs using medians, percentiles and variance.
- [ ] Implement fixes in the separate main optimization project and re-run the same workload.

## Validation status

A successful Gradle build proves source compilation and packaging only. Event registration, actual game startup, CSV contents, profiler overhead, and JFR output still require an in-game test.

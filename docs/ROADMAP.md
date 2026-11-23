# Roadmap

## Phase 1 — reliable baseline telemetry
- [x] Define independent profiler subproject and output contract.
- [x] Sample JVM heap/non-heap usage and GC count/time deltas.
- [x] Use a bounded queue and background CSV writer.
- [x] Drain queued records during normal shutdown.
- [ ] Compile against the exact NeoForge 26.3 MDK in CI.
- [ ] Verify mod startup and shutdown in a real client and dedicated server.
- [ ] Measure profiler overhead and dropped-record behavior under load.

## Phase 2 — game-loop and event telemetry
- [ ] Add server tick and client tick duration measurements.
- [ ] Add frame wall-time measurements without presenting them as GPU time.
- [ ] Add chunk load/unload and chunk-data load/save event counters.
- [ ] Add entity and world lifecycle event counters.
- [ ] Aggregate high-frequency events; never serialize every event synchronously.

## Phase 3 — attribution
- [ ] Detect JFR support and optionally capture GC, allocation and execution samples.
- [ ] Add sampled stack traces for slow ticks/frames.
- [ ] Attribute allocation pressure to classes/methods where runtime permits.

## Phase 4 — chunk pipeline
- [ ] Time chunk status transitions and task-queue wait/run where supported.
- [ ] Separate generation, lighting, disk I/O and scheduling costs where instrumentation permits.

## Phase 5 — rendering
- [ ] Time CPU render stages and section rebuild queues.
- [ ] Add GPU timer queries only for supported graphics backends.

## Phase 6 — optimization handoff
- [ ] Export reproducible baseline bundles.
- [ ] Compare repeated runs using medians and variance.
- [ ] Implement optimizations in the separate main optimization project.

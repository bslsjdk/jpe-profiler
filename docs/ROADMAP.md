# Roadmap

## Phase 1 — baseline telemetry
- [x] Define independent profiler subproject and output contract.
- [ ] Compile against the exact NeoForge 26.3 MDK.
- [ ] Verify event signatures and physical-client isolation.
- [x] Add bounded asynchronous runtime/thread CSV writer.
- [x] Sample per-thread CPU time and optional allocated-byte deltas.
- [ ] Test on desktop Java 25 and the actual ZL2 runtime.

## Phase 2 — attribution
- [ ] Detect JFR support and capture GC, allocation samples and execution samples when available.
- [ ] Add sampled stack traces for slow ticks/frames.
- [ ] Attribute allocation pressure to classes/methods where runtime permits.

## Phase 3 — chunk pipeline
- [ ] Time chunk status transitions, noise, surface, carvers, features, lighting and task queue wait/run.

## Phase 4 — rendering
- [ ] Time CPU render stages, section rebuild queues and buffer uploads.
- [ ] Add GPU timer queries only for supported graphics backends.

## Phase 5 — optimization handoff
- [ ] Export reproducible baseline bundles.
- [ ] Compare repeated runs using medians.
- [ ] Implement optimizations in the separate main optimization project.

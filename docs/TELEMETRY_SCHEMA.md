# Telemetry schema and interpretation

JPE Profiler writes four CSV streams per session. A session timestamp is included in every filename so repeated runs do not overwrite each other.

## runtime-<session>.csv

- timestamp: wall-clock time when the one-second sample was taken.
- heap_used_bytes, heap_committed_bytes, heap_max_bytes: JVM heap memory usage from MemoryMXBean.
- nonheap_used_bytes: JVM non-heap memory currently used.
- gc_count_delta, gc_time_ms_delta: changes since the previous sample, summed across the JVM's garbage collectors.
- process_cpu_load, system_cpu_load: JVM operating-system bean estimates in the range 0 to 1; -1 means unavailable.
- thread_count, daemon_thread_count: JVM live and daemon thread counts.
- dropped_records: total telemetry rows rejected because the bounded writer queue was full.

## events-<session>.csv

Each duration group contains count, total_ns, max_ns and slow_count:

- server_tick: ServerTickEvent.Pre to ServerTickEvent.Post.
- client_tick: ClientTickEvent.Pre to ClientTickEvent.Post.
- frame: RenderFrameEvent.Pre to RenderFrameEvent.Post. This is CPU-side elapsed wall time, not GPU time.
- level_tick: LevelTickEvent.Pre to LevelTickEvent.Post, aggregated over levels. Per-dimension data is in level-ticks CSV.

slow_count uses a 50 ms threshold for server/client/level ticks and 33.333 ms for full frames. These are useful warning thresholds, not proof that every interval over the threshold caused a visible stutter.

Remaining count columns:

- entity_tick_events: completed EntityTickEvent.Post callbacks.
- entity_joins, entity_disk_loads, entity_leaves: entity lifecycle events.
- entity_section_moves, entity_chunk_moves: section transitions and the subset that crossed a chunk boundary.
- chunk_loads, new_chunk_loads, chunk_unloads: full chunk lifecycle; the new-chunk flag is meaningful only on the server.
- chunk_data_loads, chunk_data_saves: chunk serialized-data callbacks.
- chunk_watches, chunks_sent, chunk_unwatches: server-side player chunk tracking/transmission lifecycle.
- chunk_ticket_changes: server chunk ticket-level changes.
- level_loads, level_unloads, level_saves: level lifecycle and save callbacks.

## level-ticks-<session>.csv

One row per dimension per sampling interval with at least one completed LevelTickEvent pair:

- side_dimension: client: or server: followed by the dimension resource location.
- tick_count, total_ns, max_ns, slow_count: level-tick duration statistics for that dimension in that interval.

## render-stages-<session>.csv

- interval_ending_at: the RenderLevelStageEvent checkpoint at which the interval ended.
- count, total_ns, max_ns, slow_count: elapsed wall time from the preceding render checkpoint to this one. An additional frame_tail_after_last_stage interval captures time between the final stage event and RenderFrameEvent.Post.

These are checkpoint intervals, not exclusive method timings. Work between callbacks can include engine internals, other mods' handlers, scheduling and synchronization. Never interpret them as GPU timer-query results.

## JFR capture

JFR is opt-in. Add -Djpe.profiler.jfr=true to the Java runtime arguments before launching Minecraft. On clean process shutdown, the recording is written as profile-<session>.jfr. It includes sampled execution stacks and sampled allocation stacks, plus per-thread allocation statistics. Use the JDK 25 jfr command to summarize or print the event data.

Allocation samples are statistical. They identify likely allocation sites but are not a complete accounting of every allocation. Execution samples approximate CPU hot methods over the recording window; repeat runs with the same workload before drawing conclusions.

## Safe conclusions

- A large server tick with a large level-tick value suggests the delay happened during level work, but it does not identify the responsible method by itself.
- A large server tick with small level-tick totals suggests work outside the measured level ticks, scheduling, or waits deserve investigation.
- A spike in generated chunk loads indicates new chunks became fully loaded in that interval; pair it with tick timing and JFR samples to investigate generation cost.
- A high entity tick count is workload volume, not a measure of entity AI cost. Use JFR execution samples for method attribution.
- A slow frame is CPU-side elapsed time. Distinguish CPU work from GPU saturation using a renderer/backend-specific GPU profiler; this mod does not yet expose GPU timer queries.

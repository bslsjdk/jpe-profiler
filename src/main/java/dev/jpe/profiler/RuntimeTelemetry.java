package dev.jpe.profiler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.sun.management.OperatingSystemMXBean;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Low-overhead telemetry collector.
 *
 * Event handlers only update counters/timers. Sampling, CSV formatting and disk I/O
 * happen on background threads. The queue is bounded so disk stalls cannot stall
 * the game thread.
 */
public final class RuntimeTelemetry implements AutoCloseable {
    private static final long SLOW_TICK_NS = 50_000_000L;
    private static final long SLOW_FRAME_NS = 33_333_333L;
    private static final int QUEUE_CAPACITY = 8192;

    private final List<GarbageCollectorMXBean> collectors =
            ManagementFactory.getGarbageCollectorMXBeans();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean()
            instanceof OperatingSystemMXBean bean ? bean : null;

    private final Path directory = FMLPaths.GAMEDIR.get().resolve("logs/jpe-profiler");
    private final long sessionId = System.currentTimeMillis();
    private final Path runtimeCsv = directory.resolve("runtime-" + sessionId + ".csv");
    private final Path eventsCsv = directory.resolve("events-" + sessionId + ".csv");
    private final Path levelTicksCsv = directory.resolve("level-ticks-" + sessionId + ".csv");
    private final Path renderStagesCsv = directory.resolve("render-stages-" + sessionId + ".csv");
    private final Path jfrFile = directory.resolve("profile-" + sessionId + ".jfr");

    private final ArrayBlockingQueue<QueuedLine> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final LongAdder droppedRecords = new LongAdder();

    private final WindowMetric serverTick = new WindowMetric();
    private final WindowMetric clientTick = new WindowMetric();
    private final WindowMetric frameTime = new WindowMetric();
    private final WindowMetric levelTickTime = new WindowMetric();

    private final WindowMetric entityTickEvents = new WindowMetric();
    private final WindowMetric entityJoins = new WindowMetric();
    private final WindowMetric entityLoadsFromDisk = new WindowMetric();
    private final WindowMetric entityLeaves = new WindowMetric();
    private final WindowMetric entitySectionMoves = new WindowMetric();
    private final WindowMetric entityChunkMoves = new WindowMetric();

    private final WindowMetric chunkLoads = new WindowMetric();
    private final WindowMetric generatedChunkLoads = new WindowMetric();
    private final WindowMetric chunkUnloads = new WindowMetric();
    private final WindowMetric chunkDataLoads = new WindowMetric();
    private final WindowMetric chunkDataSaves = new WindowMetric();
    private final WindowMetric chunkWatches = new WindowMetric();
    private final WindowMetric chunksSent = new WindowMetric();
    private final WindowMetric chunkUnwatches = new WindowMetric();
    private final WindowMetric chunkTicketChanges = new WindowMetric();

    private final WindowMetric levelLoads = new WindowMetric();
    private final WindowMetric levelUnloads = new WindowMetric();
    private final WindowMetric levelSaves = new WindowMetric();

    private final ConcurrentHashMap<Level, String> levelLabels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowMetric> levelTickByDimension = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowMetric> renderStageIntervals = new ConcurrentHashMap<>();
    private final ClassValue<String> renderStageNames = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return "interval_ending_at_" + type.getSimpleName();
        }
    };

    private final ThreadLocal<TickClock> serverClock = ThreadLocal.withInitial(TickClock::new);
    private final ThreadLocal<TickClock> clientClock = ThreadLocal.withInitial(TickClock::new);
    private final ThreadLocal<LevelClock> levelClock = ThreadLocal.withInitial(LevelClock::new);
    private final ThreadLocal<FrameClock> frameClock = ThreadLocal.withInitial(FrameClock::new);

    private volatile boolean running = true;
    private volatile boolean closed;
    private long lastGcCount = gcCount();
    private long lastGcTime = gcTime();

    private final Thread writer;
    private final Thread sampler;
    private Recording jfrRecording;

    public RuntimeTelemetry() {
        try {
            Files.createDirectories(directory);
            createCsv(runtimeCsv,
                    "timestamp,heap_used_bytes,heap_committed_bytes,heap_max_bytes,nonheap_used_bytes,"
                            + "gc_count_delta,gc_time_ms_delta,process_cpu_load,system_cpu_load,thread_count,daemon_thread_count,dropped_records");
            createCsv(eventsCsv,
                    "timestamp,server_tick_count,server_tick_total_ns,server_tick_max_ns,server_tick_slow_count,"
                            + "client_tick_count,client_tick_total_ns,client_tick_max_ns,client_tick_slow_count,"
                            + "frame_count,frame_total_ns,frame_max_ns,frame_slow_count,"
                            + "level_tick_count,level_tick_total_ns,level_tick_max_ns,level_tick_slow_count,"
                            + "entity_tick_events,entity_joins,entity_disk_loads,entity_leaves,entity_section_moves,entity_chunk_moves,"
                            + "chunk_loads,new_chunk_loads,chunk_unloads,chunk_data_loads,chunk_data_saves,"
                            + "chunk_watches,chunks_sent,chunk_unwatches,chunk_ticket_changes,"
                            + "level_loads,level_unloads,level_saves,dropped_records");
            createCsv(levelTicksCsv, "timestamp,side_dimension,tick_count,total_ns,max_ns,slow_count");
            createCsv(renderStagesCsv, "timestamp,interval_ending_at,count,total_ns,max_ns,slow_count");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create profiler output", e);
        }

        startJfrIfRequested();

        writer = new Thread(this::writeLoop, "jpe-profiler-writer");
        writer.setDaemon(true);
        writer.start();

        sampler = new Thread(this::sampleLoop, "jpe-profiler-sampler");
        sampler.setDaemon(true);
        sampler.start();

        JpeProfiler.LOGGER.info(
                "JPE Profiler recording to {} (Java {}, VM {}, GC {}, JFR {})",
                directory, System.getProperty("java.version"), System.getProperty("java.vm.name"),
                collectors.stream().map(GarbageCollectorMXBean::getName).toList(),
                jfrRecording != null ? "enabled" : "disabled");
    }

    private static void createCsv(Path path, String header) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(header);
            out.newLine();
        }
    }

    private void startJfrIfRequested() {
        if (!Boolean.getBoolean("jpe.profiler.jfr")) {
            return;
        }
        try {
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("JPE Profiler " + sessionId);
            recording.setToDisk(true);
            recording.setMaxAge(Duration.ofMinutes(30));
            recording.setMaxSize(256L * 1024L * 1024L);
            recording.setDumpOnExit(false);
            recording.enable("jdk.ExecutionSample").withStackTrace();
            recording.enable("jdk.ObjectAllocationSample").withStackTrace();
            recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1));
            recording.start();
            jfrRecording = recording;
        } catch (Throwable error) {
            JpeProfiler.LOGGER.warn("JFR could not be started; CSV telemetry will continue", error);
        }
    }

    private void sampleLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                if (running) {
                    sample();
                }
            } catch (InterruptedException ignored) {
                if (!running) break;
            } catch (Throwable error) {
                JpeProfiler.LOGGER.warn("Telemetry sample failed", error);
            }
        }
    }

    private void sample() {
        Instant now = Instant.now();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonheap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();

        long nowGcCount = gcCount();
        long nowGcTime = gcTime();
        long countDelta = delta(lastGcCount, nowGcCount);
        long timeDelta = delta(lastGcTime, nowGcTime);
        lastGcCount = nowGcCount;
        lastGcTime = nowGcTime;

        double processCpu = osBean == null ? -1.0 : osBean.getProcessCpuLoad();
        double systemCpu = osBean == null ? -1.0 : osBean.getCpuLoad();
        var threadBean = ManagementFactory.getThreadMXBean();

        enqueue(Output.RUNTIME, now + "," + heap.getUsed() + "," + heap.getCommitted()
                + "," + heap.getMax() + "," + nonheap.getUsed() + "," + countDelta + ","
                + timeDelta + "," + processCpu + "," + systemCpu + ","
                + threadBean.getThreadCount() + "," + threadBean.getDaemonThreadCount() + ","
                + droppedRecords.sum());

        WindowMetric.Snapshot server = serverTick.takeSnapshot();
        WindowMetric.Snapshot client = clientTick.takeSnapshot();
        WindowMetric.Snapshot frame = frameTime.takeSnapshot();
        WindowMetric.Snapshot levels = levelTickTime.takeSnapshot();

        enqueue(Output.EVENTS, now + ","
                + server.csv() + "," + client.csv() + "," + frame.csv() + "," + levels.csv() + ","
                + entityTickEvents.takeSnapshot().count() + ","
                + entityJoins.takeSnapshot().count() + ","
                + entityLoadsFromDisk.takeSnapshot().count() + ","
                + entityLeaves.takeSnapshot().count() + ","
                + entitySectionMoves.takeSnapshot().count() + ","
                + entityChunkMoves.takeSnapshot().count() + ","
                + chunkLoads.takeSnapshot().count() + ","
                + generatedChunkLoads.takeSnapshot().count() + ","
                + chunkUnloads.takeSnapshot().count() + ","
                + chunkDataLoads.takeSnapshot().count() + ","
                + chunkDataSaves.takeSnapshot().count() + ","
                + chunkWatches.takeSnapshot().count() + ","
                + chunksSent.takeSnapshot().count() + ","
                + chunkUnwatches.takeSnapshot().count() + ","
                + chunkTicketChanges.takeSnapshot().count() + ","
                + levelLoads.takeSnapshot().count() + ","
                + levelUnloads.takeSnapshot().count() + ","
                + levelSaves.takeSnapshot().count() + ","
                + droppedRecords.sum());

        for (Map.Entry<String, WindowMetric> entry : levelTickByDimension.entrySet()) {
            WindowMetric.Snapshot snapshot = entry.getValue().takeSnapshot();
            if (snapshot.count() > 0) {
                enqueue(Output.LEVEL_TICKS, now + "," + csv(entry.getKey()) + "," + snapshot.csv());
            }
        }

        for (Map.Entry<String, WindowMetric> entry : renderStageIntervals.entrySet()) {
            WindowMetric.Snapshot snapshot = entry.getValue().takeSnapshot();
            if (snapshot.count() > 0) {
                enqueue(Output.RENDER_STAGES, now + "," + csv(entry.getKey()) + "," + snapshot.csv());
            }
        }
    }

    private long gcCount() {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionCount())).sum();
    }

    private long gcTime() {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionTime())).sum();
    }

    private static long delta(long before, long after) {
        return before < 0 || after < before ? 0 : after - before;
    }

    private void enqueue(Output output, String line) {
        if (!queue.offer(new QueuedLine(output, line))) {
            droppedRecords.increment();
        }
    }

    private void writeLoop() {
        Map<Output, BufferedWriter> outputs = new EnumMap<>(Output.class);
        try {
            outputs.put(Output.RUNTIME, Files.newBufferedWriter(runtimeCsv, StandardCharsets.UTF_8, StandardOpenOption.APPEND));
            outputs.put(Output.EVENTS, Files.newBufferedWriter(eventsCsv, StandardCharsets.UTF_8, StandardOpenOption.APPEND));
            outputs.put(Output.LEVEL_TICKS, Files.newBufferedWriter(levelTicksCsv, StandardCharsets.UTF_8, StandardOpenOption.APPEND));
            outputs.put(Output.RENDER_STAGES, Files.newBufferedWriter(renderStagesCsv, StandardCharsets.UTF_8, StandardOpenOption.APPEND));

            while (running || !queue.isEmpty()) {
                QueuedLine line = queue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    BufferedWriter output = outputs.get(line.output());
                    output.write(line.value());
                    output.newLine();
                }
                if (line == null || !running) {
                    for (BufferedWriter output : outputs.values()) output.flush();
                }
            }
            for (BufferedWriter output : outputs.values()) output.flush();
        } catch (InterruptedException ignored) {
            // Normal shutdown never interrupts the writer: it must drain the queue.
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            JpeProfiler.LOGGER.error("Profiler writer failed; some telemetry may be missing", error);
        } finally {
            for (BufferedWriter output : outputs.values()) {
                try {
                    output.close();
                } catch (IOException error) {
                    JpeProfiler.LOGGER.warn("Could not close profiler output", error);
                }
            }
        }
    }

    public Path outputDirectory() {
        return directory;
    }

    public void beginServerTick() {
        serverClock.get().start = System.nanoTime();
    }

    public void endServerTick() {
        TickClock clock = serverClock.get();
        if (clock.start != 0L) {
            serverTick.record(System.nanoTime() - clock.start, SLOW_TICK_NS);
            clock.start = 0L;
        }
    }

    public void beginClientTick() {
        clientClock.get().start = System.nanoTime();
    }

    public void endClientTick() {
        TickClock clock = clientClock.get();
        if (clock.start != 0L) {
            clientTick.record(System.nanoTime() - clock.start, SLOW_TICK_NS);
            clock.start = 0L;
        }
    }

    public void beginLevelTick(Level level) {
        LevelClock clock = levelClock.get();
        clock.start = System.nanoTime();
        clock.label = levelLabels.computeIfAbsent(level,
                value -> (value.isClientSide() ? "client:" : "server:") + value.dimension().toString());
    }

    public void endLevelTick() {
        LevelClock clock = levelClock.get();
        if (clock.start == 0L || clock.label == null) return;
        long duration = System.nanoTime() - clock.start;
        levelTickTime.record(duration, SLOW_TICK_NS);
        levelTickByDimension.computeIfAbsent(clock.label, ignored -> new WindowMetric())
                .record(duration, SLOW_TICK_NS);
        clock.start = 0L;
        clock.label = null;
    }

    public void beginFrame() {
        long now = System.nanoTime();
        FrameClock clock = frameClock.get();
        clock.start = now;
        clock.lastStage = now;
    }

    public void endFrame() {
        long now = System.nanoTime();
        FrameClock clock = frameClock.get();
        if (clock.start == 0L) return;
        frameTime.record(now - clock.start, SLOW_FRAME_NS);
        if (clock.lastStage != 0L && now >= clock.lastStage) {
            renderStageIntervals.computeIfAbsent("frame_tail_after_last_stage", ignored -> new WindowMetric())
                    .record(now - clock.lastStage, SLOW_FRAME_NS);
        }
        clock.start = 0L;
        clock.lastStage = 0L;
    }

    public void renderStage(Class<?> stageType) {
        FrameClock clock = frameClock.get();
        long now = System.nanoTime();
        if (clock.start != 0L && clock.lastStage != 0L && now >= clock.lastStage) {
            String stageKey = renderStageNames.get(stageType);
            renderStageIntervals.computeIfAbsent(stageKey, ignored -> new WindowMetric())
                    .record(now - clock.lastStage, SLOW_FRAME_NS);
        }
        if (clock.start != 0L) clock.lastStage = now;
    }

    public void entityTickCompleted() { entityTickEvents.increment(); }
    public void entityJoined(boolean loadedFromDisk) {
        entityJoins.increment();
        if (loadedFromDisk) entityLoadsFromDisk.increment();
    }
    public void entityLeft() { entityLeaves.increment(); }
    public void entityEnteredSection(boolean changedChunk) {
        entitySectionMoves.increment();
        if (changedChunk) entityChunkMoves.increment();
    }

    public void chunkLoaded(boolean newlyGenerated) {
        chunkLoads.increment();
        if (newlyGenerated) generatedChunkLoads.increment();
    }
    public void chunkUnloaded() { chunkUnloads.increment(); }
    public void chunkDataLoaded() { chunkDataLoads.increment(); }
    public void chunkDataSaved() { chunkDataSaves.increment(); }
    public void chunkWatched() { chunkWatches.increment(); }
    public void chunkSent() { chunksSent.increment(); }
    public void chunkUnwatched() { chunkUnwatches.increment(); }
    public void chunkTicketChanged() { chunkTicketChanges.increment(); }

    public void levelLoaded(Level level) {
        levelLoads.increment();
        levelLabels.computeIfAbsent(level,
                value -> (value.isClientSide() ? "client:" : "server:") + value.dimension().toString());
    }
    public void levelUnloaded(Level level) {
        levelUnloads.increment();
        levelLabels.remove(level);
    }
    public void levelSaved() { levelSaves.increment(); }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        sampler.interrupt();

        try {
            sampler.join(3000);
            writer.join(10000);
            if (writer.isAlive()) {
                JpeProfiler.LOGGER.warn("Profiler writer did not drain within 10 seconds; pending rows may be lost");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        Recording recording = jfrRecording;
        if (recording != null) {
            try {
                if (recording.getState() == RecordingState.RUNNING) recording.stop();
                recording.dump(jfrFile);
                JpeProfiler.LOGGER.info("JFR profile saved to {}", jfrFile);
            } catch (Throwable error) {
                JpeProfiler.LOGGER.warn("Could not save JFR recording", error);
            } finally {
                recording.close();
            }
        }
    }

    private enum Output { RUNTIME, EVENTS, LEVEL_TICKS, RENDER_STAGES }
    private record QueuedLine(Output output, String value) {}
    private static final class TickClock { long start; }
    private static final class LevelClock { long start; String label; }
    private static final class FrameClock { long start; long lastStage; }

    private static final class WindowMetric {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final LongAdder slow = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        void increment() { count.increment(); }

        void record(long durationNs, long slowThresholdNs) {
            long safeDuration = Math.max(0L, durationNs);
            count.increment();
            total.add(safeDuration);
            max.accumulateAndGet(safeDuration, Math::max);
            if (safeDuration >= slowThresholdNs) slow.increment();
        }

        Snapshot takeSnapshot() {
            return new Snapshot(count.sumThenReset(), total.sumThenReset(),
                    max.getAndSet(0L), slow.sumThenReset());
        }

        private record Snapshot(long count, long totalNs, long maxNs, long slowCount) {
            String csv() { return count + "," + totalNs + "," + maxNs + "," + slowCount; }
        }
    }
}

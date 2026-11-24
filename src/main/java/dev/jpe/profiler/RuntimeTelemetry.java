package dev.jpe.profiler;

import com.sun.management.ThreadMXBean;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Runtime baseline recorder. Samples heap/GC and per-thread CPU/allocation once per second.
 * Disk I/O runs on a daemon writer thread; game threads never wait for the writer.
 */
public final class RuntimeTelemetry implements AutoCloseable {
    private final List<GarbageCollectorMXBean> collectors =
            ManagementFactory.getGarbageCollectorMXBeans();
    private final java.lang.management.ThreadMXBean standardThreads =
            ManagementFactory.getThreadMXBean();
    private final ThreadMXBean extendedThreads =
            standardThreads instanceof ThreadMXBean bean ? bean : null;
    private final boolean cpuTimeEnabled = enableCpuTime();
    private final boolean allocationEnabled = enableThreadAllocation();
    private final Map<Long, ThreadSnapshot> previousThreads = new HashMap<>();
    private final ArrayBlockingQueue<OutputLine> queue = new ArrayBlockingQueue<>(4096);
    private final LongAdder dropped = new LongAdder();
    private final Path directory = FMLPaths.GAMEDIR.get().resolve("logs/jpe-profiler");
    private final String session = Long.toString(System.currentTimeMillis());
    private final Path csv = directory.resolve("runtime-" + session + ".csv");
    private final Path threadCsv = directory.resolve("threads-" + session + ".csv");
    private volatile boolean running = true;
    private long lastGcCount = gcCount();
    private long lastGcTime = gcTime();
    private final Thread writer;
    private final Thread sampler;

    public RuntimeTelemetry() {
        try {
            Files.createDirectories(directory);
            try (var out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
                 var threadOut = Files.newBufferedWriter(threadCsv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW)) {
                out.write("timestamp,heap_used,heap_committed,heap_max,nonheap_used,gc_count_delta,gc_time_ms_delta,dropped_records");
                out.newLine();
                threadOut.write("timestamp,thread_id,thread_name,state,daemon,cpu_delta_ns,allocated_delta_bytes");
                threadOut.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create profiler output", e);
        }
        writer = new Thread(this::writeLoop, "jpe-profiler-writer");
        writer.setDaemon(true);
        writer.start();
        enqueueMetric("# session java=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name")
                + " gc=" + collectors.stream().map(GarbageCollectorMXBean::getName).toList()
                + " threadCpu=" + cpuTimeEnabled
                + " threadAllocation=" + allocationEnabled);
        sampler = new Thread(this::sampleLoop, "jpe-profiler-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private boolean enableCpuTime() {
        try {
            if (!standardThreads.isThreadCpuTimeSupported()) return false;
            if (!standardThreads.isThreadCpuTimeEnabled()) standardThreads.setThreadCpuTimeEnabled(true);
            return standardThreads.isThreadCpuTimeEnabled();
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return false;
        }
    }

    private boolean enableThreadAllocation() {
        try {
            if (extendedThreads == null || !extendedThreads.isThreadAllocatedMemorySupported()) return false;
            if (!extendedThreads.isThreadAllocatedMemoryEnabled()) {
                extendedThreads.setThreadAllocatedMemoryEnabled(true);
            }
            return extendedThreads.isThreadAllocatedMemoryEnabled();
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return false;
        }
    }

    private void sampleLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                if (!running) break;
                sampleRuntime();
                sampleThreads();
            } catch (InterruptedException ignored) {
                if (!running) break;
            } catch (Throwable error) {
                JpeProfiler.LOGGER.warn("Telemetry sample failed", error);
            }
        }
    }

    private void sampleRuntime() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonheap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long nowGcCount = gcCount();
        long nowGcTime = gcTime();
        long countDelta = delta(lastGcCount, nowGcCount);
        long timeDelta = delta(lastGcTime, nowGcTime);
        lastGcCount = nowGcCount;
        lastGcTime = nowGcTime;

        enqueueMetric(Instant.now() + "," + heap.getUsed() + "," + heap.getCommitted()
                + "," + heap.getMax() + "," + nonheap.getUsed() + ","
                + countDelta + "," + timeDelta + "," + dropped.sum());
    }

    private void sampleThreads() {
        long[] ids = standardThreads.getAllThreadIds();
        ThreadInfo[] infos = standardThreads.getThreadInfo(ids, 0);
        for (int i = 0; i < ids.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null) continue;
            long id = ids[i];
            long cpu = cpuTimeEnabled ? standardThreads.getThreadCpuTime(id) : -1L;
            long allocated = allocationEnabled ? extendedThreads.getThreadAllocatedBytes(id) : -1L;
            ThreadSnapshot previous = previousThreads.put(id, new ThreadSnapshot(cpu, allocated));
            long cpuDelta = previous == null ? -1L : delta(previous.cpuNanos, cpu);
            long allocationDelta = previous == null ? -1L : delta(previous.allocatedBytes, allocated);
            enqueueThread(Instant.now() + "," + id + "," + csv(info.getThreadName()) + ","
                    + info.getThreadState().name() + "," + info.isDaemon() + ","
                    + cpuDelta + "," + allocationDelta);
        }
        previousThreads.keySet().removeIf(id -> standardThreads.getThreadInfo(id, 0) == null);
    }

    private long gcCount() {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionCount())).sum();
    }

    private long gcTime() {
        return collectors.stream().mapToLong(c -> Math.max(0, c.getCollectionTime())).sum();
    }

    private static long delta(long before, long after) {
        return before < 0 || after < before ? -1 : after - before;
    }

    private void enqueueMetric(String line) {
        if (!queue.offer(new OutputLine(false, line))) dropped.increment();
    }

    private void enqueueThread(String line) {
        if (!queue.offer(new OutputLine(true, line))) dropped.increment();
    }

    private void writeLoop() {
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
             BufferedWriter threadOut = Files.newBufferedWriter(threadCsv, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                OutputLine line = queue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    BufferedWriter target = line.thread ? threadOut : out;
                    target.write(line.value);
                    target.newLine();
                    target.flush();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            JpeProfiler.LOGGER.error("Profiler writer failed", e);
        }
    }

    private static String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    public Path outputDirectory() { return directory; }

    @Override
    public void close() {
        running = false;
        sampler.interrupt();
        writer.interrupt();
    }

    private record OutputLine(boolean thread, String value) {}
    private record ThreadSnapshot(long cpuNanos, long allocatedBytes) {}
}

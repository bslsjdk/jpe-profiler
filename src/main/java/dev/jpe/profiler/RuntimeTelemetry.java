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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Runtime-only baseline recorder. Samples once per second.
 * Disk I/O runs on a daemon writer thread; game threads never wait for the writer.
 */
public final class RuntimeTelemetry implements AutoCloseable {
    private final List<GarbageCollectorMXBean> collectors =
            ManagementFactory.getGarbageCollectorMXBeans();
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2048);
    private final LongAdder dropped = new LongAdder();
    private final Path directory = FMLPaths.GAMEDIR.get().resolve("logs/jpe-profiler");
    private final Path csv = directory.resolve("runtime-" + System.currentTimeMillis() + ".csv");
    private volatile boolean running = true;
    private long lastGcCount = gcCount();
    private long lastGcTime = gcTime();
    private long lastSampleNanos = System.nanoTime();
    private final Thread writer;

    public RuntimeTelemetry() {
        try {
            Files.createDirectories(directory);
            try (var out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW)) {
                out.write("timestamp,heap_used,heap_committed,heap_max,nonheap_used,gc_count_delta,gc_time_ms_delta,dropped_records");
                out.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create profiler output", e);
        }
        writer = new Thread(this::writeLoop, "jpe-profiler-writer");
        writer.setDaemon(true);
        writer.start();
        enqueue("# session java=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name")
                + " gc=" + collectors.stream().map(GarbageCollectorMXBean::getName).toList());
        Thread sampler = new Thread(this::sampleLoop, "jpe-profiler-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void sampleLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                sample();
            } catch (InterruptedException ignored) {
                if (!running) break;
            } catch (Throwable error) {
                JpeProfiler.LOGGER.warn("Telemetry sample failed", error);
            }
        }
    }

    private void sample() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonheap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long nowGcCount = gcCount();
        long nowGcTime = gcTime();
        long countDelta = delta(lastGcCount, nowGcCount);
        long timeDelta = delta(lastGcTime, nowGcTime);
        lastGcCount = nowGcCount;
        lastGcTime = nowGcTime;
        lastSampleNanos = System.nanoTime();

        enqueue(Instant.now() + "," + heap.getUsed() + "," + heap.getCommitted()
                + "," + heap.getMax() + "," + nonheap.getUsed() + ","
                + countDelta + "," + timeDelta + "," + dropped.sum());
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

    private void enqueue(String line) {
        if (!queue.offer(line)) dropped.increment();
    }

    private void writeLoop() {
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    out.write(line);
                    out.newLine();
                }
                out.flush();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            JpeProfiler.LOGGER.error("Profiler writer failed", e);
        }
    }

    public Path outputDirectory() { return directory; }

    @Override
    public void close() {
        running = false;
        writer.interrupt();
    }
}

package com.medizon.monitoring;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What the container itself is using against the limits the host (e.g. Render: 512 MB, 0.1 CPU) puts on it, read from
 * the Linux cgroup files. The JVM's own numbers can't show this: heap is only part of the memory the process takes, and
 * the JVM's CPU figure is a share of a whole core, not of the fraction of a core the plan allows.
 * Works with cgroup v2 and v1; on Windows/macOS or outside a container it reports nothing.
 */
@Component
@Slf4j
public class ContainerStats {

    private static final Path V2 = Path.of("/sys/fs/cgroup");
    private static final Path V1_MEM = Path.of("/sys/fs/cgroup/memory");
    private static final Path V1_CPU = Path.of("/sys/fs/cgroup/cpu,cpuacct");
    private static final long SAMPLE_SECONDS = 10;
    /** cgroup v1 reports "no limit" as a huge number. */
    private static final long NO_LIMIT = 1L << 60;

    private final ScheduledExecutorService sampler;
    private final boolean v2 = Files.exists(V2.resolve("cgroup.controllers"));

    private volatile long lastUsageMicros = -1, lastAt;
    private volatile double cpuOfLimit = -1;          // 0..1+ share of the CPU quota used over the last sample
    private volatile double cpuOfLimitPeak = 0;

    public ContainerStats() {
        if (memoryCurrent() < 0) {             // not in a Linux cgroup: nothing to sample
            sampler = null;
            return;
        }
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "container-stats");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(this::sample, 0, SAMPLE_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (sampler != null) sampler.shutdownNow();
    }

    private void sample() {
        try {
            long usage = cpuUsageMicros();
            long now = System.nanoTime();
            double cores = cpuLimitCores();
            if (usage >= 0 && lastUsageMicros >= 0 && cores > 0) {
                double elapsedMicros = (now - lastAt) / 1000.0;
                cpuOfLimit = (usage - lastUsageMicros) / elapsedMicros / cores;
                cpuOfLimitPeak = Math.max(cpuOfLimitPeak, cpuOfLimit);
            }
            lastUsageMicros = usage;
            lastAt = now;
        } catch (RuntimeException e) {
            log.debug("Container CPU sample failed", e);
        }
    }

    public boolean isAvailable() { return memoryCurrent() >= 0; }

    /** Readings for the dashboard; values are -1 when unknown (no limit set, or not in a container). */
    public Map<String, Double> read() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("memUsed", (double) memoryCurrent());
        out.put("memLimit", (double) memoryLimit());
        out.put("memPeak", (double) memoryPeak());
        out.put("rss", (double) processRss());
        out.put("cpuLimit", cpuLimitCores());
        out.put("cpuOfLimit", cpuOfLimit);
        out.put("cpuOfLimitPeak", cpuOfLimitPeak);
        long[] throttle = throttling();
        out.put("throttledPeriods", (double) throttle[0]);
        out.put("totalPeriods", (double) throttle[1]);
        out.put("throttledSeconds", throttle[2] / 1_000_000.0);
        return out;
    }

    // ------------------------------------------------------------------ memory

    /** Memory charged to the container right now (includes page cache the kernel can reclaim). */
    long memoryCurrent() {
        return v2 ? readLong(V2.resolve("memory.current")) : readLong(V1_MEM.resolve("memory.usage_in_bytes"));
    }

    long memoryLimit() {
        long l = v2 ? readLong(V2.resolve("memory.max")) : readLong(V1_MEM.resolve("memory.limit_in_bytes"));
        return l >= NO_LIMIT ? -1 : l;
    }

    long memoryPeak() {
        return v2 ? readLong(V2.resolve("memory.peak")) : readLong(V1_MEM.resolve("memory.max_usage_in_bytes"));
    }

    /** Resident memory of this Java process (heap + metaspace + threads + native), from /proc. */
    long processRss() {
        for (String line : lines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) {
                String kb = line.replaceAll("\\D", "");
                return kb.isEmpty() ? -1 : Long.parseLong(kb) * 1024;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ CPU

    /** CPU the plan allows, in cores (0.1 = a tenth of one core); -1 when unlimited or unknown. */
    double cpuLimitCores() {
        if (v2) {
            List<String> l = lines(V2.resolve("cpu.max"));          // "10000 100000" or "max 100000"
            if (l.isEmpty()) return -1;
            String[] p = l.get(0).trim().split("\\s+");
            if (p.length < 2 || "max".equals(p[0])) return -1;
            return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
        }
        long quota = readLong(V1_CPU.resolve("cpu.cfs_quota_us")), period = readLong(V1_CPU.resolve("cpu.cfs_period_us"));
        return quota <= 0 || period <= 0 ? -1 : (double) quota / period;
    }

    /** Total CPU time the container has used, in microseconds. */
    long cpuUsageMicros() {
        if (v2) return stat(V2.resolve("cpu.stat"), "usage_usec");
        long ns = readLong(V1_CPU.resolve("cpuacct.usage"));
        return ns < 0 ? -1 : ns / 1000;
    }

    /** [periods throttled, periods total, microseconds throttled] since the container started. */
    long[] throttling() {
        if (v2) {
            Path f = V2.resolve("cpu.stat");
            return new long[]{stat(f, "nr_throttled"), stat(f, "nr_periods"), stat(f, "throttled_usec")};
        }
        Path f = V1_CPU.resolve("cpu.stat");
        long ns = stat(f, "throttled_time");
        return new long[]{stat(f, "nr_throttled"), stat(f, "nr_periods"), ns < 0 ? -1 : ns / 1000};
    }

    // ------------------------------------------------------------------ files

    private static long stat(Path file, String key) {
        for (String line : lines(file)) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2 && p[0].equals(key)) return parse(p[1]);
        }
        return -1;
    }

    private static long readLong(Path file) {
        List<String> l = lines(file);
        return l.isEmpty() ? -1 : parse(l.get(0).trim());
    }

    private static long parse(String s) {
        if ("max".equals(s)) return Long.MAX_VALUE;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException | SecurityException e) {
            return List.of();
        }
    }
}

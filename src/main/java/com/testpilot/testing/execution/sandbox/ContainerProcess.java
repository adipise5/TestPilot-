package com.testpilot.testing.execution.sandbox;

import org.springframework.stereotype.Component;
import java.io.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Only Docker CLI commands are accepted; repository commands never execute on the host. */
@Component
public class ContainerProcess {
    public record Result(Integer exitCode, boolean timedOut, boolean cancelled, String output) {}
    public Result run(List<String> command, Duration timeout, BooleanSupplier cancelled, Runnable heartbeat) throws IOException, InterruptedException {
        if (command.size() < 2 || !command.get(0).equals("docker") || !List.of("run", "rm").contains(command.get(1))) {
            throw new IllegalArgumentException("Only scoped Docker operations are allowed");
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (captured) { captured.write(buffer, 0, Math.min(read, Math.max(0, 2_000_000 - captured.size()))); }
                }
            } catch (IOException ignored) { }
        }, "container-output");
        drain.setDaemon(true); drain.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextHeartbeat = 0;
        boolean timedOut = false, wasCancelled = false;
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                wasCancelled = cancelled.getAsBoolean();
                timedOut = System.nanoTime() >= deadline;
                if (wasCancelled || timedOut) break;
                if (System.nanoTime() >= nextHeartbeat) {
                    heartbeat.run(); nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                }
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
            drain.join(1000);
        }
        synchronized (captured) {
            return new Result(process.isAlive() ? null : process.exitValue(), timedOut, wasCancelled,
                    captured.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}

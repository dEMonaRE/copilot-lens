package io.copilotlens.util;

import io.copilotlens.config.CopilotLensConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Opsiyonel debug log dosyasi: ~/.copilot-lens/debug.log.
 *
 * Aktivasyon (ilk eşleşen kazanır):
 *   1. JVM system property  -Dcopilot-lens.debug=true
 *   2. Environment variable COPILOT_LENS_DEBUG=true|1|yes
 *   3. config.properties    debug.enabled=true
 *
 * Normal calismada dosya acilmaz; sifir performans etkisi.
 * Aktifken mesajlar append edilir; dosya ~2 MB'a ulasirsa rotate edilir.
 *
 * Kullanim:
 *   DebugLog.info("parsed 123 requests");
 *   DebugLog.warn("token field missing for line " + i);
 *   DebugLog.error("parse failed", e);
 */
public final class DebugLog {

    private static final Path FILE = CopilotLensConfig.getHomeDir().resolve("debug.log");
    private static final long MAX_BYTES = 2 * 1024 * 1024L; // 2 MB
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile Boolean enabled = null;

    private DebugLog() {}

    public static boolean isEnabled() {
        if (enabled != null) return enabled;
        // System property
        if (Boolean.getBoolean("copilot-lens.debug")) {
            enabled = Boolean.TRUE;
            return true;
        }
        // Env var
        String env = System.getenv("COPILOT_LENS_DEBUG");
        if (env != null && (env.equalsIgnoreCase("true") || env.equals("1") || env.equalsIgnoreCase("yes"))) {
            enabled = Boolean.TRUE;
            return true;
        }
        // Config
        try {
            if (CopilotLensConfig.load().getBool("debug.enabled")) {
                enabled = Boolean.TRUE;
                return true;
            }
        } catch (Exception ignored) {}
        enabled = Boolean.FALSE;
        return false;
    }

    /** Test/setup icin enable'i zorla. Normal kod yolundan cagrilmamali. */
    public static void setEnabled(boolean v) {
        enabled = v;
        if (v) ensureParent();
    }

    public static void info(String msg)  { write("INFO",  msg, null); }
    public static void warn(String msg)  { write("WARN",  msg, null); }
    public static void error(String msg) { write("ERROR", msg, null); }
    public static void error(String msg, Throwable t) { write("ERROR", msg, t); }

    private static void write(String level, String msg, Throwable t) {
        if (!isEnabled()) return;
        LOCK.lock();
        try {
            rotateIfNeeded();
            StringBuilder sb = new StringBuilder(128);
            sb.append(TS.format(LocalDateTime.now()))
              .append(" [").append(level).append("] ")
              .append(msg == null ? "(null)" : msg)
              .append(System.lineSeparator());
            if (t != null) {
                sb.append("    cause: ").append(t.getClass().getName())
                  .append(": ").append(t.getMessage()).append(System.lineSeparator());
                for (StackTraceElement el : t.getStackTrace()) {
                    sb.append("      at ").append(el).append(System.lineSeparator());
                    if (sb.length() > 4096) break; // stack trace'i kısa tut
                }
            }
            Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Loglama logu yazarken patlarsa sessizce yut
        } finally {
            LOCK.unlock();
        }
    }

    private static void ensureParent() {
        try { Files.createDirectories(FILE.getParent()); } catch (IOException ignored) {}
    }

    private static void rotateIfNeeded() {
        try {
            ensureParent();
            if (Files.exists(FILE) && Files.size(FILE) > MAX_BYTES) {
                Path bak = FILE.resolveSibling("debug.log.old");
                try { Files.deleteIfExists(bak); } catch (IOException ignored) {}
                Files.move(FILE, bak);
            }
        } catch (IOException ignored) {}
    }
}
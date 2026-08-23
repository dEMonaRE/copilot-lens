package io.copilotlens.detector;

import io.copilotlens.config.CopilotLensConfig;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * IDE log dosyalarını otomatik tespit eder.
 * Önce ~/.copilot-lens/config.properties'ten override okur,
 * bulamazsa Windows varsayılan konumlarını dener.
 */
public class IdeDetector {

    private final CopilotLensConfig config;

    public IdeDetector() {
        this.config = CopilotLensConfig.load();
    }

    public Optional<Path> findVsCodeLog() {
        String pattern = config.get("log.vscode");
        return findLatestMatching(pattern);
    }

    public Optional<Path> findIntelliJLog() {
        String pattern = config.get("log.idea");
        return findLatestMatching(pattern);
    }

    public Optional<Path> findAnyLog() {
        Optional<Path> idea = findIntelliJLog();
        Optional<Path> vscode = findVsCodeLog();
        return Stream.of(idea, vscode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
    }

    /**
     * Glob pattern'i expand edip eşleşen en yeni dosyayı bulur.
     * ** ile iç içe klasörler desteklenir.
     */
    private Optional<Path> findLatestMatching(String pattern) {
        if (pattern == null || pattern.isEmpty()) return Optional.empty();

        // Pattern'i base path + filename glob olarak ayır
        int globStart = pattern.indexOf("**");
        Path basePath;
        String filenamePattern;
        if (globStart >= 0) {
            String before = pattern.substring(0, globStart);
            int lastSep = Math.max(before.lastIndexOf('/'), before.lastIndexOf('\\'));
            if (lastSep >= 0) {
                basePath = Paths.get(before.substring(0, lastSep));
            } else {
                basePath = Paths.get(System.getProperty("user.home"));
            }
            // Filename glob: ** kısmından sonra gelen path
            String after = pattern.substring(globStart + 2);
            // Strip leading separator
            if (after.startsWith("/") || after.startsWith("\\")) after = after.substring(1);
            // Take last component as filename glob
            int lastFileSep = Math.max(after.lastIndexOf('/'), after.lastIndexOf('\\'));
            filenamePattern = lastFileSep >= 0 ? after.substring(lastFileSep + 1) : after;
        } else {
            int lastSep = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
            if (lastSep >= 0) {
                basePath = Paths.get(pattern.substring(0, lastSep));
                filenamePattern = pattern.substring(lastSep + 1);
            } else {
                basePath = Paths.get(".");
                filenamePattern = pattern;
            }
        }

        if (!Files.exists(basePath)) {
            return fallbackFor(pattern);
        }

        // Convert glob to regex for filename matching
        String regex = globToRegex(filenamePattern);

        try (Stream<Path> stream = Files.walk(basePath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.matches(regex);
                    })
                    .filter(p -> {
                        try { return Files.size(p) > 0; }
                        catch (Exception e) { return false; }
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Basit glob -> regex dönüşümü.
     * * -> [^/]* , ? -> [^/] , ** -> .* (recursive)
     */
    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.' -> sb.append("\\.");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                        sb.append(c);
                    } else {
                        sb.append("\\").append(c);
                    }
                }
            }
        }
        sb.append("$");
        return sb.toString();
    }

    /**
     * Bilinen IDE default path'lerini dener (config boşsa fallback).
     */
    private Optional<Path> fallbackFor(String pattern) {
        String home = System.getProperty("user.home");
        if (pattern.contains("Code/logs")) {
            Path dir = Paths.get(home, "AppData", "Roaming", "Code", "logs");
            if (!Files.isDirectory(dir)) return Optional.empty();
            try (Stream<Path> stream = Files.walk(dir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith("output_logging"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            } catch (Exception e) { return Optional.empty(); }
        }
        if (pattern.contains("JetBrains")) {
            Path dir = Paths.get(home, "AppData", "Local", "JetBrains");
            if (!Files.isDirectory(dir)) return Optional.empty();
            try (Stream<Path> stream = Files.walk(dir)) {
                return stream
                        .filter(p -> p.getFileName().toString().equals("idea.log"))
                        .filter(p -> p.toString().contains("log"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            } catch (Exception e) { return Optional.empty(); }
        }
        return Optional.empty();
    }
}

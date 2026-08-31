package io.copilotlens;

import io.copilotlens.analyzer.Discoverer;
import io.copilotlens.analyzer.Discoverer.Finding;
import io.copilotlens.analyzer.EffectivenessScorer;
import io.copilotlens.analyzer.IncrementalState;
import io.copilotlens.analyzer.StatsAggregator;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TokenCounter;
import io.copilotlens.analyzer.TrendAggregator;
import io.copilotlens.analyzer.TrendAggregator.Period;
import io.copilotlens.analyzer.TrendAggregator.TrendPoint;
import io.copilotlens.config.CopilotLensConfig;
import io.copilotlens.detector.IdeDetector;
import io.copilotlens.detector.McpScanner;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.parser.IntelliJParser;
import io.copilotlens.parser.LogParser;
import io.copilotlens.parser.VsCodeForkParser;
import io.copilotlens.parser.VsCodeParser;
import io.copilotlens.parser.VsCodeSessionDb;
import io.copilotlens.parser.VsCodeSessionJson;
import io.copilotlens.parser.VsCodeSessionJsonl;
import io.copilotlens.reporter.CliReporter;
import io.copilotlens.reporter.HtmlReporter;
import io.copilotlens.reporter.JsonReporter;
import io.copilotlens.snapshot.Snapshot;
import io.copilotlens.snapshot.SnapshotStore;
import io.copilotlens.util.DebugLog;
import io.copilotlens.watch.LogWatcher;

import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * copilot-lens CLI entry point.
 *
 * Commands (RTK feature parity):
 *   copilot-lens                  One-shot report (console + HTML)
 *   copilot-lens gain             Usage summary
 *   copilot-lens gain --history   Daily history trend
 *   copilot-lens discover         Most expensive patterns
 *   copilot-lens watch            Live terminal dashboard
 *   copilot-lens export json      JSON export
 *   copilot-lens report           HTML-only report
 *   copilot-lens snapshot         Persist today's totals
 *   copilot-lens trend            ASCII trend from snapshots
 *   copilot-lens init             Write default project config
 *   copilot-lens install          Copy wrapper to ~/.local/bin
 *
 * Options:
 *   --ide=vscode|idea|auto   IDE selection (default: auto)
 *   --log=<path>             Manual log file
 *   --period=daily|weekly|monthly   Trend grouping (default: daily)
 *   --days=N                 How many recent buckets to show (default: 30)
 *   --no-ansi                Disable color
 *   --help                   Help
 */
public class Main {

    public static void main(String[] args) {
        try {
            Args params = Args.parse(args);

            if (params.help) {
                printHelp();
                return;
            }

            DebugLog.info("command=" + params.command + " ide=" + params.ide +
                    " log=" + (params.logFile == null ? "<auto>" : params.logFile));

            switch (params.command) {
                case LOG, GAIN, REPORT -> runReport(params);
                case WATCH -> runWatch(params);
                case DISCOVER -> runDiscover(params);
                case EXPORT -> runExport(params);
                case INIT -> runInit(params);
                case SNAPSHOT -> runSnapshot(params);
                case TREND -> runTrend(params);
                case INSTALL -> runInstall(params);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            DebugLog.error("uncaught exception: " + e.getMessage(), e);
            if (DebugLog.isEnabled() || Boolean.getBoolean("copilot-lens.debug")) {
                e.printStackTrace();
                System.err.println("(stack trace logged to ~/.copilot-lens/debug.log)");
            }
            System.exit(1);
        }
    }

    static void runReport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        IncrementalState state = new IncrementalState();

        List<CopilotRequest> requests = parseWithCache(state, log, parser, params);
        requests = enrichWithChatSessions(requests);

        Report report = new StatsAggregator().aggregate(requests);
        CliReporter cli = new CliReporter(!params.noAnsi);

        if (params.history || params.command == Args.Command.GAIN) {
            cli.printHistory(report);
        } else {
            cli.print(report);
        }

        // Only write HTML when 'report' command is explicitly used.
        // Default run is console-only — avoids Windows auto-opening the file.
        if (params.command == Args.Command.REPORT) {
            Path htmlOut = Paths.get("copilot-lens-report.html");
            new HtmlReporter().write(report, htmlOut);
            System.out.println("HTML report: " + htmlOut.toAbsolutePath());
        }

        // P1.2: append the Effectiveness Score section (categories + tips).
        List<String> configuredMcps;
        try { configuredMcps = new McpScanner().configuredNames(); }
        catch (NoClassDefFoundError | Exception e) { configuredMcps = List.of(); }
        EffectivenessScorer.Score score = new EffectivenessScorer().score(requests, configuredMcps);
        cli.printScore(score);
    }

    /**
     * Incremental parse: onceki cache + dosyanin yeni byte'lari.
     * Ilk calistirma tam parse, sonrakiler delta.
     */
    static List<CopilotRequest> parseWithCache(IncrementalState state, Path log,
                                                LogParser parser, Args params) throws Exception {
        CopilotLensConfig cfg = CopilotLensConfig.load();
        boolean useCache = cfg.getBool("cache.enabled");

        if (!useCache || params.logFile != null) {
            // Manuel log veya cache kapali: full parse
            return parser.parse(log);
        }

        long currentSize = Files.size(log);
        // Use normalized path for state lookup
        String normalizedPath = log.toString().replace('\\', '/');
        long offset = state.getReadOffset(normalizedPath, currentSize);

        if (offset == currentSize) {
            // Yeni veri yok, cache dondur
            List<CopilotRequest> cached = state.getAllCached();
            System.err.println("[cache] Using cached data (" + cached.size() +
                    " requests, no new data since last run)");
            return cached;
        }

        List<CopilotRequest> newRequests;
        if (offset == 0) {
            // First scan or rotated
            newRequests = parser.parse(log);
            System.err.println("[parse] Full scan: " + newRequests.size() + " requests");
        } else {
            // Incremental: read only new bytes
            Path tempSlice = Files.createTempFile("copilot-lens-delta-", ".log");
            try (var channel = java.nio.channels.FileChannel.open(log, java.nio.file.StandardOpenOption.READ)) {
                channel.position(offset);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (currentSize - offset));
                channel.read(buf);
                Files.writeString(tempSlice, new String(buf.array()));
            }
            newRequests = parser.parse(tempSlice);
            Files.deleteIfExists(tempSlice);
            System.err.println("[parse] Delta scan: " + newRequests.size() +
                    " new requests (offset " + offset + " -> " + currentSize + ")");
        }

        state.addRequests(newRequests);

        // Record state (with normalized path)
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
        state.recordParsed(normalizedPath, currentSize, attrs.lastModifiedTime().toMillis(),
                state.getAllCached().size());

        return state.getAllCached();
    }

    static void runWatch(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        new LogWatcher(log, parser, new CliReporter(!params.noAnsi)).watch();
    }

    static void runInit(Args params) throws Exception {
        // Project-level config (cwd) takes priority; init creates it there.
        // User-level config (~/.copilot-lens/config.properties) is only used as
        // a fallback when no project config exists, and is not touched by init.
        Path configFile = Paths.get("config.properties");

        // Idempotent: skip if already exists to avoid triggering Windows file
        // association prompts when the .properties file gets recreated.
        if (Files.exists(configFile)) {
            System.out.println("Config already exists: " + configFile.toAbsolutePath());
            System.out.println("Edit it manually to change IDE log paths or tool settings.");
            System.out.println("Delete it first if you want to regenerate with new defaults.");
            return;
        }

        CopilotLensConfig.writeDefault();
        System.out.println("Project config written: " + configFile.toAbsolutePath());
        System.out.println("Edit to override IDE log paths and tool settings.");
    }

    /**
     * Install the wrapper into ~/.local/bin so `copilot-lens` is runnable
     * from anywhere on PATH. Equivalent to the old install.sh but goes
     * through the standard wrapper so output stays in the current terminal.
     */
    static void runInstall(Args params) throws Exception {
        // Find wrapper source: same directory the JVM was launched from.
        Path projectRoot = locateProjectRoot();
        Path wrapperSrc = projectRoot.resolve("copilot-lens.sh");
        if (!Files.exists(wrapperSrc)) {
            System.err.println("ERROR: wrapper not found: " + wrapperSrc);
            System.err.println("Run from the copilot-lens project root.");
            System.exit(1);
        }

        Path home = Paths.get(System.getProperty("user.home"));
        Path installDir = home.resolve(".local").resolve("bin");
        Files.createDirectories(installDir);
        Path wrapperDst = installDir.resolve("copilot-lens");

        // Replace existing file or symlink
        try { Files.delete(wrapperDst); } catch (java.nio.file.NoSuchFileException ignored) {}

        Files.copy(wrapperSrc, wrapperDst, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        // Ensure executable bit (best-effort on Windows)
        wrapperDst.toFile().setExecutable(true, false);

        System.out.println("OK Installed: " + wrapperDst);
        System.out.println("   (from " + wrapperSrc + ")");

        // PATH check + .bashrc update
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && pathEnv.contains(";" + installDir.toString())
                            || (pathEnv != null && pathEnv.startsWith(installDir.toString()))) {
            System.out.println("OK " + installDir + " is already in PATH");
        } else {
            Path bashrc = home.resolve(".bashrc");
            if (Files.exists(bashrc)) {
                String marker = "# copilot-lens PATH";
                String existing = Files.readString(bashrc);
                if (!existing.contains(marker)) {
                    String append = System.lineSeparator()
                                   + marker + System.lineSeparator()
                                   + "export PATH=\"$HOME/.local/bin:$PATH\"" + System.lineSeparator();
                    Files.writeString(bashrc, append,
                            java.nio.file.StandardOpenOption.APPEND);
                    System.out.println("OK PATH updated in " + bashrc);
                    System.out.println("   Activate with: source " + bashrc);
                } else {
                    System.out.println("OK PATH entry already present in " + bashrc);
                }
            } else {
                System.out.println("WARN No ~/.bashrc found. Add manually:");
                System.out.println("   export PATH=\"$HOME/.local/bin:$PATH\"");
            }
        }

        System.out.println();
        System.out.println("Install complete. Test with:");
        System.out.println("  copilot-lens --help");
    }

    /**
     * Locate the project root by searching upward from cwd for
     * {@code lib/jtokkit-*.jar} (the same convention the bash wrapper uses).
     */
    static Path locateProjectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path lib = dir.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var s = Files.list(lib)) {
                    if (s.anyMatch(p -> p.getFileName().toString().startsWith("jtokkit-"))) {
                        return dir;
                    }
                } catch (Exception ignored) {}
            }
            dir = dir.getParent();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    static void runDiscover(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);
        requests = enrichWithChatSessions(requests);

        Discoverer d = new Discoverer();
        List<Finding> findings = d.analyze(requests);

        System.out.println("Discovery Report");
        System.out.println();
        System.out.println("=".repeat(64));
        if (findings.isEmpty()) {
            System.out.println("No significant issues detected.");
            return;
        }
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            System.out.printf(Locale.ROOT, "%n[%d] %s%n", i + 1, f.title());
            System.out.printf(Locale.ROOT, "    %s%n", f.detail());
            System.out.printf(Locale.ROOT, "    Severity: %.1f%n", f.severity());
        }
        System.out.println();
        System.out.println("=".repeat(64));
        System.out.println("Suggestions:");
        System.out.println("  - Reduce number of open files in IDE");
        System.out.println("  - Close .md files (agents.md, claude.md, business docs)");
        System.out.println("  - Prefer inline suggestions over Chat");
        System.out.println("  - Use #selection in custom commands, don't paste whole files");
    }

    static void runExport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);
        requests = enrichWithChatSessions(requests);

        String format = params.format != null ? params.format : "json";
        if (!format.equals("json")) {
            System.err.println("Only 'json' format is currently supported.");
            System.exit(1);
        }

        Path output = Paths.get("copilot-lens-export.json");
        new JsonReporter().write(requests, output);
        System.out.println("Export written: " + output.toAbsolutePath());
    }

    /**
     * Persist a Snapshot for today derived from the cache.
     * The Report aggregates ALL cached requests; we filter by date so each
     * daily snapshot is self-contained and additive across runs.
     */
    static void runSnapshot(Args params) throws Exception {
        IncrementalState state = new IncrementalState();
        // Force a cache-only read; we only need what was already parsed.
        Path log = params.logFile != null ? params.logFile : resolveLog(params);
        LogParser parser = createParser(log, params);

        List<CopilotRequest> cached = state.getAllCached();
        if (cached.isEmpty()) {
            // No cache: parse once so we have something to snapshot.
            cached = parser.parse(log);
            state.addRequests(cached);
            long size = Files.size(log);
            String normalized = log.toString().replace('\\', '/');
            BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
            state.recordParsed(normalized, size,
                    attrs.lastModifiedTime().toMillis(), cached.size());
        }

        LocalDate today = LocalDate.now();
        Snapshot s = Snapshot.forDate(today, cached);

        SnapshotStore store = new SnapshotStore();
        store.save(s);

        CliReporter cli = new CliReporter(!params.noAnsi);
        cli.printSnapshotConfirmation(s, store.dir());
    }

    static void runTrend(Args params) throws Exception {
        SnapshotStore store = new SnapshotStore();
        List<Snapshot> all = store.loadAll();
        if (all.isEmpty()) {
            System.out.println("No snapshots yet.");
            System.out.println("Run `copilot-lens snapshot` to record today's totals.");
            return;
        }

        Period period = TrendAggregator.parse(params.period);
        TrendAggregator agg = new TrendAggregator();
        List<TrendPoint> points = agg.aggregate(all, period);
        points = agg.limit(points, Math.max(1, params.days));

        CliReporter cli = new CliReporter(!params.noAnsi);
        cli.printTrend(points, period, all.size());
    }

    static Path resolveLog(Args params) {
        if (params.logFile != null) return params.logFile;

        IdeDetector detector = new IdeDetector();
        Optional<Path> detected;

        switch (params.ide) {
            case IDE_VSCODE -> detected = detector.findVsCodeLog();
            case IDE_INTELLIJ -> detected = detector.findIntelliJLog();
            case IDE_CURSOR -> detected = detector.findCursorLog();
            case IDE_WINDSURF -> detected = detector.findWindsurfLog();
            default -> detected = detector.findAnyLog();
        }

        return detected.orElseThrow(() -> {
            String msg = "Log file not found.\n";
            msg += "  Pass --log=<path> manually, then enable verbose log\n";
            msg += "  for the IDE you use. See docs/LOG_ACTIVATION.md for\n";
            msg += "  VSCode, IntelliJ, Windsurf, and Cursor setup steps.";
            return new RuntimeException(msg);
        });
    }

    /**
     * P0 step 9: VSCode chat-session reader integration. Walks
     * {@code %APPDATA%\Code\User\workspaceStorage\*\state.vscdb} to find
     * the chat session index, then parses each {@code chatSessions\<id>.{json,jsonl}}
     * file into one {@link CopilotRequest} per turn. Appended to the input
     * list and returned.
     *
     * <p>Gated by {@code chatsession.enabled=true} in config. Default OFF
     * so existing runs are unchanged. Returns the input list unchanged
     * when disabled or when no VSCode install is present.
     *
     * <p>All errors are swallowed: missing VSCode, corrupt SQLite, oversized
     * session files, malformed JSON, or missing gson/sqlite-jdbc on classpath
     * all result in a quiet skip. The flag is opt-in; we never break a run.
     */
    static List<CopilotRequest> enrichWithChatSessions(List<CopilotRequest> existing) {
        CopilotLensConfig cfg = CopilotLensConfig.load();
        if (!cfg.getBool("chatsession.enabled")) return existing;
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) return existing;
        Path userRoot = Paths.get(appdata, "Code", "User");
        if (!Files.isDirectory(userRoot)) return existing;

        long maxBytes;
        try { maxBytes = Long.parseLong(cfg.get("chatsession.maxBytes")); }
        catch (NumberFormatException e) { maxBytes = 200_000_000L; }

        VsCodeSessionDb db = new VsCodeSessionDb();
        VsCodeSessionJson jsonParser = new VsCodeSessionJson(maxBytes);
        VsCodeSessionJsonl jsonlParser = new VsCodeSessionJsonl(maxBytes);

        List<CopilotRequest> added = new ArrayList<>();
        List<VsCodeSessionDb.WorkspaceSessions> workspaces;
        try { workspaces = db.loadAll(userRoot); }
        catch (NoClassDefFoundError | Exception e) {
            // sqlite-jdbc or gson missing — silently skip
            return existing;
        }

        int sessionsParsed = 0, sessionsSkipped = 0;
        for (var ws : workspaces) {
            Path chatSessionsDir = ws.stateDb().getParent().resolve("chatSessions");
            if (!Files.isDirectory(chatSessionsDir)) continue;
            for (var entry : ws.sessions()) {
                if (entry.isEmpty()) continue;
                String sessionId = entry.sessionId();
                Path jsonFile = chatSessionsDir.resolve(sessionId + ".json");
                Path jsonlFile = chatSessionsDir.resolve(sessionId + ".jsonl");
                Path sessionFile = Files.exists(jsonFile) ? jsonFile
                        : (Files.exists(jsonlFile) ? jsonlFile : null);
                if (sessionFile == null) {
                    sessionsSkipped++;
                    continue;
                }
                List<? extends Object> turns;
                try {
                    if (sessionFile.toString().endsWith(".json")) {
                        turns = jsonParser.parse(sessionFile, entry.title(), ws.workspaceHash());
                    } else {
                        turns = jsonlParser.parse(sessionFile, entry.title(), ws.workspaceHash());
                    }
                } catch (Exception e) {
                    sessionsSkipped++;
                    continue;
                }
                if (turns == null) {
                    sessionsSkipped++;
                    continue;
                }
                for (Object o : turns) {
                    if (o instanceof VsCodeSessionJson.SessionTurn jt) {
                        added.add(CopilotRequest.ofSession(
                                jt.timestamp(), CopilotRequest.Ide.VSCODE,
                                jt.sessionId(), jt.agentId(),
                                jt.promptText(), jt.responseText(),
                                jt.toolNames(), jt.title()));
                    } else if (o instanceof VsCodeSessionJsonl.SessionTurn lt) {
                        added.add(CopilotRequest.ofSession(
                                lt.timestamp(), CopilotRequest.Ide.VSCODE,
                                lt.sessionId(), lt.agentId(),
                                lt.promptText(), lt.responseText(),
                                lt.toolNames(), lt.title()));
                    }
                }
                sessionsParsed++;
            }
        }
        if (sessionsParsed > 0 || sessionsSkipped > 0) {
            System.err.println("[chatsession] parsed " + sessionsParsed +
                    " sessions, skipped " + sessionsSkipped);
        }

        List<CopilotRequest> merged = new ArrayList<>(existing.size() + added.size());
        merged.addAll(existing);
        merged.addAll(added);
        return merged;
    }

    static LogParser createParser(Path log, Args params) {
        TokenCounter counter = new TokenCounter();
        return switch (params.ide) {
            case IDE_INTELLIJ -> new IntelliJParser(counter);
            case IDE_CURSOR -> new VsCodeForkParser(counter, CopilotRequest.Ide.CURSOR);
            case IDE_WINDSURF -> new VsCodeForkParser(counter, CopilotRequest.Ide.WINDSURF);
            default -> new VsCodeParser(counter);
        };
    }

    static void printHelp() {
        String help = """
            copilot-lens - GitHub Copilot Token & Premium Analyzer

            Usage:
              copilot-lens [command] [options]

            Commands:
              (none)           One-shot report (console + HTML)
              gain             Usage summary
              gain --history   Daily history trend
              discover         Find most expensive usage patterns
              watch            Live monitoring (RTK 'watch' style)
              export json      JSON export
              report           Generate HTML report only
              snapshot         Persist today's totals to ~/.copilot-lens/snapshots/
              trend            ASCII trend chart from stored snapshots
              init             Write default ./config.properties (idempotent)
              install          Copy wrapper to ~/.local/bin and update PATH

            Options:
              --ide=vscode|idea|cursor|windsurf|auto   IDE selection (default: auto)
              --log=<path>             Manual log file
              --period=daily|weekly|monthly   Trend grouping (default: daily)
              --days=N                 How many recent buckets to show (default: 30)
              --no-ansi                Disable colored terminal output
              --help, -h               Show this help

            Examples:
              copilot-lens
              copilot-lens watch --ide=idea
              copilot-lens --ide=cursor
              copilot-lens --ide=windsurf
              copilot-lens discover
              copilot-lens gain --history
              copilot-lens snapshot
              copilot-lens trend --period=weekly --days=12
              copilot-lens trend --period=monthly
              copilot-lens export json
              copilot-lens install
              copilot-lens --log=/path/to/custom.log

            Enable verbose log: see docs/LOG_ACTIVATION.md
            (covers VSCode, IntelliJ, Cursor, Windsurf)
            """;
        System.out.println(help);
    }
}

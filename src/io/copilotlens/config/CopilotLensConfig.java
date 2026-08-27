package io.copilotlens.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Config lookup chain (highest priority first):
 *   1. Environment variable  COPILOT_LENS_<KEY>
 *   2. Project config         <project-dir>/config.properties
 *   3. User config            ~/.copilot-lens/config.properties
 *   4. Hard-coded defaults
 *
 * Config keys:
 *   log.vscode     VSCode output_logging glob
 *   log.idea       IntelliJ idea.log glob
 *   log.cursor     Cursor output_logging glob (VSCode-fork)
 *   log.windsurf   Windsurf output_logging glob (Codeium VSCode-fork)
 *   state.dir      Where to keep incremental state + cache
 *   state.enabled  Track file byte offsets (delta scans)
 *   cache.enabled  Cache parsed requests across runs
 */
public class CopilotLensConfig {

    private static final Path DEFAULT_HOME = Paths.get(
            System.getProperty("user.home"), ".copilot-lens");
    private static final Path USER_CONFIG = DEFAULT_HOME.resolve("config.properties");

    private final Properties props = new Properties();

    private CopilotLensConfig() {
        // Hard-coded baseline defaults
        // VSCode: search both the old "output_logging" convention (used when
        // verbose log is enabled via F1 -> Set Log Level) AND the new
        // per-extension log path produced by GitHub Copilot Chat.
        props.setProperty("log.vscode",
                "${APPDATA}/Code/logs/**/output_logging*.log,"
              + "${APPDATA}/Code/logs/**/GitHub.copilot-chat/GitHub Copilot Chat.log");
        props.setProperty("log.idea", "${LOCALAPPDATA}/JetBrains/**/log/idea.log");
        // Cursor: VSCode-fork; aynı extension-host log konvansiyonu
        props.setProperty("log.cursor", "${APPDATA}/Cursor/logs/**/output_logging*.log");
        // Windsurf: Codeium'un VSCode-fork'u; generic output_logging_*.log kullanılır.
        // Cascade AI'ın kendi iç logu (Lifeguard.log) serbest formatlı olduğu için
        // parse edilmez — sadece GitHub Copilot Chat benzeri extension logları okunur.
        props.setProperty("log.windsurf", "${APPDATA}/Windsurf/logs/**/output_logging*.log");
        props.setProperty("state.dir", DEFAULT_HOME.toString());
        props.setProperty("state.enabled", "true");
        props.setProperty("cache.enabled", "true");

        // Layer 3: user-level config (~/.copilot-lens/config.properties)
        if (Files.exists(USER_CONFIG)) {
            loadFrom(USER_CONFIG);
        }

        // Layer 2: project-level config (./config.properties, relative to cwd)
        Path projectConfig = Paths.get("config.properties");
        if (Files.exists(projectConfig)) {
            loadFrom(projectConfig);
        }
    }

    private void loadFrom(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            // Merge: file values override current defaults
            for (String key : p.stringPropertyNames()) {
                String value = p.getProperty(key).trim();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    props.setProperty(key, value);
                }
            }
        } catch (IOException ignored) {
            // skip unreadable config
        }
    }

    public static CopilotLensConfig load() {
        return new CopilotLensConfig();
    }

    public String get(String key) {
        // Layer 1: env var
        String envKey = "COPILOT_LENS_" + key.toUpperCase().replace('.', '_');
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) return expand(envVal);
        // Layer 2-4: properties (with ${VAR} expansion)
        return expand(props.getProperty(key, ""));
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public Path getPath(String key) {
        return Paths.get(get(key));
    }

    public Path getStateDir() {
        Path dir = Paths.get(get("state.dir"));
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
        }
        return dir;
    }

    public Path getStateFile() {
        return getStateDir().resolve("state.json");
    }

    public Path getCacheFile() {
        return getStateDir().resolve("cache.json");
    }

    public static Path getHomeDir() {
        return DEFAULT_HOME;
    }

    /**
     * Expand ${VAR} placeholders using system env (read-only).
     */
    private String expand(String value) {
        if (value == null || !value.contains("${")) return value;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) { out.append(value, i, value.length()); break; }
            out.append(value, i, start);
            int end = value.indexOf('}', start);
            if (end < 0) { out.append(value, start, value.length()); break; }
            String varName = value.substring(start + 2, end);
            String envVal = System.getenv(varName);
            if (envVal == null) envVal = System.getProperty(varName.toLowerCase());
            out.append(envVal != null ? envVal : "");
            i = end + 1;
        }
        return out.toString();
    }

    /** Write default project-level config (used by `copilot-lens init`). */
    public static void writeDefault() throws IOException {
        String content = """
            # copilot-lens configuration (project-level)
            #
            # This file lives inside the project so each project can pin its own IDE log paths.
            # Values here override the user-level fallback at ~/.copilot-lens/config.properties.
            #
            # After editing, just re-run `copilot-lens` -- no restart needed.

            # --- IDE-specific Copilot log paths ---
            # Glob patterns, ** = recursive directory walk.
            # Variables like ${APPDATA}, ${LOCALAPPDATA}, ${HOME} are expanded at runtime
            # (do NOT set them yourself -- read them from your shell if you need the value).

            # VSCode Copilot log (Windows default)
            log.vscode=${APPDATA}/Code/logs/**/output_logging*.log

            # IntelliJ IDEA Copilot log (Windows default)
            log.idea=${LOCALAPPDATA}/JetBrains/**/log/idea.log

            # Cursor (VSCode fork) Copilot log (Windows default)
            log.cursor=${APPDATA}/Cursor/logs/**/output_logging*.log

            # Windsurf (Codeium VSCode fork) log (Windows default)
            # Cascade AI'in kendi ic logu (Lifeguard.log) serbest formatli,
            # bu yuzden parse edilmez — sadece extension-host logu kullanilir.
            log.windsurf=${APPDATA}/Windsurf/logs/**/output_logging*.log

            # --- Tool behavior ---

            # Where to keep incremental scan state + parsed request cache
            state.dir=${HOME}/.copilot-lens

            # Track per-file byte offset (delta scans on subsequent runs)
            state.enabled=true

            # Cache parsed requests across runs (avoids full re-parse)
            cache.enabled=true
            """;
        Files.writeString(Paths.get("config.properties"), content);
    }
}

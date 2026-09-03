package io.copilotlens.detector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans well-known locations for GitHub Copilot / VSCode MCP server
 * configurations and returns the union of configured server names.
 *
 * <p>Files searched (in order; first non-empty wins, then merged across all):
 *
 * <pre>
 *   ~/.vscode/mcp.json
 *   %APPDATA%\Code\User\mcp.json
 *   %APPDATA%\Code - Insiders\User\mcp.json
 *   &lt;cwd&gt;/.vscode/mcp.json
 *   &lt;cwd&gt;/.github/copilot/mcp.json
 * </pre>
 *
 * <p>Each file is JSONC-tolerant (trailing commas stripped before parse).
 * The {@code servers} key is preferred; the legacy {@code mcpServers}
 * alias is also accepted.
 *
 * <p>Used by the Effectiveness Score (P1.1) MCP Utilization category and
 * by the {@code copilot-lens mcp} subcommand (P2.3).
 */
public class McpScanner {

    /** One configured MCP server entry. */
    public record McpServer(String name, Path sourceFile) {}

    private final Path userHome;
    private final String appData;

    public McpScanner() {
        this.userHome = java.nio.file.Paths.get(System.getProperty("user.home"));
        this.appData = System.getenv("APPDATA");
    }

    public McpScanner(Path userHome, String appData) {
        this.userHome = userHome;
        this.appData = appData;
    }

    /**
     * Scan all known config locations and return the union of configured
     * server names (case-sensitive, in the order first encountered).
     */
    public List<McpServer> scanAll() {
        List<Path> candidates = configCandidates();
        Set<String> seen = new LinkedHashSet<>();
        List<McpServer> out = new ArrayList<>();
        for (Path p : candidates) {
            List<String> names = readServerNames(p);
            for (String n : names) {
                if (seen.add(n)) {
                    out.add(new McpServer(n, p));
                }
            }
        }
        return out;
    }

    /** Just the configured server names (no source file). */
    public List<String> configuredNames() {
        List<String> out = new ArrayList<>();
        for (McpServer s : scanAll()) out.add(s.name());
        return out;
    }

    /** Internal: build the list of config-file candidates. */
    private List<Path> configCandidates() {
        List<Path> out = new ArrayList<>();
        if (userHome != null) {
            out.add(userHome.resolve(".vscode").resolve("mcp.json"));
        }
        if (appData != null && !appData.isEmpty()) {
            out.add(java.nio.file.Paths.get(appData, "Code", "User", "mcp.json"));
            out.add(java.nio.file.Paths.get(appData, "Code - Insiders", "User", "mcp.json"));
        }
        // Project-local (cwd)
        Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir"));
        out.add(cwd.resolve(".vscode").resolve("mcp.json"));
        out.add(cwd.resolve(".github").resolve("copilot").resolve("mcp.json"));
        return out;
    }

    /**
     * Read one config file and extract server names. Returns empty list
     * when the file is missing, unreadable, or its JSON doesn't carry
     * a {@code servers} (or legacy {@code mcpServers}) map. Never throws.
     */
    private List<String> readServerNames(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        String raw;
        try { raw = Files.readString(file); }
        catch (Exception e) { return List.of(); }
        if (raw.isBlank()) return List.of();
        // Strip JSONC trailing commas: ", ]" / ", }" → "]" / "}"
        String json = raw.replaceAll(",\\s*([}\\]])", "$1");
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            return List.of();
        }
        if (!root.isJsonObject()) return List.of();
        JsonObject obj = root.getAsJsonObject();
        JsonElement servers = obj.get("servers");
        if (servers == null || !servers.isJsonObject()) {
            servers = obj.get("mcpServers");
        }
        if (servers == null || !servers.isJsonObject()) return List.of();
        return new ArrayList<>(servers.getAsJsonObject().keySet());
    }
}

package io.copilotlens.parser;

import io.copilotlens.analyzer.TokenCounter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VSCode GitHub Copilot log formatını parse eder.
 * Format: JSON satırları + zaman damgası prefix'i.
 * Tipik log konumu: %APPDATA%\Code\logs\<tarih>\exthost\output_logging_*.log
 */
public class VsCodeParser implements LogParser {

    private static final Pattern TIMESTAMP = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");
    private static final Pattern NUMBER = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+)");
    private static final Pattern ROLE = Pattern.compile("\"role\"");
    private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]{0,120})");

    private final TokenCounter counter;

    public VsCodeParser(TokenCounter counter) {
        this.counter = counter;
    }

    @Override
    public List<CopilotRequest> parse(Path logFile) throws Exception {
        List<CopilotRequest> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(logFile);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LocalDateTime ts = extractTimestamp(line);
            if (ts == null) continue;

            // İstek: POST /v1/chat/completions
            if (line.contains("POST") && line.contains("chat/completions")) {
                StringBuilder body = new StringBuilder(line);
                for (int j = i + 1; j < Math.min(i + 8, lines.size()); j++) {
                    body.append('\n').append(lines.get(j));
                    // Body'nin sonu — kapanış parantezi
                    if (lines.get(j).contains("}") && body.toString().contains("\"messages\"")) {
                        break;
                    }
                }

                int tokens = counter.count(body.toString());
                int msgs = (int) ROLE.matcher(body).results().count();
                String summary = extractSummary(body.toString());
                String workspace = extractWorkspace(body.toString());

                result.add(new CopilotRequest(
                        ts, CopilotRequest.Ide.VSCODE,
                        "/v1/chat/completions",
                        tokens, 0, msgs, summary, workspace));
            }

            // Yanıt: usage objesi içeren satır
            if (line.contains("\"usage\"") && line.contains("completion_tokens")) {
                int outTokens = extractNumber(line, "completion_tokens");
                int inTokens = extractNumber(line, "prompt_tokens");

                if (!result.isEmpty()) {
                    CopilotRequest last = result.get(result.size() - 1);
                    if (last.outputTokens() == 0) {
                        result.set(result.size() - 1, withTokens(last,
                                inTokens > 0 ? inTokens : last.inputTokens(),
                                outTokens));
                    }
                }
            }
        }

        return result;
    }

    @Override
    public CopilotRequest.Ide ide() { return CopilotRequest.Ide.VSCODE; }

    private LocalDateTime extractTimestamp(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        if (!m.find()) return null;
        try {
            String raw = m.group(1);
            // ms varsa at
            if (raw.contains(".")) raw = raw.substring(0, raw.indexOf('.'));
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private int extractNumber(String line, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String extractSummary(String body) {
        Matcher m = CONTENT.matcher(body);
        return m.find() ? m.group(1).replace("\\n", " ") : "(no content)";
    }

    private String extractWorkspace(String body) {
        // URI, file path gibi workspace bilgisi varsa yakala
        Matcher m = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"|\"uri\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private CopilotRequest withTokens(CopilotRequest r, int in, int out) {
        return new CopilotRequest(r.timestamp(), r.ide(), r.endpoint(),
                in, out, r.messageCount(), r.summary(), r.workspaceHint());
    }
}

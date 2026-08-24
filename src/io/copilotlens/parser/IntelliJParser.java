package io.copilotlens.parser;

import io.copilotlens.analyzer.TokenCounter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ IDEA GitHub Copilot plugin log formatını parse eder.
 *
 * Üç format desteklenir:
 *   1) Eski HTTP formatı:
 *      - "copilot.request - POST /v1/chat/completions" + body
 *      - "usage: prompt_tokens=N,completion_tokens=M"
 *      Tokenlar doğrudan logdan okunur.
 *
 *   2) Yeni JSON-RPC formatı (backgroundAgent mimarisi, IntelliJ 2025+):
 *      - JSON-RPC mesajları stdout'a "[stdout] Content-Length: N" header'ı ile yazılır
 *      - Sonraki satır(lar) N byte JSON body içerir
 *      - body içinde `"type":"session.usage_info"` eventleri var:
 *          {tokenLimit, currentTokens, systemTokens, conversationTokens,
 *           toolDefinitionsTokens, messagesLength, isInitial}
 *      - Per-turn delta = currentTokens[n] - currentTokens[n-1] (session başına)
 *
 *   3) ChatSnapshot SessionSnapshot satırları:
 *      - title alanı sessionId ile eşleştirilir
 *      - usage_info eventinde özet için kullanılır
 */
public class IntelliJParser implements LogParser {

    // 2026-08-23 09:15:22,123 [1234567] INFO - copilot... gibi satırlar
    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}),\\d+");

    // [stdout] Content-Length: 245
    private static final Pattern CONTENT_LENGTH = Pattern.compile("\\[stdout\\]\\s+Content-Length:\\s+(\\d+)");

    // backgroundAgent/sessionUpdate içindeki session.usage_info eventi
    // sessionId + data { ... } blokları
    private static final Pattern USAGE_INFO = Pattern.compile(
            "\"method\":\"backgroundAgent/sessionUpdate\".*?" +
            "\"sessionId\":\"([^\"]+)\".*?" +
            "\"type\":\"session\\.usage_info\".*?" +
            "\"data\":\\{([^}]*)\\}");

    // data {} bloğu içindeki "key":123 alanları
    private static final Pattern NUM_FIELD = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*(\\d+)");
    private static final Pattern BOOL_FIELD = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*(true|false)");

    // ISO 8601 UTC: 2026-08-24T14:07:50.969Z
    private static final Pattern ISO_UTC = Pattern.compile(
            "\"timestamp\":\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)Z\"");

    // ChatSnapshot(... sessions=[SessionSnapshot(id=..., title=..., targetType=..., model=..., mode=...), ...])
    private static final Pattern SESSION_SNAPSHOT = Pattern.compile(
            "SessionSnapshot\\(id=([^,]+),\\s*title=([^,]+),\\s*targetType=([^,]+),\\s*model=([^,]+)");

    // Eski HTTP format
    private static final Pattern REQUEST = Pattern.compile("copilot\\.(request|completion|chat)\\b.*?POST\\s+(/v\\d+/[\\w/-]+)");
    private static final Pattern USAGE = Pattern.compile("prompt_tokens=(\\d+).*?completion_tokens=(\\d+)|completion_tokens=(\\d+).*?prompt_tokens=(\\d+)");

    private final TokenCounter counter;
    private final Map<String, Integer> sessionPreviousCurrentTokens = new HashMap<>();
    private final Map<String, String> sessionTitles = new HashMap<>();
    private final Map<String, String> sessionModels = new HashMap<>();

    public IntelliJParser(TokenCounter counter) {
        this.counter = counter;
    }

    @Override
    public List<CopilotRequest> parse(Path logFile) throws Exception {
        List<CopilotRequest> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(logFile);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LocalDateTime ts = extractTimestamp(line, fmt);
            if (ts == null) continue;

            // Session title / model yakalama (öncelikli: usage_info'dan önce gelmiş olabilir)
            // Bir satırda birden fazla SessionSnapshot olabilir; hepsini yakala
            Matcher sm = SESSION_SNAPSHOT.matcher(line);
            while (sm.find()) {
                String id = sm.group(1).trim();
                String title = sm.group(2).trim();
                String model = sm.group(4).trim();
                sessionTitles.put(id, title);
                sessionModels.put(id, model);
            }

            // [stdout] Content-Length: N — JSON-RPC body'yi oku
            Matcher cl = CONTENT_LENGTH.matcher(line);
            if (cl.find()) {
                int length = Integer.parseInt(cl.group(1));
                StringBuilder body = new StringBuilder();
                int j = i + 1;
                while (j < lines.size() && body.length() < length) {
                    if (body.length() > 0) body.append('\n');
                    body.append(lines.get(j));
                    j++;
                }
                i = j - 1; // skip body lines

                String bodyStr = body.toString();
                if (bodyStr.contains("\"type\":\"session.usage_info\"")) {
                    CopilotRequest req = parseUsageInfo(bodyStr, ts);
                    if (req != null) result.add(req);
                }
                continue;
            }

            // Eski HTTP formatı
            if (line.contains("copilot") && line.contains("POST")) {
                Matcher m = REQUEST.matcher(line);
                String endpoint = m.find() ? m.group(2) : "/v1/chat/completions";

                StringBuilder body = new StringBuilder(line);
                for (int k = i + 1; k < Math.min(i + 5, lines.size()); k++) {
                    body.append('\n').append(lines.get(k));
                }

                int tokens = counter.count(body.toString());
                result.add(CopilotRequest.of(
                        ts, CopilotRequest.Ide.INTELLIJ,
                        endpoint, tokens, 0, 1, extractSummary(body.toString()), null));
            }

            if (line.contains("usage")) {
                Matcher m = USAGE.matcher(line);
                if (m.find()) {
                    int pTok = parseGroup(m, 1, 4);
                    int cTok = parseGroup(m, 2, 3);

                    if (!result.isEmpty()) {
                        CopilotRequest last = result.get(result.size() - 1);
                        if (last.outputTokens() == 0) {
                            result.set(result.size() - 1,
                                    CopilotRequest.of(last.timestamp(), last.ide(),
                                            last.endpoint(),
                                            pTok > 0 ? pTok : last.inputTokens(),
                                            cTok, last.messageCount(),
                                            last.summary(), last.workspaceHint()));
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public CopilotRequest.Ide ide() { return CopilotRequest.Ide.INTELLIJ; }

    private CopilotRequest parseUsageInfo(String body, LocalDateTime fallbackTs) {
        Matcher um = USAGE_INFO.matcher(body);
        if (!um.find()) return null;

        String sessionId = um.group(1);
        String dataBlock = um.group(2);

        Map<String, Integer> numFields = new HashMap<>();
        Matcher nf = NUM_FIELD.matcher(dataBlock);
        while (nf.find()) {
            numFields.put(nf.group(1), Integer.parseInt(nf.group(2)));
        }
        Map<String, Boolean> boolFields = new HashMap<>();
        Matcher bf = BOOL_FIELD.matcher(dataBlock);
        while (bf.find()) {
            boolFields.put(bf.group(1), Boolean.parseBoolean(bf.group(2)));
        }

        int currentTokens = numFields.getOrDefault("currentTokens", 0);
        int tokenLimit = numFields.getOrDefault("tokenLimit", 0);
        int systemTokens = numFields.getOrDefault("systemTokens", 0);
        int convTokens = numFields.getOrDefault("conversationTokens", 0);
        int toolTokens = numFields.getOrDefault("toolDefinitionsTokens", 0);
        int msgsLength = numFields.getOrDefault("messagesLength", 0);
        boolean isInitial = boolFields.getOrDefault("isInitial", false);

        int delta = 0;
        Integer prev = sessionPreviousCurrentTokens.get(sessionId);
        if (prev != null) delta = currentTokens - prev;
        sessionPreviousCurrentTokens.put(sessionId, currentTokens);

        LocalDateTime ts = extractIsoTimestamp(body);
        if (ts == null) ts = fallbackTs;

        String title = lookupTitle(sessionId);
        String model = lookupModel(sessionId);

        String summary = String.format(Locale.ROOT,
                "[%s] ctx=%d/%d +%d conv=%d sys=%d tools=%d msgs=%d %s",
                title.length() > 40 ? title.substring(0, 40) + "..." : title,
                currentTokens, tokenLimit, delta,
                convTokens, systemTokens, toolTokens, msgsLength,
                isInitial ? "initial" : "");

        return CopilotRequest.of(
                ts, CopilotRequest.Ide.INTELLIJ,
                "backgroundAgent/sessionUpdate",
                delta, 0, msgsLength,
                summary,
                sessionId);
    }

    private LocalDateTime extractTimestamp(String line, DateTimeFormatter fmt) {
        Matcher m = TIMESTAMP.matcher(line);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1), fmt);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ChatSnapshot'ta session id kısa formatta (örn "84a0695e"),
     * usage_info event'inde ise tam UUID (örn "84a0695e-9c58-...")
     * görünür. Tam UUID'ın ilk segment'i kısa id ile eşleşir; prefix lookup yap.
     */
    private String lookupTitle(String fullUuid) {
        for (var e : sessionTitles.entrySet()) {
            if (fullUuid.startsWith(e.getKey() + "-") || fullUuid.equals(e.getKey())) {
                return e.getValue();
            }
        }
        return "(unknown)";
    }

    private String lookupModel(String fullUuid) {
        for (var e : sessionModels.entrySet()) {
            if (fullUuid.startsWith(e.getKey() + "-") || fullUuid.equals(e.getKey())) {
                return e.getValue();
            }
        }
        return "(unknown)";
    }

    private LocalDateTime extractIsoTimestamp(String body) {
        Matcher m = ISO_UTC.matcher(body);
        if (!m.find()) return null;
        try {
            OffsetDateTime odt = OffsetDateTime.parse(m.group(1) + "Z",
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private int parseGroup(Matcher m, int... groups) {
        for (int g : groups) {
            String val = m.group(g);
            if (val != null) return Integer.parseInt(val);
        }
        return 0;
    }

    private String extractSummary(String body) {
        Matcher m = Pattern.compile("content=([^,]{0,80})").matcher(body);
        return m.find() ? m.group(1).trim() : "(intellij request)";
    }
}

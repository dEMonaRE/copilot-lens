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
 * İki formatı destekler:
 *   1) Eski JSON satır formatı: "...POST /v1/chat/completions..." + body + usage
 *      (VSCode GitHub Copilot extension < 0.60, output_logging_*.log)
 *   2) Yeni GitHub Copilot Chat (>= 0.60) log formatı:
 *      [fetchCompletions] Request <id> at <url> finished with <status> after <ms>ms
 *      [ccreq:<id>.copilotmd] | success | <model> | <ms>ms | [<provider>]
 *      Yeni format token sayılarını içermez — model + provider + latency döner;
 *      look-ahead ile JSON body bulunursa BPE ile tahmin edilir.
 *
 * Token bilgisi yeni mimaride log'a düşmediği için proxy metrikler kullanılır;
 * mümkün olduğunda look-ahead + BPE ile yerel tahmin yapılır ve
 * TokenSource.ESTIMATED olarak işaretlenir.
 */
public class VsCodeParser implements LogParser {

    // Format 1 (eski): ISO timestamp prefix
    private static final Pattern TIMESTAMP_ISO = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");
    // Format 2 (yeni): "2026-08-22 01:06:55.731 [info] ..." prefix
    private static final Pattern TIMESTAMP_SPACE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)");

    private static final Pattern ROLE = Pattern.compile("\"role\"");
    private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]{0,120})");

    // Format 2 body look-ahead sinyalleri — hepsi eşzamanlı eşleşmeli
    private static final Pattern BODY_HAS_ROLE = Pattern.compile("\"role\"\\s*:\\s*\"");
    private static final Pattern BODY_HAS_CONTENT = Pattern.compile("\"content\"\\s*:");
    private static final Pattern BODY_HAS_MESSAGES = Pattern.compile("\"messages\"\\s*:\\s*\\[");

    /** Format 2 body look-ahead: ileri yönde kaç satır taranır. */
    private static final int LOOK_AHEAD_LIMIT = 64;
    /** Body bulunamazsa geriye doğru kaç satır daha taranır. */
    private static final int LOOK_BACK_LIMIT = 32;
    /** Body aday kabulü için satır başına maksimum uzunluk. */
    private static final int MAX_BODY_LINE = 8 * 1024;
    /** Birleştirilen body'nin toplam maksimum uzunluğu (büyük dump'ları kırpar). */
    private static final int MAX_BODY_TOTAL = 64 * 1024;

    // fetchCompletions: "...Request <uuid> at <https://...> finished with <status> after <float>ms"
    private static final Pattern FETCH_COMPLETIONS = Pattern.compile(
            "\\[fetchCompletions\\]\\s+Request\\s+\\S+\\s+at\\s+<(https?://[^>]+)>\\s+finished\\s+with\\s+(\\d+)\\s+status\\s+after\\s+([\\d.]+)ms");

    // ccreq: "...ccreq:<id>.copilotmd | success | <model> | <int>ms | [<provider>]"
    private static final Pattern CCREQ_SUCCESS = Pattern.compile(
            "ccreq:\\S+\\.copilotmd\\s*\\|\\s*success\\s*\\|\\s*(\\S+)\\s*\\|\\s*(\\d+)ms\\s*\\|\\s*\\[([^\\]]+)\\]");

    private final TokenCounter counter;
    private final CopilotRequest.Ide ideTag;

    public VsCodeParser(TokenCounter counter) {
        this(counter, CopilotRequest.Ide.VSCODE);
    }

    /**
     * Cursor / Windsurf gibi VSCode-fork IDE'ler için Ide etiketi override.
     * Aynı regex/log mantığı paylaşılır; sadece üretilen kayıtların Ide alanı değişir.
     */
    public VsCodeParser(TokenCounter counter, CopilotRequest.Ide ideTag) {
        this.counter = counter;
        this.ideTag = ideTag;
    }

    @Override
    public List<CopilotRequest> parse(Path logFile) throws Exception {
        List<CopilotRequest> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(logFile);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LocalDateTime ts = extractTimestamp(line);
            if (ts == null) continue;

            // ---- Format 1 (eski): POST + usage ----
            if (line.contains("POST") && line.contains("chat/completions")) {
                StringBuilder body = new StringBuilder(line);
                for (int j = i + 1; j < Math.min(i + 8, lines.size()); j++) {
                    body.append('\n').append(lines.get(j));
                    if (lines.get(j).contains("}") && body.toString().contains("\"messages\"")) {
                        break;
                    }
                }

                int tokens = counter.count(body.toString());
                int msgs = (int) ROLE.matcher(body).results().count();
                String summary = extractSummary(body.toString());
                String workspace = extractWorkspace(body.toString());

                result.add(CopilotRequest.of(ts, ideTag,
                        "/v1/chat/completions", tokens, 0, msgs, summary, workspace));
            }

            // ---- Format 1 usage satırı ----
            if (line.contains("\"usage\"") && line.contains("completion_tokens")) {
                int outTokens = extractNumber(line, "completion_tokens");
                int inTokens = extractNumber(line, "prompt_tokens");

                if (!result.isEmpty()) {
                    CopilotRequest last = result.get(result.size() - 1);
                    if (last.outputTokens() == 0) {
                        // usage satırı log'dan geldi: REPORTED olarak işaretle
                        result.set(result.size() - 1, CopilotRequest.ofReported(
                                last.timestamp(), last.ide(), last.endpoint(),
                                inTokens > 0 ? inTokens : last.inputTokens(),
                                outTokens, last.messageCount(),
                                last.summary(), last.workspaceHint()));
                    }
                }
            }

            // ---- Format 2 (yeni): fetchCompletions ----
            Matcher fm = FETCH_COMPLETIONS.matcher(line);
            if (fm.find()) {
                String url = fm.group(1);
                int status = Integer.parseInt(fm.group(2));
                int latency = (int) Math.round(Double.parseDouble(fm.group(3)));
                if (status != 200) continue;  // sadece başarılı istekleri say

                String model = extractModelFromUrl(url);
                String summary = url;

                // Body look-ahead: etraf satırlarda JSON body varsa BPE ile token say
                String body = lookAroundForBody(lines, i);
                if (body != null) {
                    // Model-aware BPE sayımı (o200k_base gpt-4o için, cl100k_base geri kalanı)
                    int input = TokenCounter.forModel(model).count(body);
                    result.add(CopilotRequest.ofEstimated(ts, ideTag,
                            url, input, 0, 1, summary, null,
                            model, null, latency));
                } else {
                    // Body bulunamadı: heuristic fallback — URL/summary metninden
                    // karakter tabanlı tahmin yap. Tam sıfır yerine "0" değil
                    // "~30 tok" gibi bir gösterge değer verir.
                    String heuristicSrc = url != null ? url : "";
                    int heurIn = TokenCounter.estimateFromChars(heuristicSrc);
                    if (heurIn > 0) {
                        result.add(CopilotRequest.ofEstimatedHeuristic(ts, ideTag,
                                url, heurIn, 0, 1, summary, null,
                                model, null, latency));
                    } else {
                        result.add(CopilotRequest.proxy(ts, ideTag,
                                url, model, null, latency, summary));
                    }
                }
            }

            // ---- Format 2 (yeni): ccreq success ----
            Matcher cm = CCREQ_SUCCESS.matcher(line);
            if (cm.find()) {
                String model = cm.group(1);
                int latency = Integer.parseInt(cm.group(2));
                String provider = cm.group(3);

                // Eğer son eklenen kayıt aynı timestamp + aynı model ise
                // provider/latency ile merge et (fetchCompletions'tan gelmiş olabilir).
                if (!result.isEmpty()) {
                    CopilotRequest last = result.get(result.size() - 1);
                    if (last.timestamp().equals(ts) && model.equals(last.model())
                            && last.provider() == null) {
                        result.set(result.size() - 1,
                                new CopilotRequest(last.timestamp(), last.ide(),
                                        last.endpoint(), last.inputTokens(),
                                        last.outputTokens(), last.messageCount(),
                                        last.summary(), last.workspaceHint(),
                                        last.model(), provider, latency,
                                        last.tokenSource(),
                                        last.sessionId(), last.agent(),
                                        last.promptText(), last.responseText(),
                                        last.toolsUsed()));
                        continue;
                    }
                }

                // ccreq success tek başına geldi (fetchCompletions'sız): summary'den
                // heuristic ile input tahmini yap ki 0 gözükmesin.
                String heurSrc = "ccreq success (" + model + ", " + provider + ")";
                int heurIn = TokenCounter.estimateFromChars(heurSrc);
                if (heurIn > 0) {
                    result.add(CopilotRequest.ofEstimatedHeuristic(ts, ideTag,
                            "/v1/engines/" + model + "/completions",
                            heurIn, 0, 1, heurSrc, null,
                            model, provider, latency));
                } else {
                    result.add(CopilotRequest.proxy(ts, ideTag,
                            "/v1/engines/" + model + "/completions",
                            model, provider, latency,
                            "ccreq success (" + model + ", " + provider + ")"));
                }
            }
        }

        return result;
    }

    @Override
    public CopilotRequest.Ide ide() { return ideTag; }

    private LocalDateTime extractTimestamp(String line) {
        Matcher m = TIMESTAMP_SPACE.matcher(line);
        if (m.find()) {
            try {
                String raw = m.group(1);
                if (raw.contains(".")) raw = raw.substring(0, raw.indexOf('.'));
                return LocalDateTime.parse(raw,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ignored) {}
        }
        Matcher m2 = TIMESTAMP_ISO.matcher(line);
        if (m2.find()) {
            try {
                String raw = m2.group(1);
                if (raw.contains(".")) raw = raw.substring(0, raw.indexOf('.'));
                return LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception ignored) {}
        }
        return null;
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
        Matcher m = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"|\"uri\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private String extractModelFromUrl(String url) {
        // https://proxy.individual.githubcopilot.com/v1/engines/gpt-41-copilot/completions
        Matcher m = Pattern.compile("/v1/engines/([^/]+)/").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Format 2 ([fetchCompletions]) hattı için, {@code anchorIdx} civarında
     * (geriye doğru {@link #LOOK_BACK_LIMIT} satır + ileriye doğru
     * {@link #LOOK_AHEAD_LIMIT} satır) JSON request body arar. Bulursa
     * BPE-count edilecek şekilde tek string olarak birleştirir; bulamazsa null.
     *
     * <p>Yeni VSCode log formatında body bazen fetchCompletions satırından
     * <em>önce</em>, bazen <em>sonra</em> gelir; ayrıca iki request arasında
     * 30-80 satır gürültü olabilir. Bu nedenle hem ileri hem geri tarıyoruz.
     *
     * Hevistik:
     *  - Satır hem "role" hem "content" veya "messages" içermeli
     *  - Satır uzunluğu {@link #MAX_BODY_LINE}'ı aşmamalı
     *  - Yeni bir timestamped log satırına ulaşırsak bakmayı bırakırız
     *  - İlk body satırından sonra JSON bracket derinliğini izleyerek
     *    body kapanana kadar toplarız (body birden fazla satıra bölünebilir)
     *  - Toplam uzunluk {@link #MAX_BODY_TOTAL} ile sınırlıdır
     */
    private String lookAroundForBody(List<String> lines, int anchorIdx) {
        // İleri yönde ara (fetchCompletions sonrası body)
        String body = scanForBody(lines, anchorIdx + 1,
                Math.min(anchorIdx + 1 + LOOK_AHEAD_LIMIT, lines.size()),
                false);
        if (body != null) return body;

        // Geri yönde ara (fetchCompletions öncesi body)
        int backStart = Math.max(0, anchorIdx - LOOK_BACK_LIMIT);
        body = scanForBody(lines, backStart, anchorIdx, true);
        return body;
    }

    /**
     * {@code [startIdx, endIdx)} aralığında JSON body arar. {@code reverse=true}
     * ise sondan başa doğru arar (geri taramada en yakın body'yi tercih etmek için).
     */
    private String scanForBody(List<String> lines, int startIdx, int endIdx, boolean reverse) {
        if (reverse) {
            for (int k = endIdx - 1; k >= startIdx; k--) {
                String body = tryCollectBody(lines, k, endIdx, startIdx);
                if (body != null) return body;
            }
        } else {
            for (int k = startIdx; k < endIdx; k++) {
                String body = tryCollectBody(lines, k, endIdx, startIdx);
                if (body != null) return body;
            }
        }
        return null;
    }

    /**
     * {@code lines[k]} aday body satırı mı diye bakar; öyleyse JSON bracket
     * derinliğini izleyerek tam body'yi döndürür. Değilse null.
     */
    private String tryCollectBody(List<String> lines, int k, int endIdx, int startIdx) {
        String cand = lines.get(k);
        if (cand == null || cand.isEmpty()) return null;
        // Yeni timestamped log satırı: bu artık farklı bir request
        if (TIMESTAMP_SPACE.matcher(cand).find() || TIMESTAMP_ISO.matcher(cand).find()) {
            return null;
        }
        // Yanlış pozitif filtreleri — body adayının tüm sinyalleri taşıması şart
        if (cand.length() > MAX_BODY_LINE) return null;
        if (!BODY_HAS_ROLE.matcher(cand).find()) return null;
        if (!BODY_HAS_CONTENT.matcher(cand).find() && !BODY_HAS_MESSAGES.matcher(cand).find()) {
            return null;
        }

        // Body buradan başlıyor: JSON bracket derinliğini izleyerek topla
        StringBuilder sb = new StringBuilder(cand.trim());
        int depth = 0;
        for (int p = 0; p < cand.length(); p++) {
            char ch = cand.charAt(p);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
        }
        int j = k + 1;
        while (depth > 0 && j < endIdx && sb.length() < MAX_BODY_TOTAL) {
            String next = lines.get(j);
            if (next != null && !next.isEmpty()) {
                sb.append(' ').append(next.trim());
                for (int p = 0; p < next.length(); p++) {
                    char ch = next.charAt(p);
                    if (ch == '{') depth++;
                    else if (ch == '}') depth--;
                }
            }
            j++;
        }
        if (depth > 0) return null;  // tam kapanmamış body, güvenilmez
        return sb.toString();
    }
}

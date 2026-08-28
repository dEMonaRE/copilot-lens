package io.copilotlens.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * VsCodeParser regression testleri. Sample log satırları gerçek enterprise
 * / yeni VSCode Copilot Chat log formatından alınmıştır.
 *
 * <p>Çalıştırma:
 * <pre>java -ea -cp out:lib/jtokkit-1.1.0.jar:test io.copilotlens.parser.VsCodeParserTest</pre>
 */
public class VsCodeParserTest {

    private static int passed = 0, failed = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        run("format2_noBody_heuristicFallback", VsCodeParserTest::format2_noBody_heuristicFallback);
        run("format2_withBody_estimated", VsCodeParserTest::format2_withBody_estimated);
        run("format2_ccreqMerge", VsCodeParserTest::format2_ccreqMerge);
        run("format1_postLine_counted", VsCodeParserTest::format1_postLine_counted);

        System.out.println();
        System.out.println("=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            for (String f : FAILURES) System.out.println("  FAIL: " + f);
            System.exit(1);
        }
    }

    @FunctionalInterface
    interface TestCase { void run() throws Throwable; }

    private static void run(String name, TestCase tc) {
        try {
            tc.run();
            System.out.println("  OK   " + name);
            passed++;
        } catch (Throwable t) {
            System.out.println("  FAIL " + name + " -- " + t.getMessage());
            FAILURES.add(name + ": " + t.getMessage());
            failed++;
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        boolean eq = (expected == null) ? actual == null : expected.equals(actual);
        if (!eq) {
            throw new AssertionError(msg + " (expected '" + expected + "', got '" + actual + "')");
        }
    }

    /** Sample log'u geçici dosyaya yazar, parse eder, sonra siler. */
    private static List<CopilotRequest> parseLog(String content) throws Exception {
        Path tmp = Files.createTempFile("copilot-lens-test-", ".log");
        Files.writeString(tmp, content);
        try {
            return new VsCodeParser(new io.copilotlens.analyzer.TokenCounter()).parse(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---------- Test cases ----------

    /**
     * Yeni VSCode log formatı: fetchCompletions var ama body'si yok
     * (tipik enterprise senaryosu). Heuristic fallback input tahmini yapmalı.
     */
    static void format2_noBody_heuristicFallback() throws Exception {
        String log = String.join("\n",
                "2026-08-22 01:06:55.731 [info] [fetchCompletions] Request 66490d5a at <https://proxy.individual.githubcopilot.com/v1/engines/gpt-4o-mini-2024-07-18/completions> finished with 200 status after 405.9ms",
                ""  // boş satır — body yok
        );

        List<CopilotRequest> result = parseLog(log);
        assertEquals(1, result.size(), "Tek bir kayıt beklenir");
        CopilotRequest r = result.get(0);

        // Model tespit edilmeli
        assertEquals("gpt-4o-mini-2024-07-18", r.model(), "Model URL'den çıkarılmalı");

        // Body bulunamadı → heuristic ile bir input tahmini olmalı (0 değil)
        assertTrue(r.inputTokens() > 0,
                "Heuristic fallback 0 yerine makul bir tahmin dönmeli, geldi: " + r.inputTokens());

        // ESTIMATED_HEURISTIC source olmalı
        assertEquals(CopilotRequest.TokenSource.ESTIMATED_HEURISTIC, r.tokenSource(),
                "Body yok → ESTIMATED_HEURISTIC beklenir");
    }

    /**
     * Yeni VSCode log formatı: fetchCompletions + hemen ardından JSON body.
     * BPE ile input tahmini yapılmalı (ESTIMATED).
     */
    static void format2_withBody_estimated() throws Exception {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello world\"}]}";
        String log = String.join("\n",
                "2026-08-22 01:06:55.731 [info] [fetchCompletions] Request abc at <https://proxy.individual.githubcopilot.com/v1/engines/gpt-4o-mini-2024-07-18/completions> finished with 200 status after 500ms",
                body
        );

        List<CopilotRequest> result = parseLog(log);
        assertEquals(1, result.size(), "Tek bir kayıt beklenir");
        CopilotRequest r = result.get(0);

        assertTrue(r.inputTokens() > 0, "BPE sayımı 0 olmamalı");
        assertEquals(CopilotRequest.TokenSource.ESTIMATED, r.tokenSource(),
                "Body bulundu → ESTIMATED beklenir");
    }

    /**
     * ccreq success satırı tek başına geldiğinde fetchCompletions ile merge
     * olmalı; aksi halde heuristic ile input tahmini yapılmalı.
     */
    static void format2_ccreqMerge() throws Exception {
        String log = String.join("\n",
                "2026-08-24 16:53:30.305 [info] ccreq:fef93778.copilotmd | success | gpt-4o-mini-2024-07-18 | 567ms | [title]"
        );

        List<CopilotRequest> result = parseLog(log);
        assertEquals(1, result.size(), "Tek kayıt (ccreq success) beklenir");
        CopilotRequest r = result.get(0);

        assertEquals("gpt-4o-mini-2024-07-18", r.model(), "Model adı ccreq'ten alınmalı");
        assertEquals("title", r.provider(), "Provider ccreq'ten alınmalı");
        assertEquals(567, r.latencyMs().intValue(), "Latency ccreq'ten alınmalı");
        assertTrue(r.inputTokens() > 0, "ccreq summary'den heuristic tahmin > 0 olmalı");
        assertEquals(CopilotRequest.TokenSource.ESTIMATED_HEURISTIC, r.tokenSource(),
                "Body yok → ESTIMATED_HEURISTIC beklenir");
    }

    /**
     * Eski Format 1: POST satırı + body'si hemen ardından.
     * BPE ile input tahmini yapılmalı (ESTIMATED, çünkü REPORTED usage satırı yok).
     */
    static void format1_postLine_counted() throws Exception {
        String body = "POST /v1/chat/completions {\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String log = String.join("\n",
                "2024-01-01T12:00:00.000Z " + body
        );

        List<CopilotRequest> result = parseLog(log);
        assertEquals(1, result.size(), "Tek kayıt beklenir");
        CopilotRequest r = result.get(0);

        assertTrue(r.inputTokens() > 0, "Format 1 POST body'si BPE sayımı > 0 olmalı");
    }
}

package io.copilotlens.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * TokenCounter regression testleri.
 *
 * <p>JUnit bağımlılığı olmadan yazıldı (proje Maven/Gradle kullanmıyor).
 * <pre>java -ea -cp out:lib/jtokkit-1.1.0.jar:test io.copilotlens.analyzer.TokenCounterTest</pre>
 */
public class TokenCounterTest {

    private static int passed = 0, failed = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        run("forModel_gpt4o_picksO200k",
                TokenCounterTest::forModel_gpt4o_picksO200k);
        run("forModel_gpt41Copilot_picksO200k",
                TokenCounterTest::forModel_gpt41Copilot_picksO200k);
        run("forModel_o1_picksO200k",
                TokenCounterTest::forModel_o1_picksO200k);
        run("forModel_gpt4_picksCl100k",
                TokenCounterTest::forModel_gpt4_picksCl100k);
        run("forModel_gpt35_picksCl100k",
                TokenCounterTest::forModel_gpt35_picksCl100k);
        run("forModel_unknown_fallsBackToCl100k",
                TokenCounterTest::forModel_unknown_fallsBackToCl100k);
        run("forModel_null_fallsBackToCl100k",
                TokenCounterTest::forModel_null_fallsBackToCl100k);
        run("forModel_exactMatchFromJtokkit",
                TokenCounterTest::forModel_exactMatchFromJtokkit);
        run("count_returnsPositiveForNonEmpty",
                TokenCounterTest::count_returnsPositiveForNonEmpty);
        run("estimateFromChars_emptyReturnsZero",
                TokenCounterTest::estimateFromChars_emptyReturnsZero);
        run("estimateFromChars_proseIsAboutFourCharsPerToken",
                TokenCounterTest::estimateFromChars_proseIsAboutFourCharsPerToken);
        run("estimateFromChars_codeIsDenserThanProse",
                TokenCounterTest::estimateFromChars_codeIsDenserThanProse);
        run("estimateFromChars_jsonIsEvenDenser",
                TokenCounterTest::estimateFromChars_jsonIsEvenDenser);

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

    private static void assertTrue(boolean cond) {
        assertTrue(cond, "assertTrue failed");
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but got '" + actual + "'");
        }
    }

    private static void assertEquals(String expected, String actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " (expected '" + expected + "', got '" + actual + "')");
        }
    }

    // ---------- Test cases ----------

    static void forModel_gpt4o_picksO200k() {
        TokenCounter c = TokenCounter.forModel("gpt-4o-mini-2024-07-18");
        assertEquals("o200k_base", c.encodingFamily(),
                "gpt-4o için o200k_base beklenir");
    }

    static void forModel_gpt41Copilot_picksO200k() {
        TokenCounter c = TokenCounter.forModel("gpt-41-copilot");
        assertEquals("o200k_base", c.encodingFamily(),
                "gpt-41-copilot için o200k_base beklenir");
    }

    static void forModel_o1_picksO200k() {
        TokenCounter c = TokenCounter.forModel("o1-preview");
        assertEquals("o200k_base", c.encodingFamily(),
                "o1-preview için o200k_base beklenir");
    }

    static void forModel_gpt4_picksCl100k() {
        TokenCounter c = TokenCounter.forModel("gpt-4");
        assertEquals("cl100k_base", c.encodingFamily(),
                "gpt-4 için cl100k_base beklenir");
    }

    static void forModel_gpt35_picksCl100k() {
        TokenCounter c = TokenCounter.forModel("gpt-3.5-turbo");
        assertEquals("cl100k_base", c.encodingFamily(),
                "gpt-3.5-turbo için cl100k_base beklenir");
    }

    static void forModel_unknown_fallsBackToCl100k() {
        TokenCounter c = TokenCounter.forModel("unknown-model-xyz");
        assertEquals("cl100k_base", c.encodingFamily(),
                "Bilinmeyen model cl100k_base'e düşmeli");
    }

    static void forModel_null_fallsBackToCl100k() {
        TokenCounter c = TokenCounter.forModel(null);
        assertEquals("cl100k_base", c.encodingFamily(),
                "null model cl100k_base'e düşmeli");
    }

    static void forModel_exactMatchFromJtokkit() {
        // jtokkit'in kendi registry'sinde kesin isim olarak geçen bir model
        TokenCounter c = TokenCounter.forModel("gpt-4o");
        assertTrue(c.modelLabel() != null && !c.modelLabel().isEmpty(),
                "Exact match sonrası modelLabel boş olmamalı");
        assertEquals("o200k_base", c.encodingFamily());
    }

    static void count_returnsPositiveForNonEmpty() {
        TokenCounter c = TokenCounter.forModel("gpt-4o");
        assertTrue(c.count("Hello, world!") > 0, "Non-empty text için > 0 beklenir");
        assertTrue(c.count("") == 0, "Boş string için 0 beklenir");
        assertTrue(c.count(null) == 0, "null için 0 beklenir");
    }

    static void estimateFromChars_emptyReturnsZero() {
        assertTrue(TokenCounter.estimateFromChars(null) == 0);
        assertTrue(TokenCounter.estimateFromChars("") == 0);
    }

    static void estimateFromChars_proseIsAboutFourCharsPerToken() {
        // ~25 karakterlik İngilizce → ~5-7 token civarı beklenir
        String prose = "The quick brown fox jumps";
        int estimate = TokenCounter.estimateFromChars(prose);
        assertTrue(estimate >= 4 && estimate <= 8,
                "Prose için 4-8 arası beklenir, geldi: " + estimate);
    }

    static void estimateFromChars_codeIsDenserThanProse() {
        String prose = "Hello world this is plain text";
        String code  = "function foo() { return x[0]; }";
        int proseEst = TokenCounter.estimateFromChars(prose);
        int codeEst  = TokenCounter.estimateFromChars(code);
        // Code, prose'dan daha yüksek token/char oranına sahip olmalı
        assertTrue(codeEst > proseEst,
                "Code (" + codeEst + ") prose'dan (" + proseEst + ") daha yoğun olmalı");
    }

    static void estimateFromChars_jsonIsEvenDenser() {
        String json = "{\"key\":\"value\",\"arr\":[1,2,3],\"nested\":{\"a\":true,\"b\":false}}";
        String prose = "key value arr one two three nested a true b false";
        int jsonEst = TokenCounter.estimateFromChars(json);
        int proseEst = TokenCounter.estimateFromChars(prose);
        assertTrue(jsonEst > proseEst,
                "JSON (" + jsonEst + ") prose'dan (" + proseEst + ") daha yoğun olmalı");
    }
}

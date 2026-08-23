package io.copilotlens.analyzer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.ModelType;

/**
 * BPE tabanlı token sayımı. OpenAI tiktoken'ın Java port'u (jtokkit) kullanılır.
 * Copilot arka planda GPT-4 ailesi modeller kullanır; tahmin için GPT_4 yeterli.
 */
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = Encodings.newDefaultEncodingRegistry()
                .getEncodingForModel(ModelType.GPT_4);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }

    /**
     * Karakter sayısından hızlı tahmin (regex/parse öncesi upper bound).
     */
    public static int estimateFromChars(String text) {
        if (text == null) return 0;
        // İngilizce ~4 char/token, Java/kod daha yoğun -> 3.5
        return (int) Math.ceil(text.length() / 3.5);
    }
}

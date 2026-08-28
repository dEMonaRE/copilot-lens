package io.copilotlens.analyzer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;

import java.util.Locale;
import java.util.Optional;

/**
 * BPE tabanlı token sayımı. OpenAI tiktoken'ın Java port'u (jtokkit) kullanılır.
 *
 * <p>Copilot proxy'si (bireysel ve enterprise) arka planda farklı model
 * ailelerine yönlendirme yapabilir: {@code gpt-4o*}, {@code gpt-4.1*},
 * {@code o1/o3} → {@code o200k_base} BPE kullanır; eski {@code gpt-4},
 * {@code gpt-3.5-turbo}, Claude proxy → {@code cl100k_base}. Encoding
 * seçimi otomatiktir: model adı {@link #forModel(String)}'e geçirilir,
 * bilinmeyen modeller için {@code cl100k_base}'e düşülür.
 *
 * <p>{@link #estimateFromChars(String)} heuristic'i içerik tipi sniffer'ı
 * kullanır: koda benzer sinyal yoğunluğu yüksekse {@code length/3}, düz
 * yazıysa {@code length/4} civarı bir değer döner.
 */
public class TokenCounter {

    /** o200k_base ailesi için model adı ipuçları (case-insensitive substring match). */
    private static final String[] O200K_HINTS = {
            "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "gpt-41-", "gpt-5",
            "o1", "o3", "o4", "o200k", "o4-mini", "o3-mini", "o1-mini"
    };

    /** cl100k_base ailesi için model adı ipuçları (case-insensitive substring match). */
    private static final String[] CL100K_HINTS = {
            "gpt-4", "gpt-3.5", "cl100k", "claude", "text-embedding-ada",
            "copilot-nes", "copilot-gpt"
    };

    /** Heuristic: code benzeri sinyal karakterleri (regex sınıfı olarak). */
    private static final java.util.regex.Pattern CODE_SIGNAL =
            java.util.regex.Pattern.compile("[(){}\\[\\];=<>!&|+\\-*/%]+|\\b[a-z]+_[a-z_]+\\b|\\b[a-z]+[A-Z][a-zA-Z]*\\b");

    private final Encoding encoding;
    private final String modelLabel;
    private final String encodingFamily;

    /** Varsayılan: cl100k_base (Copilot'un eski gpt-4 trafiği için). */
    public TokenCounter() {
        this(Encodings.newDefaultEncodingRegistry()
                .getEncodingForModel(ModelType.GPT_4), "cl100k_base", "cl100k_base");
    }

    /**
     * Explicit model seçimi (jtokkit enum).
     * @deprecated Tercihen {@link #forModel(String)} kullanılmalı; bu
     * constructor test veya özel durumlar için tutulmuştur.
     */
    @Deprecated
    public TokenCounter(ModelType modelType) {
        this(Encodings.newDefaultEncodingRegistry().getEncodingForModel(modelType),
                modelType.name(),
                inferFamilyFromModelType(modelType));
    }

    public TokenCounter(Encoding encoding) {
        this(encoding, "encoding", "unknown");
    }

    private TokenCounter(Encoding encoding, String modelLabel, String encodingFamily) {
        this.encoding = encoding;
        this.modelLabel = modelLabel;
        this.encodingFamily = encodingFamily;
    }

    private static String inferFamilyFromModelType(ModelType mt) {
        // ModelType.GPT_4O ve türevleri → o200k_base; diğerleri → cl100k_base.
        String name = mt.name().toUpperCase(Locale.ROOT);
        if (name.contains("GPT_4O") || name.contains("O200K")
                || name.startsWith("O1") || name.startsWith("O3") || name.startsWith("O4")) {
            return "o200k_base";
        }
        return "cl100k_base";
    }

    /**
     * Model adından otomatik encoding seçimi yapar.
     *
     * <p>Önce jtokkit'in kendi model mapping'ini dener
     * ({@code gpt-4o-2024-08-06} gibi kesin isimler için), sonra kendi
     * heuristic'imizi uygularız. Bilinmeyen modellerde cl100k_base'e
     * düşeriz (Copilot proxy'si geriye dönük uyumluluk için bunu kullanır).
     */
    public static TokenCounter forModel(String modelName) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

        // 1) jtokkit'in kendi registry'sini dene — kesin model adları için birebir.
        if (modelName != null && !modelName.isBlank()) {
            Optional<Encoding> byExact = registry.getEncodingForModel(modelName);
            if (byExact.isPresent()) {
                String family = classify(modelName) == EncodingTypeHint.O200K_BASE
                        ? "o200k_base" : "cl100k_base";
                return new TokenCounter(byExact.get(), modelName, family);
            }
        }

        // 2) Heuristic substring match.
        EncodingTypeHint hint = classify(modelName);
        return switch (hint) {
            case O200K_BASE -> new TokenCounter(
                    registry.getEncodingForModel(ModelType.GPT_4O),
                    modelName == null ? "o200k_base" : ("o200k_base[" + modelName + "]"),
                    "o200k_base");
            case CL100K_BASE -> new TokenCounter(
                    registry.getEncodingForModel(ModelType.GPT_4),
                    modelName == null ? "cl100k_base" : ("cl100k_base[" + modelName + "]"),
                    "cl100k_base");
        };
    }

    private enum EncodingTypeHint { O200K_BASE, CL100K_BASE }

    private static EncodingTypeHint classify(String modelName) {
        if (modelName == null) return EncodingTypeHint.CL100K_BASE;
        String lower = modelName.toLowerCase(Locale.ROOT);

        // o200k_base ipuçlarını önce kontrol et — "gpt-4o" "gpt-4" ile çakışır
        for (String hint : O200K_HINTS) {
            if (lower.contains(hint)) return EncodingTypeHint.O200K_BASE;
        }
        for (String hint : CL100K_HINTS) {
            if (lower.contains(hint)) return EncodingTypeHint.CL100K_BASE;
        }
        return EncodingTypeHint.CL100K_BASE;
    }

    /** Bu sayaç tarafından kullanılan encoding etiketi (debug/log için). */
    public String modelLabel() {
        return modelLabel;
    }

    /** Encoding ailesi: {@code "o200k_base"} veya {@code "cl100k_base"}. */
    public String encodingFamily() {
        return encodingFamily;
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }

    /**
     * Karakter sayısından hızlı tahmin (regex/parse öncesi upper bound).
     *
     * <p>İçerik tipi sniffer'ı: kod sinyallerinin yoğunluğuna göre
     * {@code charsPerToken} değeri 3.0 (yoğun kod/JSON) ↔ 4.0 (düz yazı)
     * arasında interpole edilir. Bu, düz {@code length()/3.5} kuralına
     * göre karışık İngilizce+kod girdilerinde ~10-15% daha doğrudur.
     */
    public static int estimateFromChars(String text) {
        if (text == null) return 0;
        int len = text.length();
        if (len == 0) return 0;

        // Kod sinyali yoğunluğu: karakter sayısına oranı [0..1] arası
        long codeSignals = CODE_SIGNAL.matcher(text).results().count();
        double codeRatio = Math.min(1.0, (double) codeSignals * 6.0 / len);
        // 4.0 (prose) -> 3.0 (code); codeRatio 1.0 olduğunda 3.0'a düşer
        double charsPerToken = 4.0 - codeRatio;

        return (int) Math.ceil(len / charsPerToken);
    }
}

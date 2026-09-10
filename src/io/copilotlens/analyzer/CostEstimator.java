package io.copilotlens.analyzer;

import io.copilotlens.config.CopilotLensConfig;
import io.copilotlens.parser.CopilotRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provider-API cost estimation for the parsed Copilot traffic.
 *
 * <p>GitHub Copilot itself does NOT bill on tokens (it bills on premium
 * requests). The numbers this class produces reflect what you would have
 * paid if you had called the underlying provider API directly with the
 * same token volume. Useful as an upper bound for "what does my Copilot
 * usage cost at raw API rates?".
 *
 * <p>Pricing defaults are hardcoded for ~10 common models (USD per
 * 1M tokens, both directions). Overrides via config:
 * <pre>
 *   cost.input.gpt-4o=2.50
 *   cost.output.gpt-4o=10.00
 * </pre>
 * Ponytail: hardcoded table is fine — pricing moves quarterly at most,
 * and the user's own config can pin current rates.
 */
public class CostEstimator {

    /**
     * USD per 1M tokens for common models. Lowercase keys; substring match.
     * Source: vendor pricing pages as of 2026-09-10. Ponytail: refresh
     * quarterly or when adding a new vendor; override via config for
     * negotiated rates.
     */
    private static final Map<String, double[]> DEFAULT_PRICES;
    static {
        Map<String, double[]> m = new LinkedHashMap<>();
        // [inputPerMTok, outputPerMTok]
        // Zhipu AI
        m.put("glm-4.7-flash",     new double[] { 0.00, 0.00 }); // free tier
        // Amazon
        m.put("nova-micro",        new double[] { 0.035, 0.14 });
        // Alibaba
        m.put("qwen-3.7-flash",    new double[] { 0.03,  0.13 });
        // OpenAI
        m.put("gpt-5-nano",        new double[] { 0.05,  0.40 });
        m.put("gpt-5-mini",        new double[] { 0.25,  2.00 });
        m.put("gpt-5",             new double[] { 1.25, 10.00 });
        // Google
        m.put("gemini-2.5-flash-lite", new double[] { 0.10, 0.40 });
        m.put("gemini-3.6-flash",      new double[] { 1.50, 7.50 });
        // Meta
        m.put("llama-4-scout",     new double[] { 0.17,  0.66 });
        m.put("llama-4-maverick",  new double[] { 0.24,  0.97 });
        // Anthropic
        m.put("claude-haiku-4.5",  new double[] { 1.00,  5.00 });
        m.put("claude-sonnet-5",   new double[] { 2.00, 10.00 });
        m.put("claude-opus-5",     new double[] { 5.00, 25.00 });
        // xAI
        m.put("grok-4.5",          new double[] { 2.00,  6.00 });
        DEFAULT_PRICES = m;
    }

    /** Per-request cost record. */
    public record Cost(LocalDate date, String model, int inputTokens, int outputTokens,
                       double inputCost, double outputCost) {
        public double totalCost() { return inputCost + outputCost; }
    }

    /** Aggregated cost row for one period bucket. */
    public record CostBreakdown(String label, double inputCost, double outputCost) {
        public double totalCost() { return inputCost + outputCost; }
    }

    private final CopilotLensConfig config;

    public CostEstimator() { this(CopilotLensConfig.load()); }

    public CostEstimator(CopilotLensConfig config) { this.config = config; }

    /**
     * Estimate per-request cost. Requests with no model or no token data
     * contribute 0; they're summarised in the row count anyway.
     */
    public List<Cost> estimate(List<CopilotRequest> requests) {
        List<Cost> out = new ArrayList<>(requests.size());
        for (CopilotRequest r : requests) {
            String model = r.model();
            if (model == null || model.isBlank()) continue;
            double[] price = priceFor(model);
            double in = (r.inputTokens()  / 1_000_000.0) * price[0];
            double outCost = (r.outputTokens() / 1_000_000.0) * price[1];
            out.add(new Cost(r.timestamp().toLocalDate(), model,
                    r.inputTokens(), r.outputTokens(), in, outCost));
        }
        return out;
    }

    /** Aggregate by day. */
    public List<CostBreakdown> aggregateDaily(List<Cost> costs) {
        return aggregate(costs, TrendAggregator.Period.DAILY);
    }

    /** Aggregate by ISO week. */
    public List<CostBreakdown> aggregateWeekly(List<Cost> costs) {
        return aggregate(costs, TrendAggregator.Period.WEEKLY);
    }

    /** Aggregate by calendar month. */
    public List<CostBreakdown> aggregateMonthly(List<Cost> costs) {
        return aggregate(costs, TrendAggregator.Period.MONTHLY);
    }

    /**
     * Sum input/output cost into period buckets. Bucket key comes from
     * {@link TrendAggregator#bucketKey}; each {@link Cost} carries its
     * own date so the aggregation is order-independent.
     */
    private List<CostBreakdown> aggregate(List<Cost> costs,
                                          TrendAggregator.Period period) {
        TreeMap<String, double[]> buckets = new TreeMap<>();
        for (Cost c : costs) {
            String key = TrendAggregator.bucketKey(c.date(), period);
            double[] agg = buckets.computeIfAbsent(key, k -> new double[2]);
            agg[0] += c.inputCost();
            agg[1] += c.outputCost();
        }
        List<CostBreakdown> rows = new ArrayList<>(buckets.size());
        for (var e : buckets.entrySet()) {
            rows.add(new CostBreakdown(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return rows;
    }

    /**
     * Look up [inputPerMTok, outputPerMTok] for a model. Overrides via
     * {@code cost.input.<model>} / {@code cost.output.<model>} win;
     * otherwise substring-match the hardcoded table; otherwise 0.
     */
    private double[] priceFor(String model) {
        String lower = model.toLowerCase(Locale.ROOT);
        // 1) explicit config override
        String inOverride = config.get("cost.input." + lower);
        String outOverride = config.get("cost.output." + lower);
        if (inOverride != null && !inOverride.isBlank()
                && outOverride != null && !outOverride.isBlank()) {
            try {
                return new double[] {
                        Double.parseDouble(inOverride),
                        Double.parseDouble(outOverride)
                };
            } catch (NumberFormatException ignored) {
                // fall through to defaults
            }
        }
        // 2) hardcoded substring match
        for (var e : DEFAULT_PRICES.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return new double[] { 0.0, 0.0 };
    }
}

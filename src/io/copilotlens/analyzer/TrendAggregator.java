package io.copilotlens.analyzer;

import io.copilotlens.snapshot.Snapshot;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Aggregates a list of {@link Snapshot}s into ordered trend points.
 *
 * <p>Three grouping modes:
 * <ul>
 *   <li>{@code DAILY}  — one point per snapshot date</li>
 *   <li>{@code WEEKLY} — one point per ISO week (YYYY-Www)</li>
 *   <li>{@code MONTHLY} — one point per calendar month (YYYY-MM)</li>
 * </ul>
 *
 * <p>Within each bucket the request count, input tokens, and output tokens
 * are summed. The result is ordered by bucket label ascending.
 */
public class TrendAggregator {

    public enum Period { DAILY, WEEKLY, MONTHLY }

    public record TrendPoint(
            String label,         // YYYY-MM-DD | YYYY-Www | YYYY-MM
            int requestCount,
            int totalInputTokens,
            int totalOutputTokens
    ) {
        public int totalTokens() {
            return totalInputTokens + totalOutputTokens;
        }
    }

    public List<TrendPoint> aggregate(List<Snapshot> snapshots, Period period) {
        if (snapshots.isEmpty()) return List.of();

        TreeMap<String, int[]> buckets = new TreeMap<>();
        for (Snapshot s : snapshots) {
            String key = bucketKey(s.localDate(), period);
            int[] agg = buckets.computeIfAbsent(key, k -> new int[3]);
            agg[0] += s.requestCount();
            agg[1] += s.totalInputTokens();
            agg[2] += s.totalOutputTokens();
        }

        List<TrendPoint> points = new ArrayList<>(buckets.size());
        for (var e : buckets.entrySet()) {
            int[] a = e.getValue();
            points.add(new TrendPoint(e.getKey(), a[0], a[1], a[2]));
        }
        return points;
    }

    /** Trim a list of points to the most recent {@code n} entries. */
    public List<TrendPoint> limit(List<TrendPoint> points, int n) {
        if (points.size() <= n) return points;
        return new ArrayList<>(points.subList(points.size() - n, points.size()));
    }

    private static String bucketKey(LocalDate date, Period period) {
        return switch (period) {
            case DAILY   -> date.toString();
            case WEEKLY  -> isoWeekKey(date);
            case MONTHLY -> String.format(Locale.ROOT, "%04d-%02d",
                    date.getYear(), date.getMonthValue());
        };
    }

    private static String isoWeekKey(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    public static Period parse(String s) {
        if (s == null) return Period.DAILY;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "weekly", "week", "w"   -> Period.WEEKLY;
            case "monthly", "month", "m" -> Period.MONTHLY;
            default                       -> Period.DAILY;
        };
    }
}

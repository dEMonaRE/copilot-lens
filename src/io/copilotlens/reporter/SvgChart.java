package io.copilotlens.reporter;

import java.util.List;
import java.util.Map;

/**
 * Inline SVG chart primitives. Zero JS, zero external resources; the
 * viewer renders them natively. Colors come from CSS variables
 * ({@code --accent}, {@code --ok}, {@code --warn}) so the host page's
 * dark-mode rule recolors them automatically.
 *
 * <p>All functions return a self-contained {@code <svg>} element with
 * width/height/padding baked in. Embed them straight into HTML.
 */
public class SvgChart {

    /** CSS var names for series palette. */
    private static final String[] PALETTE = { "--accent", "--ok", "--warn", "#8957e5", "#00b8d4" };

    /**
     * Vertical bar chart with one bar per label.
     *
     * @param labels  x-axis labels (one per bar)
     * @param values  bar heights
     * @param w       total SVG width
     * @param h       total SVG height
     */
    public static String barChart(List<String> labels, List<Integer> values, int w, int h) {
        if (labels == null || labels.isEmpty()) return "";
        int padBottom = 22, padTop = 8, padLeft = 32, padRight = 8;
        int innerW = w - padLeft - padRight;
        int innerH = h - padBottom - padTop;
        int n = labels.size();
        int barSlot = innerW / Math.max(1, n);
        int barWidth = Math.max(2, barSlot - 4);
        int max = Math.max(1, values.stream().mapToInt(Integer::intValue).max().orElse(1));

        StringBuilder s = new StringBuilder(w * 2);
        s.append("<svg viewBox='0 0 ").append(w).append(' ').append(h)
         .append("' xmlns='http://www.w3.org/2000/svg' style='font-family:inherit'>");
        // axis baseline
        s.append("<line x1='").append(padLeft).append("' y1='").append(padTop + innerH)
         .append("' x2='").append(w - padRight).append("' y2='").append(padTop + innerH)
         .append("' stroke='var(--border)'/>");
        // y-axis tick at 50% and 100%
        for (int tick : new int[]{max / 2, max}) {
            int y = padTop + innerH - (int) ((long) innerH * tick / max);
            s.append("<line x1='").append(padLeft).append("' y1='").append(y)
             .append("' x2='").append(w - padRight).append("' y2='").append(y)
             .append("' stroke='var(--border)' stroke-dasharray='2 3' opacity='0.5'/>");
            s.append("<text x='").append(padLeft - 4).append("' y='").append(y + 4)
             .append("' text-anchor='end' font-size='10' fill='var(--muted)'>")
             .append(tick).append("</text>");
        }
        // bars
        for (int i = 0; i < n; i++) {
            int v = values.get(i);
            int barH = (int) ((long) innerH * v / max);
            int x = padLeft + i * barSlot + (barSlot - barWidth) / 2;
            int y = padTop + innerH - barH;
            s.append("<rect x='").append(x).append("' y='").append(y)
             .append("' width='").append(barWidth).append("' height='").append(barH)
             .append("' fill='var(--accent)'><title>")
             .append(labels.get(i)).append(": ").append(v).append("</title></rect>");
            s.append("<text x='").append(x + barWidth / 2).append("' y='").append(padTop + innerH + 14)
             .append("' text-anchor='middle' font-size='10' fill='var(--muted)'>")
             .append(truncate(labels.get(i), 10)).append("</text>");
        }
        s.append("</svg>");
        return s.toString();
    }

    /**
     * Multi-series line chart over a shared date axis.
     *
     * @param series legend name → ordered values (oldest first)
     */
    public static String lineChart(Map<String, List<Integer>> series, int w, int h,
                                   List<String> xLabels) {
        if (series == null || series.isEmpty()) return "";
        int padBottom = 24, padTop = 8, padLeft = 36, padRight = 12;
        int innerW = w - padLeft - padRight;
        int innerH = h - padBottom - padTop;
        int max = 1;
        for (var e : series.entrySet()) for (int v : e.getValue()) max = Math.max(max, v);

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox='0 0 ").append(w).append(' ').append(h)
         .append("' xmlns='http://www.w3.org/2000/svg' style='font-family:inherit'>");
        // y ticks
        for (int tick : new int[]{max / 2, max}) {
            int y = padTop + innerH - (int) ((long) innerH * tick / max);
            s.append("<line x1='").append(padLeft).append("' y1='").append(y)
             .append("' x2='").append(w - padRight).append("' y2='").append(y)
             .append("' stroke='var(--border)' stroke-dasharray='2 3' opacity='0.5'/>");
            s.append("<text x='").append(padLeft - 4).append("' y='").append(y + 4)
             .append("' text-anchor='end' font-size='10' fill='var(--muted)'>").append(tick).append("</text>");
        }

        int colorIdx = 0;
        for (var e : series.entrySet()) {
            String color = PALETTE[colorIdx++ % PALETTE.length];
            List<Integer> vals = e.getValue();
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < vals.size(); i++) {
                int x = padLeft + (vals.size() == 1 ? innerW / 2 : i * innerW / (vals.size() - 1));
                int y = padTop + innerH - (int) ((long) innerH * vals.get(i) / max);
                if (i > 0) pts.append(' ');
                pts.append(x).append(',').append(y);
            }
            s.append("<polyline points='").append(pts)
             .append("' fill='none' stroke='").append(color)
             .append("' stroke-width='2'><title>").append(e.getKey()).append("</title></polyline>");
        }
        // legend (top-right, no overlap with chart)
        int lx = w - padRight - 90;
        int ly = padTop + 4;
        colorIdx = 0;
        for (String name : series.keySet()) {
            String color = PALETTE[colorIdx++ % PALETTE.length];
            s.append("<rect x='").append(lx).append("' y='").append(ly)
             .append("' width='10' height='10' fill='").append(color).append("'/>");
            s.append("<text x='").append(lx + 14).append("' y='").append(ly + 9)
             .append("' font-size='10' fill='var(--text)'>").append(truncate(name, 10)).append("</text>");
            ly += 14;
        }
        // x labels: first, middle, last
        if (xLabels != null && !xLabels.isEmpty()) {
            int n = xLabels.size();
            for (int idx : new int[]{0, n / 2, n - 1}) {
                if (idx < 0 || idx >= n) continue;
                int x = padLeft + (n == 1 ? innerW / 2 : idx * innerW / (n - 1));
                s.append("<text x='").append(x).append("' y='").append(padTop + innerH + 16)
                 .append("' text-anchor='middle' font-size='10' fill='var(--muted)'>")
                 .append(truncate(xLabels.get(idx), 10)).append("</text>");
            }
        }
        s.append("</svg>");
        return s.toString();
    }

    /**
     * Donut chart with the largest slice's key in the center.
     */
    public static String donut(Map<String, Integer> distribution, int size) {
        if (distribution == null || distribution.isEmpty()) return "";
        int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return "";
        int r = size / 2;
        int cx = size / 2, cy = size / 2;
        double innerR = r * 0.55;

        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox='0 0 ").append(size).append(' ').append(size)
         .append("' xmlns='http://www.w3.org/2000/svg' style='font-family:inherit'>");
        double angle = -Math.PI / 2; // start at top
        int colorIdx = 0;
        String topKey = "";
        int topVal = -1;
        for (var e : distribution.entrySet()) {
            if (e.getValue() > topVal) { topVal = e.getValue(); topKey = e.getKey(); }
        }
        for (var e : distribution.entrySet()) {
            double slice = (double) e.getValue() / total * 2 * Math.PI;
            double a1 = angle, a2 = angle + slice;
            String color = PALETTE[colorIdx++ % PALETTE.length];
            s.append(arcPath(cx, cy, r, innerR, a1, a2, color, e.getKey(), e.getValue()));
            angle = a2;
        }
        // center text
        s.append("<text x='").append(cx).append("' y='").append(cy + 4)
         .append("' text-anchor='middle' font-size='12' font-weight='600' fill='var(--text)'>")
         .append(escape(truncate(topKey, 10))).append("</text>");
        s.append("</svg>");
        return s.toString();
    }

    /**
     * Tiny inline sparkline (no axes, no labels). Last point gets a
     * circle marker for "where we are now".
     */
    public static String sparkline(List<Integer> values, int w, int h) {
        if (values == null || values.isEmpty()) return "";
        int max = Math.max(1, values.stream().mapToInt(Integer::intValue).max().orElse(1));
        int pad = 2;
        int innerW = w - 2 * pad;
        int innerH = h - 2 * pad;
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            int x = pad + (values.size() == 1 ? innerW / 2 : i * innerW / (values.size() - 1));
            int y = pad + innerH - (int) ((long) innerH * values.get(i) / max);
            if (i > 0) pts.append(' ');
            pts.append(x).append(',').append(y);
        }
        StringBuilder s = new StringBuilder();
        s.append("<svg viewBox='0 0 ").append(w).append(' ').append(h)
         .append("' xmlns='http://www.w3.org/2000/svg'>");
        s.append("<polyline points='").append(pts)
         .append("' fill='none' stroke='var(--accent)' stroke-width='1.5'/>");
        // last-point circle
        int lastX = pad + (values.size() == 1 ? innerW / 2 : (values.size() - 1) * innerW / (values.size() - 1));
        int lastY = pad + innerH - (int) ((long) innerH * values.get(values.size() - 1) / max);
        s.append("<circle cx='").append(lastX).append("' cy='").append(lastY)
         .append("' r='2' fill='var(--accent)'/>");
        s.append("</svg>");
        return s.toString();
    }

    /** Build an SVG arc path between two angles. */
    private static String arcPath(int cx, int cy, double r, double innerR,
                                  double a1, double a2, String color, String label, int val) {
        double x1 = cx + r * Math.cos(a1), y1 = cy + r * Math.sin(a1);
        double x2 = cx + r * Math.cos(a2), y2 = cy + r * Math.sin(a2);
        double ix1 = cx + innerR * Math.cos(a2), iy1 = cy + innerR * Math.sin(a2);
        double ix2 = cx + innerR * Math.cos(a1), iy2 = cy + innerR * Math.sin(a1);
        int largeArc = (a2 - a1) > Math.PI ? 1 : 0;
        return "<path d='M" + x1 + " " + y1
                + " A" + r + " " + r + " 0 " + largeArc + " 1 " + x2 + " " + y2
                + " L" + ix1 + " " + iy1
                + " A" + innerR + " " + innerR + " 0 " + largeArc + " 0 " + ix2 + " " + iy2
                + " Z' fill='" + color + "'><title>" + escape(label) + ": " + val + "</title></path>";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

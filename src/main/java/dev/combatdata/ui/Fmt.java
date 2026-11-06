package dev.combatdata.ui;

import dev.combatdata.client.Recordings;
import static dev.combatdata.core.Lang.t;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Fmt {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE_EN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Fmt() {
    }

    // These run for every visible row each frame, so they build strings directly instead of parsing a String.format pattern.

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        StringBuilder b = new StringBuilder(8);
        pad(b, s / 60, 2).append(':');
        return pad(b, s % 60, 2).toString();
    }

    static String clockMs(long ms) {
        long v = Math.max(0, ms), s = v / 1000;
        StringBuilder b = new StringBuilder(12);
        pad(b, s / 60, 2).append(':');
        pad(b, s % 60, 2).append('.');
        return pad(b, v % 1000, 3).toString();
    }

    private static StringBuilder pad(StringBuilder b, long n, int width) {
        String s = Long.toString(n);
        for (int i = s.length(); i < width; i++) b.append('0');
        return b.append(s);
    }

    private static final long[] POW10 = {1, 10, 100, 1000, 10_000, 100_000, 1_000_000};

    static String num(double v, int digits) {
        if (!Double.isFinite(v)) return "—";
        return fixed(new StringBuilder(12), v, digits, false).toString();
    }

    static String signed(double v, int digits) {
        if (!Double.isFinite(v)) return "—";
        return fixed(new StringBuilder(12), v, digits, true).toString();
    }

    static String pct(double v) {
        return Double.isFinite(v) ? fixed(new StringBuilder(8), v, 0, false).append('%').toString() : "—";
    }

    /** Like "%.Nf" (with plus: "%+.Nf"); values too large for a long fall back to String.format. */
    private static StringBuilder fixed(StringBuilder b, double v, int digits, boolean plus) {
        if (digits < 0 || digits >= POW10.length || Math.abs(v) >= 1e12) {
            return b.append(String.format(Locale.ROOT, (plus ? "%+." : "%.") + Math.max(0, digits) + "f", v));
        }
        // The nudge makes halves like 0.145 round up as in String.format, which rounds the shortest decimal form.
        long p = POW10[digits], scaled = Math.round(Math.abs(v) * p * (1 + 1e-14));
        if (v < 0 || (v == 0 && 1 / v < 0)) b.append('-');
        else if (plus) b.append('+');
        b.append(scaled / p);
        if (digits > 0) pad(b.append('.'), scaled % p, digits);
        return b;
    }

    static String date(long epochMs) {
        return (dev.combatdata.core.Lang.english() ? DATE_EN : DATE).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    static String title(Recordings.Entry e) {
        if (e.header() == null) return e.stem() + t(" (не читается)", " (unreadable)");
        if (e.defaultName()) {
            String server = e.header().servers().isEmpty() ? t("сервер неизвестен", "unknown server") : String.join(", ", e.header().servers());
            return server;
        }
        return e.stem();
    }

    static String meta(Recordings.Entry e) {
        if (e.header() == null) return t("Файл повреждён или создан другой версией", "File is damaged or made by another version");
        var h = e.header();
        StringBuilder sb = new StringBuilder(clock(h.durationMs()));
        sb.append(t(" · ударов ", " · hits ")).append(h.hitsConfirmed()).append(t(" из ", " of ")).append(h.attacks());
        if (!e.defaultName() && !h.servers().isEmpty()) sb.append(" · ").append(String.join(", ", h.servers()));
        if (!h.opponents().isEmpty()) sb.append(" · ").append(String.join(", ", h.opponents()));
        return sb.toString();
    }

    /** Where the author's name sits in a line ending with meta(e): {from, to}, or null if they are not an opponent. */
    static int[] authorSpan(Recordings.Entry e, String line) {
        var h = e.header();
        if (h == null || h.opponents().isEmpty()) return null;
        int start = line.length() - String.join(", ", h.opponents()).length();
        for (int i = 0; i < h.opponents().size(); i++) {
            String name = h.opponents().get(i);
            if (dev.combatdata.core.Author.is(h.opponentId(i))) return new int[]{start, start + name.length()};
            start += name.length() + 2;
        }
        return null;
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024.0)) + t(" КБ", " KB");
        return String.format(Locale.ROOT, t("%.1f МБ", "%.1f MB"), bytes / 1048576.0);
    }

    static String when(Recordings.Entry e) {
        return date(e.header() != null ? e.header().wallStartMs() : e.modified());
    }
}

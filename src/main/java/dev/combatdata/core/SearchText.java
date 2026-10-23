package dev.combatdata.core;

import java.util.Locale;

/**
 * Menu search ranking. Every word of the query must occur somewhere; a word scores by where it is found:
 * the label beats the same label in the other language, which beats the hint and the place in the menu.
 */
public final class SearchText {
    private SearchText() {
    }

    /** Lower case with the Cyrillic yo folded into ie; the length stays the same, so match positions stay valid. */
    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public static String[] words(String query) {
        String q = norm(query).trim();
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    /** -1 if some word is missing; otherwise higher is better. */
    public static int score(String query, String label, String alt, String extra) {
        String[] ws = words(query);
        if (ws.length == 0) return -1;
        String l = norm(label), a = norm(alt), e = norm(extra);
        int total = 0;
        for (String w : ws) {
            int s = Math.max(Math.max(in(l, w, 100, 80, 60, 45), in(a, w, 50, 40, 32, 25)), e.contains(w) ? 10 : -1);
            if (s < 0) return -1;
            total += s;
        }
        // Among equal matches the shorter label goes first: "Background" before "Background opacity".
        return total * 100 - Math.min(99, l.length());
    }

    private static int in(String text, String w, int exact, int start, int wordStart, int inside) {
        if (text.isEmpty()) return -1;
        if (text.equals(w)) return exact;
        if (text.startsWith(w)) return start;
        int i = text.indexOf(w);
        if (i < 0) return -1;
        return Character.isLetterOrDigit(text.charAt(i - 1)) ? inside : wordStart;
    }

    /** {start, end} of the first query word found in the label, for highlighting; null if none. */
    public static int[] span(String query, String label) {
        String l = norm(label);
        for (String w : words(query)) {
            int i = l.indexOf(w);
            if (i >= 0) return new int[]{i, i + w.length()};
        }
        return null;
    }
}

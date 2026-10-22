package dev.combatdata.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class RecordingQuery {
    public enum Sort {
        NEWEST, OLDEST, LONGEST, MOST_HITS, ACCURACY, NAME, LARGEST
    }

    public record Item(int index, String name, String text, long timeMs, long durationMs, int hits, int attacks,
                       long sizeBytes, boolean favorite, boolean unreadable) {
        /** Hit rate; -1 without attacks so such recordings sort last. */
        double accuracy() {
            return attacks > 0 ? (double) hits / attacks : -1;
        }
    }

    private RecordingQuery() {
    }

    public static boolean matches(String text, String query) {
        if (query == null || query.isBlank()) return true;
        String t = text.toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!t.contains(word)) return false;
        return true;
    }

    public static List<Item> apply(List<Item> items, String query, Sort sort, boolean favoritesOnly) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if ((!favoritesOnly || it.favorite()) && matches(it.text(), query)) out.add(it);
        Comparator<Item> byNewest = Comparator.comparingLong(Item::timeMs).reversed();
        Comparator<Item> c = switch (sort) {
            case NEWEST -> byNewest;
            case OLDEST -> Comparator.comparingLong(Item::timeMs);
            case LONGEST -> Comparator.comparingLong(Item::durationMs).reversed().thenComparing(byNewest);
            case MOST_HITS -> Comparator.comparingInt(Item::hits).reversed().thenComparing(byNewest);
            case ACCURACY -> Comparator.comparingDouble(Item::accuracy).reversed().thenComparing(byNewest);
            case NAME -> Comparator.comparing((Item i) -> i.name().toLowerCase(Locale.ROOT)).thenComparing(byNewest);
            case LARGEST -> Comparator.comparingLong(Item::sizeBytes).reversed().thenComparing(byNewest);
        };
        out.sort(c);
        return out;
    }
}

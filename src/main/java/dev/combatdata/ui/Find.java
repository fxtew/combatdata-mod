package dev.combatdata.ui;

import dev.combatdata.core.Lang;
import dev.combatdata.core.SearchText;
import dev.combatdata.core.Themes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Menu search. The index is not a hand-written list: tabs report each row as they lay it out (mark), and the index is
 * collected by laying out every section once, invisibly, in both languages. So whatever the menu shows can be found,
 * by its Russian or English name. The same mark() call highlights the row a result points to.
 */
final class Find {
    /** parent: the row to highlight instead when this one is hidden, e.g. options of an indicator that is off. */
    record Entry(MenuScreen.TabId tab, int section, String group, String label, String hint, String parent, String alt) {
        Entry withAlt(String a) {
            return new Entry(tab, section, group, label, hint, parent, a);
        }
    }

    record Result(Entry entry, int score) {
    }

    private static final long HOLD_MS = 900, FADE_MS = 1700;

    private List<Entry> index;
    private boolean indexEnglish;
    private List<Entry> collecting;
    private MenuScreen.TabId tab;
    private int section;
    private String group, parent;

    private Entry target;
    private boolean located, scrollPending;
    private int hitY, hitH, misses;
    private long shownAt;

    boolean needsIndex() {
        return index == null || indexEnglish != Lang.english();
    }

    void invalidate() {
        index = null;
    }

    void startCollect() {
        collecting = new ArrayList<>();
    }

    List<Entry> stopCollect() {
        List<Entry> out = collecting;
        collecting = null;
        return out;
    }

    void setIndex(List<Entry> current, List<Entry> other) {
        List<Entry> out = new ArrayList<>(current.size());
        // Both passes walk the same rows in the same order, so entries pair up by position.
        boolean paired = other != null && other.size() == current.size();
        for (int i = 0; i < current.size(); i++) {
            Entry e = current.get(i);
            out.add(paired ? e.withAlt(other.get(i).label() + " " + nz(other.get(i).hint())) : e);
        }
        index = out;
        indexEnglish = Lang.english();
    }

    /** Called by the menu before a tab draws, and by tabs for their sections and groups. */
    void at(MenuScreen.TabId tab, int section) {
        this.tab = tab;
        this.section = section;
        group = null;
        parent = null;
    }

    void group(String g) {
        group = g;
        parent = null;
    }

    void parent(String p) {
        parent = p;
    }

    void add(MenuScreen.TabId t, int s, String g, String label) {
        if (collecting != null) collecting.add(new Entry(t, s, g, label, null, null, null));
    }

    /** One row of a tab: indexed while collecting, highlighted when a search result points to it. */
    void mark(Ui ui, String label, String hint, int x, int y, int w, int h) {
        if (collecting != null) {
            collecting.add(new Entry(tab, section, group, label, hint, parent, null));
            return;
        }
        Entry t = target;
        if (t == null || t.tab() != tab || t.section() != section || !Objects.equals(t.group(), group) || !t.label().equals(label)) return;
        located = true;
        hitY = y;
        hitH = h;
        float f = strength();
        if (f <= 0) return;
        Draw.round(ui.g, x, y + 1, w, h - 2, Theme.R_CTRL, Themes.mix(Theme.PANEL, Theme.ACCENT, 0.3 * f));
        ui.g.fill(x, y + 3, x + 2, y + h - 3, Theme.alpha(Theme.ACCENT, Math.round(255 * f)));
    }

    private float strength() {
        long age = System.currentTimeMillis() - shownAt;
        if (age < HOLD_MS) return 1f;
        return Math.max(0f, 1f - (age - HOLD_MS) / (float) FADE_MS);
    }

    void show(Entry e) {
        target = e;
        located = false;
        scrollPending = true;
        misses = 0;
        shownAt = System.currentTimeMillis();
    }

    /** Called before a tab lays out its rows. */
    void beginFrame() {
        located = false;
    }

    /**
     * Called after a tab laid out its rows: how far to scroll so the found row sits in the middle of the view,
     * or 0. If the row was not drawn (hidden), the parent row is tried instead.
     */
    int scrollDelta(int viewTop, int viewH) {
        if (target == null || !scrollPending || target.tab() != tab) return 0;
        if (located) {
            scrollPending = false;
            shownAt = System.currentTimeMillis();
            return hitY + hitH / 2 - (viewTop + viewH / 2);
        }
        if (target.parent() != null) {
            target = new Entry(target.tab(), target.section(), target.group(), target.parent(), null, null, null);
        } else if (++misses > 3) {
            scrollPending = false;
        }
        return 0;
    }

    private String lastQuery;
    private List<Entry> lastIndex;
    private int lastLimit = -1, lastTotal;
    private List<Result> lastResults = List.of();

    /** Recomputed only when the query, the limit or the index changes, not every frame. */
    List<Result> search(String query, int limit) {
        if (query.equals(lastQuery) && limit == lastLimit && index == lastIndex) return lastResults;
        List<Result> out = new ArrayList<>();
        if (index != null) {
            for (Entry e : index) {
                int s = SearchText.score(query, e.label(), e.alt(), nz(e.hint()) + " " + nz(e.group()) + " " + MenuScreen.tabLabel(e.tab()));
                if (s >= 0) out.add(new Result(e, s));
            }
        }
        out.sort((a, b) -> Integer.compare(b.score(), a.score()));
        // The same row can be reached through several labels (a heading and its row): keep the best one.
        List<Result> unique = new ArrayList<>();
        for (Result r : out) {
            boolean dup = false;
            for (Result u : unique) {
                if (u.entry().tab() == r.entry().tab() && u.entry().section() == r.entry().section()
                        && u.entry().label().equals(r.entry().label()) && Objects.equals(u.entry().group(), r.entry().group())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) unique.add(r);
        }
        lastQuery = query;
        lastLimit = limit;
        lastIndex = index;
        lastTotal = unique.size();
        lastResults = unique.size() > limit ? List.copyOf(unique.subList(0, limit)) : unique;
        return lastResults;
    }

    /** Number of distinct matches of the last search. */
    int total() {
        return lastTotal;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

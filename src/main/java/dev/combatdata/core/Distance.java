package dev.combatdata.core;

import java.util.*;

public final class Distance {
    public static final long MAX_GAP_MS = 250;

    private Distance() {
    }

    public record Sample(long t, double d) {
    }

    public static List<Sample> series(List<Event> ev, UUID opp) {
        List<Sample> out = new ArrayList<>();
        if (opp == null) return out;
        for (Event e : ev) {
            if (!(e instanceof PositionEvent p)) continue;
            PositionEvent.Other o = p.find(opp);
            if (o != null) out.add(new Sample(p.t(), p.distanceTo(o)));
        }
        return out;
    }

    public static UUID mainOpponent(List<Event> ev) {
        Map<UUID, Integer> n = new HashMap<>();
        for (Event e : ev) {
            if (e instanceof AttackEvent a && a.outcome() == Outcome.HIT_CONFIRMED && a.target() != null) n.merge(a.target(), 1, Integer::sum);
            else if (e instanceof IncomingHitEvent h && h.attacker() != null) n.merge(h.attacker(), 1, Integer::sum);
        }
        UUID best = null;
        int bn = 0;
        for (Map.Entry<UUID, Integer> e : n.entrySet()) {
            if (e.getValue() > bn) {
                best = e.getKey();
                bn = e.getValue();
            }
        }
        return best;
    }

    public static double at(List<Sample> s, long t) {
        int lo = 0, hi = s.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).t() < t) lo = mid + 1;
            else hi = mid;
        }
        Sample next = lo < s.size() ? s.get(lo) : null, prev = lo > 0 ? s.get(lo - 1) : null;
        if (next != null && next.t() == t) return next.d();
        if (prev != null && next != null && next.t() - prev.t() <= MAX_GAP_MS) {
            double k = (double) (t - prev.t()) / (next.t() - prev.t());
            return prev.d() + (next.d() - prev.d()) * k;
        }
        if (prev != null && t - prev.t() <= MAX_GAP_MS / 2) return prev.d();
        if (next != null && next.t() - t <= MAX_GAP_MS / 2) return next.d();
        return Double.NaN;
    }
}

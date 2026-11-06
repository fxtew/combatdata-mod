package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Rating parameters along a recording: each server segment is rated by its server's profile. Parts start at the
 * segment time; before the first segment the first part applies.
 */
public final class Rules {
    /** profile is null for a server without a profile (or for fixed rules); oppRange NaN means "same as ours". */
    public record Part(long fromMs, String server, ServerProfile profile, JumpResetEvaluator.Params jump, int interpMs,
                       double oppRange) {
    }

    private final List<Part> parts;

    private Rules(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Rules single(JumpResetEvaluator.Params p, int interpMs) {
        return new Rules(List.of(new Part(0, null, null, p, interpMs, Double.NaN)));
    }

    public static Part part(long fromMs, String server, ServerProfile profile, Settings s) {
        if (profile == null) return new Part(fromMs, server, null, s.jumpParams(), s.interpMs, Double.NaN);
        return new Part(fromMs, server, profile, profile.jumpParams(s), profile.interpMs(s), profile.oppRange);
    }

    /** One profile for the whole recording, ignoring its segments (viewing "as if on another server"). */
    public static Rules with(ServerProfile profile, Settings s) {
        return new Rules(List.of(part(0, profile == null ? null : profile.address, profile, s)));
    }

    public static Rules of(List<Event> events, ServerProfiles profiles, Settings s) {
        List<Part> out = new ArrayList<>();
        for (Event e : events) {
            if (!(e instanceof SegmentEvent seg)) continue;
            String server = seg.server();
            if (!out.isEmpty() && java.util.Objects.equals(out.get(out.size() - 1).server(), server)) continue;
            out.add(part(seg.t(), server, profiles == null ? null : profiles.find(server), s));
        }
        if (out.isEmpty()) out.add(part(0, null, null, s));
        return new Rules(out);
    }

    public List<Part> parts() {
        return parts;
    }

    public Part at(long t) {
        int lo = 0, hi = parts.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (parts.get(mid).fromMs() <= t) lo = mid;
            else hi = mid - 1;
        }
        return parts.get(lo);
    }

    /** The part with the most hits dealt and taken: its window is shown on charts. */
    public Part main(List<Event> events) {
        if (parts.size() == 1) return parts.get(0);
        int[] n = new int[parts.size()];
        for (Event e : events) {
            if (!(e instanceof AttackEvent || e instanceof IncomingHitEvent)) continue;
            n[parts.indexOf(at(e.t()))]++;
        }
        int best = 0;
        for (int i = 1; i < n.length; i++) if (n[i] > n[best]) best = i;
        return parts.get(best);
    }

    /** Parameters that change the stats; used in cache keys. */
    public String key() {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) b.append(p.fromMs()).append(':').append(p.jump()).append(':').append(p.interpMs()).append(':')
                .append(p.oppRange()).append(';');
        return b.toString();
    }
}

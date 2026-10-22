package dev.combatdata.core;

import java.util.*;

/**
 * Applied at view time: recordings keep everything, filtered events are shown greyed out.
 * OTHER weapon means anything except sword and hand.
 */
public record StatFilter(WeaponView weapon, boolean noTeammates, boolean noNpc, boolean highPing, int pingLimitMs,
                         int minFightHits, boolean handNoTrade) {

    public enum WeaponView { ALL, SWORD, HAND, OTHER }

    public static final StatFilter NONE = new StatFilter(WeaponView.ALL, false, false, false, 150, 1, false);

    public enum Hidden { SWORD, AXE, MACE, SPEAR, HAND, OTHER_WEAPON, UNKNOWN_WEAPON, TEAMMATE, NPC, HIGH_PING }

    public record Applied(List<Event> kept, Map<Hidden, Integer> hidden) {
        public int hiddenTotal() {
            int n = 0;
            for (int v : hidden.values()) n += v;
            return n;
        }
    }

    public StatFilter withWeapon(WeaponView w) {
        return new StatFilter(w, noTeammates, noNpc, highPing, pingLimitMs, minFightHits, handNoTrade);
    }

    public static boolean matches(WeaponView v, Weapon w) {
        return switch (v) {
            case ALL -> true;
            case SWORD -> w == Weapon.SWORD;
            case HAND -> w == Weapon.HAND;
            case OTHER -> w != Weapon.SWORD && w != Weapon.HAND;
        };
    }

    public Hidden attack(AttackEvent a) {
        Hidden common = common(a.target(), a.team(), a.pingMs());
        if (common != null) return common;
        Weapon w = a.weaponOr();
        return matches(weapon, w) ? null : of(w);
    }

    public Hidden incoming(IncomingHitEvent h) {
        return common(h.attacker(), h.team(), h.pingMs());
    }

    public boolean opensTrade(AttackEvent a) {
        return !(handNoTrade && a.weaponOr() == Weapon.HAND);
    }

    public boolean fightCounts(Stats.Fight f) {
        return f.dealt() + f.taken() >= minFightHits;
    }

    public Applied apply(List<Event> events) {
        List<Event> kept = new ArrayList<>(events.size());
        Map<Hidden, Integer> hidden = new EnumMap<>(Hidden.class);
        for (Event e : events) {
            Hidden r = e instanceof AttackEvent a ? attack(a) : e instanceof IncomingHitEvent h ? incoming(h) : null;
            if (r != null) hidden.merge(r, 1, Integer::sum);
            else kept.add(e);
        }
        return new Applied(kept, hidden);
    }

    private Hidden common(UUID other, int team, int pingMs) {
        if (noTeammates && Relation.teammate(team)) return Hidden.TEAMMATE;
        if (noNpc && Relation.npc(other)) return Hidden.NPC;
        if (highPing && pingMs > pingLimitMs) return Hidden.HIGH_PING;
        return null;
    }

    static Hidden of(Weapon w) {
        return switch (w) {
            case SWORD -> Hidden.SWORD;
            case AXE -> Hidden.AXE;
            case MACE -> Hidden.MACE;
            case SPEAR -> Hidden.SPEAR;
            case HAND -> Hidden.HAND;
            case OTHER -> Hidden.OTHER_WEAPON;
            case UNKNOWN -> Hidden.UNKNOWN_WEAPON;
        };
    }
}

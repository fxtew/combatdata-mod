package dev.combatdata.client;

import dev.combatdata.core.Relation;
import dev.combatdata.core.Weapon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;

final class Weapons {
    private Weapons() {
    }

    /** Category by 26.1.2 item tags; the mace has no tag, so compare with Items.MACE. */
    static Weapon of(ItemStack s) {
        if (s == null || s.isEmpty()) return Weapon.HAND;
        if (s.is(ItemTags.SWORDS)) return Weapon.SWORD;
        if (s.is(ItemTags.AXES)) return Weapon.AXE;
        if (s.is(Items.MACE)) return Weapon.MACE;
        if (s.is(ItemTags.SPEARS)) return Weapon.SPEAR;
        return Weapon.OTHER;
    }

    static String id(ItemStack s) {
        return s == null || s.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    static String typeId(Entity e) {
        return e == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    static int team(Entity self, Entity other) {
        PlayerTeam mine = self.getTeam();
        if (mine == null || other == null || other.getTeam() != mine) return Relation.TEAM_NONE;
        return mine.isAllowFriendlyFire() ? Relation.TEAM_SAME_FF : Relation.TEAM_SAME;
    }
}

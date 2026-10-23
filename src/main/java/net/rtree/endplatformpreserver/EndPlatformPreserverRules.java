package net.rtree.endplatformpreserver;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;

/** A world-persisted setting exposed by Minecraft's standard /gamerule command. */
public final class EndPlatformPreserverRules {
    public static final Identifier ENABLED_ID = Identifier.fromNamespaceAndPath("end-platform-preserver", "enabled");
    public static final GameRule<Boolean> ENABLED = new GameRule<>(
            GameRuleCategory.MISC, GameRuleType.BOOL, BoolArgumentType.bool(),
            GameRuleTypeVisitor::visitBoolean, Codec.BOOL, value -> value ? 1 : 0,
            true, FeatureFlagSet.of());

    private EndPlatformPreserverRules() {}

    public static void register(Registry<GameRule<?>> registry) {
        Registry.register(registry, ENABLED_ID, ENABLED);
    }
}

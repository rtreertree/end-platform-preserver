package net.rtree.endplatformpreserver.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.rtree.endplatformpreserver.EndPlatformPreserverRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Keeps vanilla floor regeneration while skipping the three air-clearing layers. */
@Mixin(value = EndPlatformFeature.class, remap = false)
public abstract class EndPlatformFeatureMixin {
    @ModifyConstant(
        method = "createEndPlatform(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V",
        constant = @Constant(intValue = 3),
        require = 1,
        allow = 1
    )
    private static int endPlatformPreserver$skipClearingAboveFloor(int originalUpperBound,
            ServerLevelAccessor level, BlockPos origin, boolean dropResources) {
        // In 26.3 the vertical loop starts at -1 (the obsidian floor).
        // An exclusive upper bound of 0 keeps that iteration and skips 0, 1, 2.
        // Neither destroyBlock nor setBlockAndUpdate runs above the floor.
        return level.getLevel().getGameRules().get(EndPlatformPreserverRules.ENABLED) ? 0 : originalUpperBound;
    }
}

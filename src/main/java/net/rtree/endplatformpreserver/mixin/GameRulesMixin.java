package net.rtree.endplatformpreserver.mixin;

import net.minecraft.core.Registry;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.rtree.endplatformpreserver.EndPlatformPreserverRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Register while the vanilla game rule registry is still mutable. */
@Mixin(value = GameRules.class, remap = false)
public abstract class GameRulesMixin {
    @Inject(method = "bootstrap", at = @At("RETURN"), require = 1, allow = 1)
    private static void endPlatformPreserver$registerRule(Registry<GameRule<?>> registry,
            CallbackInfoReturnable<GameRule<?>> callback) {
        EndPlatformPreserverRules.register(registry);
    }
}

package net.rtree.endplatformpreserver.verification.mixin;

import net.rtree.endplatformpreserver.verification.EndPlatformVerification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only entry point: stop before Minecraft starts a window or opens a world. */
@Mixin(targets = "net.minecraft.client.main.Main", remap = false)
public abstract class VerificationMainMixin {
    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), require = 1, cancellable = true)
    private static void verifyEndPlatform(String[] arguments, CallbackInfo callback) {
        int status = 1;
        try {
            EndPlatformVerification.run();
            status = 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        }
        // Bootstrap can start executor threads; an explicit process result is intentional.
        System.exit(status);
        callback.cancel();
    }
}

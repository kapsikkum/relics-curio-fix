package net.kapsikkum.relicscuriofix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Function;

/**
 * Fixes a NullPointerException in Relics 0.10.x where CurioSlotReference.gatherStack()
 * calls Map.get(identifier) on the curios handler map and then immediately dereferences
 * the result without a null check. If the slot identifier is not present in the map,
 * the result is null and .getStacks() throws NPE.
 *
 * Root cause stacktrace:
 *   java.lang.NullPointerException: Cannot invoke "ICurioStacksHandler.getStacks()"
 *   because the return value of "Map.get(Object)" is null
 *   at CurioSlotReference.lambda$gatherStack$0(CurioSlotReference.java:24)
 *
 * Fix: redirect the Optional.map() call in gatherStack() and catch any NPE thrown
 * by the lambda, returning Optional.empty() instead.
 */
@Mixin(targets = "it.hurts.sskirillss.relics.system.casts.slots.CurioSlotReference", remap = false)
public class MixinCurioSlotReference {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "gatherStack",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;"
        ),
        remap = false
    )
    private Optional safeMap(Optional instance, Function mapper) {
        try {
            return instance.map(mapper);
        } catch (NullPointerException ignored) {
            // Curio slot identifier not found in handler map — return empty rather than crashing
            return Optional.empty();
        }
    }
}

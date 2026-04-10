package net.kapsikkum.relicscuriofix.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a NullPointerException in Carry On 2.2.4.4 that mass-disconnects all
 * nearby players when a player places a carried block entity.
 *
 * Root cause:
 *   When Carry On picks up a block entity (e.g. a Mekanism machine, Ars Nouveau
 *   source jar, etc.), it calls blockEntity.saveWithId() to snapshot the NBT.
 *   Some block entities store value-type Tag objects (nested CompoundTags,
 *   ListTags) that are shared references to the block entity's live internal
 *   state rather than independent copies. When the server tick thread later
 *   modifies those live objects, the fastutil Object2ObjectOpenHashMap backing
 *   the nested CompoundTag can be left with a null key array ("this.wrapped"
 *   is null).
 *
 *   On placement, Carry On broadcasts a clientbound sync packet to all players
 *   in range. The Netty IO thread encodes this packet via DFU's NbtOps, which
 *   calls CompoundTag.copy(). The copy() constructor of the damaged fastutil
 *   map throws NullPointerException, which propagates as an EncoderException
 *   and drops every client connection that was about to receive the packet.
 *
 * Fix:
 *   Immediately after setBlock() stores the block entity NBT in this.nbt,
 *   perform a deep copy on the main server thread — before any concurrent
 *   modification can corrupt the shared references. If the deep copy somehow
 *   fails, fall back to an empty CompoundTag (the block is still placed, but
 *   its contents are lost — better than a server crash and mass-disconnect).
 *
 * Stack trace (condensed):
 *   NullPointerException: "this.wrapped" is null
 *   at Object2ObjectOpenHashMap$MapIterator.nextEntry(637)
 *   at ... (fastutil putAll / copy constructor chain)
 *   at CompoundTag.copy(1204)
 *   at CompoundTag.lambda$static$2(31)   ← NbtOps copy path
 *   at Encoder$1.encode / Codec$2.encode ← DFU codec
 *   at CustomPacketPayload$1.encode(47)  ← carryon:sync_carry_data encoder
 *   at GenericPacketSplitter.encode(104) ← NeoForge splitter
 *   → EncoderException → Connection drops for ALL nearby players
 */
@Mixin(targets = "tschipp.carryon.common.carry.CarryOnData", remap = false)
public class MixinCarryOnSyncFix {

    private static final Logger LOGGER = LogManager.getLogger("relicscuriofix/CarryOnSyncFix");

    @Shadow @Mutable
    private CompoundTag nbt;

    /**
     * After the block entity NBT snapshot has been written into this.nbt,
     * replace it with a fully independent deep copy. This is called on the
     * main server thread while the captured NBT is still pristine, breaking
     * any shared Tag references that could later be concurrently modified.
     */
    @Inject(method = "setBlock", at = @At("RETURN"), remap = false)
    private void safeDeepCopyNbtAfterBlockPickup(BlockState state, BlockEntity blockEntity, CallbackInfo ci) {
        if (this.nbt == null) return;
        try {
            this.nbt = this.nbt.copy();
        } catch (RuntimeException e) {
            LOGGER.warn(
                "[CarryOn fix] Could not deep-copy block entity NBT after pickup ({}). " +
                "Falling back to empty tag — block contents will be lost on placement, " +
                "but the server will not crash.",
                e.getMessage()
            );
            this.nbt = new CompoundTag();
        }
    }
}

package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.accessor.ItemCooldownsAccessor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import static com.fu_meng.more_attributes.AttributesRegistry.COOLDOWN_REDUCTION;

@Mixin(ItemCooldowns.class)
public class ItemCooldownsMixin implements ItemCooldownsAccessor {

    @Unique
    private Player more_attributes$player;

    @Override
    public void more_attributes$setPlayer(Player player) {more_attributes$player = player;}

    @ModifyVariable(
            method = "addCooldown",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    public int addCooldown(int pTicks) {
        double reduction = more_attributes$player.getAttributeValue(COOLDOWN_REDUCTION.get());
        return (int) ((1 - reduction) * pTicks);
    }
}

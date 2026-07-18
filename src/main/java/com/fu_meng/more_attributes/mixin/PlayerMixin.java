package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import static com.fu_meng.more_attributes.AttributesRegistry.HUNGER_RATE;

@Mixin(Player.class)
public class PlayerMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float modifyExhaustion(float exhaustion) {
        Player player = (Player) (Object) this;
        double hungerRate = player.getAttributeValue(HUNGER_RATE.get());
        return Math.max((float) (exhaustion * hungerRate), 0.0F);
    }
}

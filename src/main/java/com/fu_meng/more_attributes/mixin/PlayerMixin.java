package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.accessor.ItemCooldownsAccessor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.fu_meng.more_attributes.AttributesRegistry.HUNGER_RATE;

@Mixin(Player.class)
public class PlayerMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float modifyExhaustion(float exhaustion) {
        Player player = (Player) (Object) this;
        double hungerRate = player.getAttributeValue(HUNGER_RATE.get());
        return Math.max((float) (exhaustion * hungerRate), 0.0F);
    }

    /**
     * 每次 getCooldowns() 返回时，将 Player 自身注入到 ItemCooldowns 中。
     */
    @Inject(method = "getCooldowns", at = @At("RETURN"))
    private void moreAttributes$InjectPlayerRef(CallbackInfoReturnable<ItemCooldowns> cir) {
        ItemCooldowns cooldowns = cir.getReturnValue();
        if (cooldowns instanceof ItemCooldownsAccessor accessor) {
            accessor.more_attributes$setPlayer((Player) (Object) this);
        }
    }
}

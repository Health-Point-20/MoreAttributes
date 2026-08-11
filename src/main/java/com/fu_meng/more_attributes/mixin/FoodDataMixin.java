package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static com.fu_meng.more_attributes.AttributesRegistry.HUNGER_RATE;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V"))
    private void redirectExhaustion(FoodData instance, float pExhaustion, Player pPlayer) {
        if (MAConfig.hungerRateAffectRegeneration) {
            double hungerRate = pPlayer.getAttributeValue(HUNGER_RATE.get());
            float exhaustion = Math.max((float) (pExhaustion * hungerRate), 0.0F);
            instance.addExhaustion(exhaustion);
        } else {
            instance.addExhaustion(pExhaustion);
        }
    }
}

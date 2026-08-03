package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
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

    // Player 重写了 getDimensions，直接从 EnumMap 返回固定尺寸，不调用 super.getDimensions()，
    // 故 LivingEntityMixin.onGetDimensions 对 Player 不生效，需在此单独注入。
    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
    private void moreAttributes$onGetDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        Player self = (Player) (Object) this;
        double scale = AttributeCalculationHelper.getScaleFactor(self);
        if (scale != 1.0F) {
            EntityDimensions base = cir.getReturnValue();
            cir.setReturnValue(base.scale((float) scale));
        }
    }
}

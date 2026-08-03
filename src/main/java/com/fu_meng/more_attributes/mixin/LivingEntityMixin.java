package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    // 将基础宽高等比乘以属性值，缩放碰撞尺寸
    // getDimensions 的返回值会被 EntityMixin.onGetBoundingBox 用于重建碰撞箱
    // Player 重写了 getDimensions，由 PlayerMixin 单独处理
    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
    private void onGetDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        double scale = AttributeCalculationHelper.getScaleFactor(self);
        if (scale != 1.0F) {
            EntityDimensions base = cir.getReturnValue();
            cir.setReturnValue(base.scale((float) scale));
        }
    }
}

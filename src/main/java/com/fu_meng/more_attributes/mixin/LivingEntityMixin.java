package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.fu_meng.more_attributes.AttributesRegistry.CLIMBING_SPEED;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * 缩放攀爬速度：在原版 handleOnClimbable 钳制三轴速度后，按 CLIMBING_SPEED 属性倍率缩放返回值。
     * 属性默认值 1.0 时不影响原版行为。
     */
    @Inject(method = "handleOnClimbable", at = @At("RETURN"), cancellable = true)
    private void moreAttributes$onHandleOnClimbable(Vec3 deltaMovement, CallbackInfoReturnable<Vec3> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        double scale = self.getAttributeValue(CLIMBING_SPEED.get());
        if (scale != 1.0 && cir.getReturnValue() != null) {
            Vec3 v = cir.getReturnValue();
            cir.setReturnValue(new Vec3(v.x * scale, v.y * scale, v.z * scale));
        }
    }
}

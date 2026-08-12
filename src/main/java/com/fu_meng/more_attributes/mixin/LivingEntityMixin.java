package com.fu_meng.more_attributes.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.fu_meng.more_attributes.AttributesRegistry.CLIMBING_SPEED;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * 重写 handleOnClimbable：按 CLIMBING_SPEED 属性倍率放大横向与下滑的钳制上限。
     * 原版钳制值为 0.15F，放大后允许更高的稳态攀爬速度，且每帧速度恒定、不会累加。
     * scale == 1.0 或非攀爬时走原版逻辑。
     */
    @Inject(method = "handleOnClimbable", at = @At("HEAD"), cancellable = true)
    private void moreAttributes$onHandleOnClimbable(Vec3 deltaMovement, CallbackInfoReturnable<Vec3> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        double scale = self.getAttributeValue(CLIMBING_SPEED.get());
        if (scale == 1.0 || !self.onClimbable()) return;

        self.resetFallDistance();
        float clamp = 0.15F * (float) scale;
        double d0 = Mth.clamp(deltaMovement.x, -clamp, clamp);
        double d1 = Mth.clamp(deltaMovement.z, -clamp, clamp);
        double d2 = Math.max(deltaMovement.y, -clamp);
        if (d2 < 0.0D && !self.getFeetBlockState().isScaffolding(self)
                && self.isSuppressingSlidingDownLadder() && self instanceof net.minecraft.world.entity.player.Player) {
            d2 = 0.0D;
        }
        cir.setReturnValue(new Vec3(d0, d2, d1));
    }

    /**
     * 修改 handleRelativeFrictionAndCalculateMovement 中向上爬的 0.2D 速度。
     * 该 Vec3 构造仅在 onClimbable 时执行，故无需再次判断。
     */
    @ModifyArg(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;<init>(DDD)V"),
            index = 1
    )
    private double moreAttributes$modifyClimbUpY(double y) {
        LivingEntity self = (LivingEntity) (Object) this;
        double scale = self.getAttributeValue(CLIMBING_SPEED.get());
        return y * scale;
    }
}

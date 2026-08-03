package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    // 实时缩放碰撞箱
    // 原版 getBoundingBox()（m_20191_）是 final 方法，直接返回缓存字段 f_19828_；
    // f_19828_ 由 makeBoundingBox() 重建，而 makeBoundingBox() 读的是缓存字段 f_19815_（dimensions）。
    // 仅注入 getDimensions 无法影响已缓存的 f_19815_ 和 f_19828_，
    // 故在此用实时 getDimensions(getPose()) 重建 AABB，确保碰撞箱始终反映当前 SCALE 属性。
    // 仅对 LivingEntity 生效，非生物实体直接跳过。
    @Inject(method = "getBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At("RETURN"), cancellable = true)
    private void onGetBoundingBox(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living) {
            double scale = AttributeCalculationHelper.getScaleFactor(living);
            if (scale != 1.0F) {
                // 用 getDimensions 重建 AABB
                EntityDimensions dims = living.getDimensions(living.getPose());
                cir.setReturnValue(dims.makeBoundingBox(living.position()));
            }
        }
    }

    // 实时缩放视角高度
    // 原版 getEyeHeight()（m_20192_）是 final 方法，直接返回缓存字段 f_19816_，
    // 仅在构造/refreshDimensions/setPose 时通过 m_6380_ 计算并缓存，运行时读取的是缓存值。
    // 故在此直接对返回值乘以 scale，确保视角高度始终反映当前 SCALE 属性。
    @Inject(method = "getEyeHeight()F", at = @At("RETURN"), cancellable = true)
    private void onGetEyeHeight(CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living) {
            double scale = AttributeCalculationHelper.getScaleFactor(living);
            if (scale != 1.0F) {
                cir.setReturnValue(cir.getReturnValue() * (float) scale);
            }
        }
    }
}

package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    // 实时缩放碰撞箱
    // 原版 getBoundingBox()（m_20191_）是 final 方法，直接返回缓存字段 f_19828_；
    // f_19828_ 由 makeBoundingBox() 重建，而 makeBoundingBox() 读的是缓存字段 f_19815_（dimensions）。
    // 不再通过 getDimensions() 注入缩放（会导致 refreshDimensions 缓存缩放后的值，
    // 在 SCALE 还原后缓存仍为缩放值，产生问题2）。
    // 改为在此处手动缩放 getDimensions 返回值后重建 AABB。
    @Inject(method = "getBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At("RETURN"), cancellable = true)
    private void onGetBoundingBox(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living) {
            double scale = AttributeCalculationHelper.getScaleFactor(living);
            if (scale != 1.0F) {
                EntityDimensions dims = living.getDimensions(living.getPose()).scale((float) scale);
                cir.setReturnValue(dims.makeBoundingBox(living.position()));
            }
        }
    }

    // 实时缩放视角高度
    // 原版 getEyeHeight()（m_20192_）是 final 方法，直接返回缓存字段 f_19816_。
    // 由于不再缩放 getDimensions()，refreshDimensions 缓存的 eyeHeight 始终为未缩放值，
    // 故在此直接对返回值乘以 scale，确保视角高度始终反映当前 SCALE 属性，且不会重复缩放。
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

    // 缩放 getEyePosition()（无参版）的返回值
    // 原版 getEyePosition() 调用 getEyeY()，而 getEyeY() 直接读取缓存字段 this.eyeHeight（未缩放），
    // 不经过 getEyeHeight() 方法，故上面的 onGetEyeHeight 无法影响它。
    // 此注入确保所有通过 getEyePosition() 获取视角位置的代码（包括 isInWall）都能获得缩放后的值。
    @Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void onGetEyePosition(CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living) {
            double scale = AttributeCalculationHelper.getScaleFactor(living);
            if (scale != 1.0F) {
                Vec3 pos = cir.getReturnValue();
                double unscaledEyeHeight = pos.y - living.getY();
                cir.setReturnValue(new Vec3(pos.x, living.getY() + unscaledEyeHeight * scale, pos.z));
            }
        }
    }

    // 修复小尺寸下的窒息问题
    // 原版 isInWall() 使用缓存字段 this.dimensions.width（未缩放）和 getEyePosition()（已由上方注入缩放）。
    // 此处在 scale != 1.0 时完全接管逻辑，使用缩放后的宽度和缩放后的视角位置进行窒息检测，
    // 避免小尺寸玩家在一格高空间内因未缩放的宽度/视角高度而误判窒息。
    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void onIsInWall(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof LivingEntity living) {
            double scale = AttributeCalculationHelper.getScaleFactor(living);
            if (scale != 1.0F) {
                if (self.noPhysics) {
                    cir.setReturnValue(false);
                    return;
                }
                float f = (float) (living.getDimensions(living.getPose()).width * scale * 0.8F);
                Vec3 eyePos = new Vec3(living.getX(), living.getY() + (double) living.getEyeHeight(), living.getZ());
                AABB aabb = AABB.ofSize(eyePos, (double) f, 1.0E-6D, (double) f);
                boolean result = BlockPos.betweenClosedStream(aabb).anyMatch(pos -> {
                    BlockState blockstate = living.level().getBlockState(pos);
                    return !blockstate.isAir()
                            && blockstate.isSuffocating(living.level(), pos)
                            && Shapes.joinIsNotEmpty(
                                    blockstate.getCollisionShape(living.level(), pos)
                                            .move((double) pos.getX(), (double) pos.getY(), (double) pos.getZ()),
                                    Shapes.create(aabb), BooleanOp.AND);
                });
                cir.setReturnValue(result);
            }
        }
    }
}

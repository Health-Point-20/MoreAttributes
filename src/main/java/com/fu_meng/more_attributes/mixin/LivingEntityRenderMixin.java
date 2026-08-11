package com.fu_meng.more_attributes.mixin;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRenderMixin {
    @Inject(
            method = "render*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void onAfterScale(LivingEntity pEntity, float pEntityYaw, float pPartialTicks, PoseStack pPoseStack, MultiBufferSource pBuffer, int pPackedLight, CallbackInfo ci) {
        double scale = AttributeCalculationHelper.getScaleFactor(pEntity);
        if (scale != 1.0) {
            pPoseStack.scale((float) scale, (float) scale, (float) scale);
        }
    }
}

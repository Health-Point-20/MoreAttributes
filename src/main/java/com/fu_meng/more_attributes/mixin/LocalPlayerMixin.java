package com.fu_meng.more_attributes.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import static com.fu_meng.more_attributes.AttributesRegistry.SNEAK_SPEED;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    /**
     * 潜行速度：按 SNEAK_SPEED 属性倍率放大潜行移动系数。
     * 原版潜行系数 = clamp(0.3 + 迅捷潜行附魔加成, 0, 1)，乘以属性值后重新钳制到 [0, sneakSpeed]。
     * 属性默认值 1.0 时行为与原版完全一致。
     */
    @ModifyArg(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V"),
            index = 1
    )
    private float moreAttributes$modifySneakingSpeedMultiplier(float sneakingSpeedMultiplier) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        float sneakSpeed = (float) self.getAttributeValue(SNEAK_SPEED.get());
        return Mth.clamp(sneakingSpeedMultiplier * sneakSpeed, 0.0F, sneakSpeed);
    }
}

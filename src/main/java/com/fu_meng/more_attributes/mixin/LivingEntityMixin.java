package com.fu_meng.more_attributes.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityMixin {
    @Accessor("lastHurt")
    float getLastHurt();

    @Accessor("lastHurt")
    void setLastHurt(float value);

    @Invoker("playHurtSound")
    void playHurtSound(DamageSource damageSource);

    @Invoker("getDeathSound")
    SoundEvent getDeathSound();

    @Invoker("getSoundVolume")
    float getSoundVolume();
}
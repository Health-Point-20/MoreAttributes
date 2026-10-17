package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("lastHurt")
    float getLastHurt();

    @Accessor("lastHurt")
    void setLastHurt(float value);

    @Accessor("lastHurtByPlayer")
    Player getLastHurtByPlayer();

    @Accessor("lastHurtByPlayerTime")
    int getLastHurtByPlayerTime();

    @Invoker("playHurtSound")
    void playHurtSound(DamageSource damageSource);

    @Invoker("shouldDropLoot")
    boolean shouldDropLoot();

    /** 调用原版死亡保护判定（不死图腾等）。返回 true 表示保护已生效、血量已被置为 1。 */
    @Invoker("checkTotemDeathProtection")
    boolean invokeCheckTotemDeathProtection(DamageSource damageSource);
}
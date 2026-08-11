package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MeleeAttackGoal.class)
public interface MeleeAttackGoalAccessor {
    @Invoker("getAttackReachSqr")
    double getAttackReachSqr(LivingEntity pAttackTarget);
}

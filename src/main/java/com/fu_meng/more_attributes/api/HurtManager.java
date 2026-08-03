package com.fu_meng.more_attributes.api;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.MoreAttributesMod.queueServerWork;
import static com.fu_meng.more_attributes.MoreAttributesMod.LOGGER;

@Mod.EventBusSubscriber
public class HurtManager {

    // 存储额外伤害信息的Map
    private static final Map<LivingEntity, List<ExtraHurtData>> extraHurtQueue = new ConcurrentHashMap<>();

    // 存储玩家横扫攻击的主要目标
    private static final Map<Player, UUID> directAttackTarget = new ConcurrentHashMap<>();

    private static boolean onDealExtraDamage = false;

    public record ExtraHurtData(DamageSource source, float damage) {}

    /**
     * 获取玩家横扫攻击的主要目标
     */
    public static UUID getDirectAttackTarget(Player player) {
        return directAttackTarget.getOrDefault(player, null);
    }

    /**
     * 获取额外伤害处理状态
     */
    public static boolean isOnDealExtraDamage() {
        return onDealExtraDamage;
    }

    /**
     * 构造伤害源
     */
    public static DamageSource getDamageSource(ResourceKey<DamageType> type, LivingEntity attacker) {
        return getDamageSource(type, attacker.level(), attacker);
    }

    /**
     * 构造伤害源
     */
    public static DamageSource getDamageSource(ResourceKey<DamageType> type, Level level, @Nullable LivingEntity attacker) {
        try {
            Registry<DamageType> damageTypes = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            Holder<DamageType> typeHolder = damageTypes.getHolderOrThrow(type);
            if (attacker == null) {
                return new DamageSource(typeHolder);
            }
            return new DamageSource(typeHolder, attacker);
        } catch (IllegalStateException e) {
            LOGGER.error("无法获取伤害类型", e);
            return null;
        }
    }

    /**
     * 添加额外伤害到队列中
     */
    public static void extraHurt(LivingEntity target, DamageSource source, float damage) {
       if (!onDealExtraDamage && target.isAlive() && !target.level().isClientSide()) {
            extraHurtQueue.computeIfAbsent(target, k -> new ArrayList<>()).add(new ExtraHurtData(source, damage));
        }
    }

    /**
     * 处理单个实体的额外伤害
     */
    private static void processExtraHurt(LivingEntity target, ExtraHurtData data) {
        LivingEntityAccessor targetMixin = (LivingEntityAccessor) target;
        int origInvulnerableTime = target.invulnerableTime;
        float origHurt = targetMixin.getLastHurt();

        try {
            target.invulnerableTime = 0;
            target.hurt(data.source(), data.damage());
        } finally {
            // 恢复原始状态
            targetMixin.setLastHurt(origHurt);
            target.invulnerableTime = origInvulnerableTime;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        onDealExtraDamage = true;
        for (LivingEntity target : new HashSet<>(extraHurtQueue.keySet())) {
            if (!target.isAlive()) {
                extraHurtQueue.remove(target);
            } else {
                List<ExtraHurtData> extraHurtDataList = extraHurtQueue.get(target);

                if (extraHurtDataList == null) continue;

                for (ExtraHurtData extraHurtData : extraHurtDataList) {
                    if (extraHurtData != null) {
                        // 处理额外伤害
                        processExtraHurt(target, extraHurtData);
                    }
                }
                // 清除此实体的额外伤害数据
                extraHurtQueue.remove(target);
            }
        }
        onDealExtraDamage = false;
    }

    /**
     * 获取玩家横扫攻击的主要目标
     */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        directAttackTarget.put(event.getEntity(), event.getTarget().getUUID());
        // 清理主要攻击目标
        queueServerWork(() -> directAttackTarget.remove(event.getEntity()));
    }
}
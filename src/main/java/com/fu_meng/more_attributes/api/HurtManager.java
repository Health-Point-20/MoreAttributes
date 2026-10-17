package com.fu_meng.more_attributes.api;

import com.fu_meng.more_attributes.MoreAttributesMod;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static com.fu_meng.more_attributes.MoreAttributesMod.queueServerWork;
import static com.fu_meng.more_attributes.MoreAttributesMod.LOGGER;

@Mod.EventBusSubscriber
public class HurtManager {

    // 存储额外伤害信息的Map（使用 CopyOnWriteArrayList 保证线程安全）
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
            extraHurtQueue.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(new ExtraHurtData(source, damage));
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
        if (extraHurtQueue.isEmpty()) return;

        // 这个标志是「正在结算额外伤害」的全局守卫，会直接决定 AttributesHandler.onHurt 里
        // 真伤分支是否执行（`!isOnDealExtraDamage()`）。原来它是裸赋值：
        //     onDealExtraDamage = true; ... processExtraHurt(...) ...; onDealExtraDamage = false;
        // 而 processExtraHurt 会调用 target.hurt(...)，模组覆写的 hurt 抛异常很常见（本模组的
        // 真伤结算自己也会走这条路），一旦抛出，最后那句复位就永远不执行 —— 标志永久停在 true，
        // 于是「打过一次之后，无论过多久、换哪只怪，真伤都不再触发，且没有任何日志」。
        // 修法：① try/finally 保证复位；② 保存/恢复原值以兼容嵌套；③ 单个目标出错不影响其余目标。
        boolean previous = onDealExtraDamage;
        onDealExtraDamage = true;
        try {
            for (LivingEntity target : new HashSet<>(extraHurtQueue.keySet())) {
                try {
                    if (!target.isAlive()) {
                        extraHurtQueue.remove(target);
                        continue;
                    }
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
                } catch (Throwable t) {
                    // 单个目标的额外伤害结算失败：跳过它，但绝不能污染守卫标志与剩余队列
                    rethrowIfFatal(t);
                    extraHurtQueue.remove(target);
                    MoreAttributesMod.LOGGER.warn("[额外伤害] 结算失败已跳过：{}（{}）",
                            target.getName().getString(), t.toString());
                }
            }
        } finally {
            onDealExtraDamage = previous;
        }
    }

    /** OOM/StackOverflow 这类致命错误不应被安静吞掉 */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError error) throw error;
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
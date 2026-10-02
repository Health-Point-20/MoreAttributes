package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多个事件处理器共享的战斗判定与记录状态：
 * 弹射物命中记录、近战闪避记录、闪避掷骰、友方判定。
 */
final class CombatHelper {
    // Map<弹射物, 受击者>
    // ProjectileImpactEvent 中登记（服务端），LivingAttackEvent 中消费并移除
    static final Map<UUID, UUID> PROJECTILE_RECORD = new ConcurrentHashMap<>();

    // 本 tick 内被闪避的近战目标（其 hurt 被取消），不应参与真伤兜底结算
    static final Map<UUID, UUID> DODGED_MELEE = new ConcurrentHashMap<>();

    // 记录玩家本次挥击的攻击冷却比例（AttackEntityEvent 触发时记录）。
    // Forge 将 resetAttackStrengthTicker 移至 attack() 末尾，伤害事件期间仍能读到真实冷却；
    // 但 tick 末的近战真伤兜底结算发生时 reset 已执行（计时器进入新冷却期），
    // 需用此记录回溯本次挥击的冷却
    static final Map<UUID, Float> ATTACK_STRENGTH_RECORD = new ConcurrentHashMap<>();

    private CombatHelper() {
    }

    /**
     * 攻击冷却伤害系数：0.2 + 冷却比例² × 0.8（冷却为 0 时 20%，满冷却 100%），
     * 与原版 Player.attack 对基础伤害的冷却缩减公式一致。
     */
    static float getMeleeCooldownFactor(float attackStrengthScale) {
        return 0.2F + attackStrengthScale * attackStrengthScale * 0.8F;
    }

    /**
     * 玩家近战的攻击冷却伤害系数（直接读取当前冷却比例），非玩家返回 1。
     * 仅可在攻击流程内调用（resetAttackStrengthTicker 之前）；
     * tick 末兜底结算时计时器已重置，须改用 {@link #ATTACK_STRENGTH_RECORD} 记录的冷却比例。
     */
    static float getMeleeCooldownFactor(LivingEntity attacker) {
        if (!(attacker instanceof Player player)) return 1.0F;
        return getMeleeCooldownFactor(player.getAttackStrengthScale(0.5F));
    }

    /**
     * 闪避判定：按最终命中概率掷骰，未命中返回 true 并向玩家攻击者提示闪避。
     * 供 ProjectileImpactEvent（弹射物）与 LivingAttackEvent（近战等）共用。
     */
    static boolean rollDodge(LivingEntity attacker, LivingEntity victim) {
        double finalHitProb = AttributeCalculationHelper.getFinalHitProbability(attacker, victim);
        if (victim.getRandom().nextFloat() >= finalHitProb) {
            if (attacker instanceof Player player) {
                player.displayClientMessage(Component.translatable("more_attributes.hit_miss", victim.getDisplayName()), true);
            }
            return true;
        }
        return false;
    }

    /** 是否友方（主仆关系 / 团队关系 / 同种生物） */
    static boolean isPartner(LivingEntity entity, LivingEntity target) {
        // 判断是否有主仆关系
        if (entity instanceof OwnableEntity ownable && ownable.getOwner() == target
                || target instanceof OwnableEntity ownableTarget && ownableTarget.getOwner() == entity) {
            return true;
        }

        // 团队关系
        if (target.getTeam() != null && target.getTeam().isAlliedTo(entity.getTeam())) {
            return true;
        }

        // 种族关系
        return entity.getType() == target.getType();
    }
}

package com.fu_meng.more_attributes.api;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.IdentityHashMap;
import java.util.Map;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.MAConfig.*;

public class AttributeCalculationHelper {

    private static final Map<AttributeInstance, Double> strengthCache = new IdentityHashMap<>();
    // 减伤类属性缓存：[0] = 正向强度, [1] = 负向属性值和(≤0), [2] = 乘数修饰
    private static final Map<AttributeInstance, double[]> damageReductionCache = new IdentityHashMap<>();

    // ================= 基础：获取属性的线性累加值 =================
    public static double getRawAttributeValue(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return 0.0;

       return instance.getValue();
    }

    // ================= 强度转换核心 =================
    /**
     * 概率转强度：s = -ln(1-p)
     * <p>
     * 叠加原则：g(f(s1+s2)) = g(f(s1)) * g(s2)，其中 g(p) = 1-p
     * <p>
     * 最终公式：p = 1 - e^(-s)
     */
    private static double probabilityToStrength(double p) {
        if (p >= 1.0) return Double.POSITIVE_INFINITY;
        if (p <= -1.0) return Double.NEGATIVE_INFINITY;
        return -Math.log(1.0 - p);
    }

    private static double strengthToProbability(double strength) {
        if (Double.isInfinite(strength)) {
            return strength > 0 ? 1.0 : -1.0;
        }
        return -Math.expm1(-strength);
    }

    /**
     * 每 tick 开始时调用，清空强度缓存。
     */
    public static void clearStrengthCache() {
        strengthCache.clear();
        damageReductionCache.clear();
    }

    private static double computeStrength(AttributeInstance instance) {
        if (instance == null) return 0.0;
        return strengthCache.computeIfAbsent(instance, AttributeCalculationHelper::computeStrengthUncached);
    }

    private static double computeStrengthUncached(AttributeInstance instance) {
        // 被禁用的属性：忽略基础值与所有修饰符，等同属性不存在（默认值进入强度域）
        Attribute attr = instance.getAttribute();
        if (isAttributeDisabled(attr)) {
            return probabilityToStrength(attr.getDefaultValue());
        }

        double strength = 0.0;
        double multiplyBase = 1.0;
        double multiplyTotal = 1.0;

        double base = instance.getBaseValue();
        strength += probabilityToStrength(base);

        for (AttributeModifier modifier : instance.getModifiers()) {
            double amount = modifier.getAmount();
            switch (modifier.getOperation()) {
                case ADDITION:
                    strength += probabilityToStrength(amount);
                    break;
                case MULTIPLY_BASE:
                    multiplyBase *= (1.0 + amount);
                    break;
                case MULTIPLY_TOTAL:
                    multiplyTotal *= (1.0 + amount);
                    break;
            }
        }

        strength = strength * multiplyBase * multiplyTotal;
        return strength;
    }

    // 对外接口
    public static double getProbability(AttributeInstance instance) {
        return strengthToProbability(computeStrength(instance));
    }

    // ================= 命中率相关 =================
    public static double getTotalHitRate(LivingEntity entity) {
        return Math.max(0.0, getRawAttributeValue(entity, HIT_RATE.get()));
    }

    // ================= 闪避相关 =================
    public static double getEvasionProbability(LivingEntity entity) {
        return strengthToProbability(computeStrength(entity.getAttribute(EVASION.get())));
    }

    // ================= 最终命中概率 =================
    public static double getFinalHitProbability(LivingEntity attacker, LivingEntity target) {
        double hit = getTotalHitRate(attacker);
        double evasion = getEvasionProbability(target);

        double baseHit = hit * (1 - evasion);

        double finalHit = getRawAttributeValue(attacker, FINAL_HIT_RATE.get());
        double finalEvasion = getRawAttributeValue(target, FINAL_EVASION.get());
        double probability = baseHit + finalHit - finalEvasion;

        return Math.max(0.0, Math.min(1.0, probability));
    }

    // ================= 减伤类 =================
    // 伤害减免类属性（物理/魔法/环境/全伤害）采用正负分离模型：
    // - 正值（减免）：走强度模型叠加，多个减免按 e^(-s) 相乘，收敛于 0（绝对免疫）
    // - 负值（易伤）：在属性值域线性叠加，factor = 1 + Σ|p|，无悬崖、无上限
    // - 正负两部分独立计算后相乘，互不抵消
    private static double[] computeDamageReductionUncached(AttributeInstance instance) {
        Attribute attr = instance.getAttribute();
        if (isAttributeDisabled(attr)) {
            // 被禁用的属性：忽略基础值与所有修饰符，等同属性不存在（默认值进入对应域）
            double def = attr.getDefaultValue();
            return def >= 0
                    ? new double[]{probabilityToStrength(def), 0.0, 1.0}
                    : new double[]{0.0, def, 1.0};
        }

        double positiveStrength = 0.0;
        double negativeSum = 0.0;
        double multiplyBase = 1.0;
        double multiplyTotal = 1.0;

        double base = instance.getBaseValue();
        if (base >= 0) {
            positiveStrength += probabilityToStrength(base);
        } else {
            negativeSum += base;
        }

        for (AttributeModifier modifier : instance.getModifiers()) {
            double amount = modifier.getAmount();
            switch (modifier.getOperation()) {
                case ADDITION -> {
                    if (amount >= 0) {
                        positiveStrength += probabilityToStrength(amount);
                    } else {
                        negativeSum += amount;
                    }
                }
                case MULTIPLY_BASE -> multiplyBase *= (1.0 + amount);
                case MULTIPLY_TOTAL -> multiplyTotal *= (1.0 + amount);
            }
        }

        return new double[]{positiveStrength, negativeSum, multiplyBase * multiplyTotal};
    }

    private static double[] getDamageReductionParts(AttributeInstance instance) {
        if (instance == null) return new double[]{0.0, 0.0, 1.0};
        return damageReductionCache.computeIfAbsent(instance, AttributeCalculationHelper::computeDamageReductionUncached);
    }

    /**
     * 减伤属性的伤害因子（实际受伤比例）：
     * 正向 e^(-s·m) ∈ (0,1]（s·m → +∞ 时为 0，绝对免疫）× 负向 (1 + |Σp|·m) ≥ 1（线性放大）。
     */
    public static double getDamageReductionFactor(AttributeInstance instance) {
        double[] parts = getDamageReductionParts(instance);
        double positiveStrength = parts[0] * parts[2];
        double negativeSum = parts[1] * parts[2];

        double positiveFactor = Double.isInfinite(positiveStrength) ? 0.0 : Math.exp(-positiveStrength);
        double negativeFactor = 1.0 - negativeSum;
        return positiveFactor * negativeFactor;
    }

    /**
     * 减伤属性对外展示的概率值（供 AttributeMap.getValue 使用）：
     * 正向概率 - |负向属性值和|，与实际伤害因子保持同一套正负分离模型。
     */
    public static double getDamageReductionProbability(AttributeInstance instance) {
        double[] parts = getDamageReductionParts(instance);
        double positiveStrength = parts[0] * parts[2];
        double negativeSum = parts[1] * parts[2];

        double positiveProbability = Double.isInfinite(positiveStrength) ? 1.0 : -Math.expm1(-positiveStrength);
        return positiveProbability + negativeSum;
    }

    public static double getPhysicalDamageFactor(LivingEntity entity) {
        return getDamageReductionFactor(entity.getAttribute(PHYSICAL_DAMAGE_REDUCTION.get()));
    }

    public static double getMagicDamageFactor(LivingEntity entity) {
        return getDamageReductionFactor(entity.getAttribute(MAGIC_DAMAGE_REDUCTION.get()));
    }

    public static double getEnvironmentalDamageFactor(LivingEntity entity) {
        return getDamageReductionFactor(entity.getAttribute(ENVIRONMENTAL_DAMAGE_REDUCTION.get()));
    }

    public static double getAllDamageFactor(LivingEntity entity) {
        return getDamageReductionFactor(entity.getAttribute(DAMAGE_REDUCTION.get()));
    }

    // ================= 无视无敌时间=================
    public static double getIgnoreInvincibleTimeRate(LivingEntity entity) {
        return strengthToProbability(computeStrength(entity.getAttribute(IGNORE_INVINCIBLE_TIME.get())));
    }

    // ================= 缩放相关 =================
    // SCALE 为乘数属性：1.0 = 原始大小，>1 放大，<1 缩小
    // 正向增量（放大）直接线性累加；负向增量（缩小）强度模型叠加，
    // 将每个负向增量的绝对值视为"缩减比例"并转为强度累加，
    // 保证最终缩减比例始终在 [0, 1] 内，防止尺寸变为负数。
    public static double getScaleFactor(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(SCALE.get());
        if (instance == null) return 1.0;
        if (isAttributeDisabled(instance.getAttribute())) return instance.getAttribute().getDefaultValue();

        double positiveSum = instance.getBaseValue(); // 正向累加（含基础值，默认 1.0）
        double negativeStrength = 0.0;                 // 负向强度
        double multiplyBase = 1.0;
        double multiplyTotal = 1.0;

        for (AttributeModifier modifier : instance.getModifiers()) {
            double amount = modifier.getAmount();
            switch (modifier.getOperation()) {
                case ADDITION:
                    if (amount >= 0) {
                        positiveSum += amount;
                    } else {
                        // 将负向增量视为"缩减比例"（取绝对值），通过强度模型叠加
                        negativeStrength += probabilityToStrength(-amount);
                    }
                    break;
                case MULTIPLY_BASE:
                    multiplyBase *= (1.0 + amount);
                    break;
                case MULTIPLY_TOTAL:
                    multiplyTotal *= (1.0 + amount);
                    break;
            }
        }

        // 负向部分：缩减因子 ∈ [0, 1]
        double reductionProbability = strengthToProbability(negativeStrength);
        double negativeFactor = 1.0 - reductionProbability;

        // 最终 scale = 正向累加值 × 缩减因子 × 乘数修饰
        double scale = positiveSum * negativeFactor * multiplyBase * multiplyTotal;

        return Math.max(0.0, scale);
    }

    public static float getHurtRecoveryAmount(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(HURT_RECOVERY.get());
        float maxHealth = entity.getMaxHealth();
        if (instance == null) return 0f;
        if (isAttributeDisabled(instance.getAttribute())) return 0f;

        double sum = 0.0;

        double base = instance.getBaseValue();
        sum += base;

        for (AttributeModifier modifier : instance.getModifiers()) {
            double amount = modifier.getAmount();
            switch (modifier.getOperation()) {
                case ADDITION:
                    sum += amount;
                    break;
                case MULTIPLY_BASE, MULTIPLY_TOTAL:
                    sum += amount * maxHealth;
                    break;
            }
        }

        return (float)Math.max(0, sum);
    }

    /**
     * 强度域伤害因子（背刺专用）：攻击者加成与受害者抵抗在强度域抵消。
     * - strength > 0：净减伤，factor = e^(-strength) ∈ (0, 1)
     * - strength < 0：净加成，factor = 1 + |strength| > 1（强度域线性）
     * - strength = +∞：绝对减伤（0 伤害）
     */
    private static double getDamageFactorFromStrength(double strength) {
        if (Double.isInfinite(strength)) {
            return strength > 0 ? 0.0 : Double.POSITIVE_INFINITY;
        }

        if (strength < 0) {
            return 1.0 + Math.abs(strength);
        }

        // 返回实际受伤比例
        return Math.exp(-strength);
    }

    /**
     * 计算背刺伤害因子：攻击者加成与受害者抵抗在强度域抵消后返回伤害乘数。
     * netStrength = victim_resist_strength - attacker_bonus_strength
     * - netStrength > 0：净减伤，factor = e^(-netStrength) ∈ (0, 1)
     * - netStrength < 0：净加成，factor = 1 + |netStrength| > 1
     * - netStrength = 0：factor = 1（完全抵消）
     */
    public static float getBackstabDamageFactor(LivingEntity attacker, LivingEntity victim) {
        double attackerStrength = computeStrength(attacker.getAttribute(BACKSTAB_DAMAGE.get()));
        double victimStrength = computeStrength(victim.getAttribute(BACKSTAB_RESISTANCE.get()));
        double netStrength = victimStrength - attackerStrength;
        return (float) getDamageFactorFromStrength(netStrength);
    }

    /**
     * 计算斩杀阈值：取所有修饰符中最宽松的（最大）阈值。
     * ADDITION 模式为固定值，MULTIPLY_BASE/MULTIPLY_TOTAL 为受害者最大生命值百分比。
     * 无修饰符时返回 0（不触发斩杀）。
     */
    public static float getExecuteThresholdValue(AttributeInstance executeThreshold, double maxHealth) {
        if (executeThreshold != null) {
            // 被禁用的属性：等同属性不存在，不触发斩杀
            if (isAttributeDisabled(executeThreshold.getAttribute())) return 0.0f;

            double maxAddition = 0.0;
            double maxMultiplication = 0.0;
            for (AttributeModifier modifier : executeThreshold.getModifiers()) {
                switch (modifier.getOperation()) {
                    case ADDITION -> maxAddition = Math.max(maxAddition, modifier.getAmount());
                    case MULTIPLY_BASE, MULTIPLY_TOTAL ->
                            maxMultiplication = Math.max(maxMultiplication, modifier.getAmount());
                }
            }
            return (float) Math.max(maxAddition, maxMultiplication * maxHealth);
        }
        return 0.0f;
    }

    public static float getIncomingDamageCapValue(AttributeInstance incomingDamageCap, double maxHealth) {
        if (incomingDamageCap != null) {
            // 被禁用的属性：等同属性不存在，无单次伤害上限
            if (isAttributeDisabled(incomingDamageCap.getAttribute())) return Float.POSITIVE_INFINITY;

            double minAddition = Double.MAX_VALUE;
            double minMultiplication = 1.0;

            // 单次遍历所有修饰符，同时查找加法和乘法中的最小值
            for (AttributeModifier modifier : incomingDamageCap.getModifiers()) {
                switch (modifier.getOperation()) {
                    case ADDITION -> minAddition = Math.min(minAddition, modifier.getAmount());
                    case MULTIPLY_BASE, MULTIPLY_TOTAL ->
                            minMultiplication = Math.min(minMultiplication, 1.0 + modifier.getAmount());
                }
            }

            return (float) Math.min(minAddition, minMultiplication * maxHealth);
        }
        return Float.POSITIVE_INFINITY;
    }

    /**
     * 计算基于生命值的额外伤害，聚合四个属性：
     * - 最大生命值伤害（基于受害者最大生命值）
     * - 当前生命值伤害（基于受害者当前生命值）
     * - 已损失生命值伤害（基于受害者已损失生命值，斩杀递增方向）
     * - 自身最大生命值伤害（基于攻击者自身最大生命值，Tank/肉搏流增伤）
     * 属性值视为对应生命值的比例（如 0.5 = 50% 生命值的额外伤害）。
     */
    public static float getHealthBasedDamageBonus(LivingEntity attacker, LivingEntity victim) {
        double maxHealthRatio = getRawAttributeValue(attacker, MAX_HEALTH_DAMAGE.get());
        double currentHealthRatio = getRawAttributeValue(attacker, CURRENT_HEALTH_DAMAGE.get());
        double lostHealthRatio = getRawAttributeValue(attacker, LOST_HEALTH_DAMAGE.get());
        double selfMaxHealthRatio = getRawAttributeValue(attacker, SELF_MAX_HEALTH_DAMAGE.get());
        if (maxHealthRatio <= 0.0 && currentHealthRatio <= 0.0 && lostHealthRatio <= 0.0 && selfMaxHealthRatio <= 0.0)
            return 0.0f;

        double victimMaxHealth = victim.getMaxHealth();
        double lostHealth = Math.max(0.0, victimMaxHealth - victim.getHealth());
        double bonus = maxHealthRatio * victimMaxHealth
                + currentHealthRatio * victim.getHealth()
                + lostHealthRatio * lostHealth
                + selfMaxHealthRatio * attacker.getMaxHealth();
        return (float) Math.max(0.0, bonus);
    }

    /**
     * 计算实体在伤害分摊中的权重：
     * 权重 = max(实体伤害分摊属性值, 0) + 默认权重。
     * 默认权重的加法保证受害者始终保留非零份额，即「几乎不会完全将伤害分摊出去」。
     */
    public static double getDamageSharingWeight(LivingEntity entity) {
        double attrValue = getRawAttributeValue(entity, DAMAGE_SHARING.get());
        return Math.max(0.0, attrValue) + damageSharingDefaultWeight;
    }

    // ================= 穿透类（护甲穿透 / 保护穿透） =================
    /**
     * 解析穿透属性：ADDITION 为固定穿透点数，MULTIPLY_BASE/MULTIPLY_TOTAL 为百分比穿透。
     * 负值视作不穿透，故两项分别钳制到 {@code [0, 1]} 与 {@code [0, +∞)}。
     *
     * @return [0] = 固定穿透点数，[1] = 百分比穿透（0~1）
     */
    public static double[] getPenetrationParts(AttributeInstance instance) {
        if (instance == null) return new double[]{0.0, 0.0};
        if (isAttributeDisabled(instance.getAttribute())) return new double[]{0.0, 0.0};

        double flat = instance.getBaseValue();
        double percentage = 0.0;
        for (AttributeModifier modifier : instance.getModifiers()) {
            switch (modifier.getOperation()) {
                case ADDITION -> flat += modifier.getAmount();
                case MULTIPLY_BASE, MULTIPLY_TOTAL -> percentage += modifier.getAmount();
            }
        }

        return new double[]{Math.max(0.0, flat), Math.min(1.0, Math.max(0.0, percentage))};
    }

    /**
     * 对点数施加穿透，返回穿透后的有效点数（不低于 0）。
     * 先按百分比穿透，再扣除固定穿透点数。
     */
    public static double applyPenetration(double points, double[] penetration) {
        if (points <= 0.0) return 0.0;
        double effective = points * (1.0 - penetration[1]) - penetration[0];
        return Math.max(0.0, effective);
    }

}
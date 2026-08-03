package com.fu_meng.more_attributes.api;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.IdentityHashMap;
import java.util.Map;

import static com.fu_meng.more_attributes.AttributesRegistry.*;

public class AttributeCalculationHelper {

    private static final Map<AttributeInstance, Double> strengthCache = new IdentityHashMap<>();

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
    }

    private static double computeStrength(AttributeInstance instance) {
        if (instance == null) return 0.0;
        return strengthCache.computeIfAbsent(instance, AttributeCalculationHelper::computeStrengthUncached);
    }

    private static double computeStrengthUncached(AttributeInstance instance) {
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

    // ================= 减伤类  =================
    private static double getDamageFactorFromStrength(double strength) {
        if (Double.isInfinite(strength)) {
            // 正无穷 = 绝对减伤(0伤害)；负无穷 = 无限易伤(无限大伤害)
            return strength > 0 ? 0.0 : Double.POSITIVE_INFINITY;
        }

        if (strength < 0) {
            double absStrength = Math.abs(strength);
            return 1.0 + absStrength;
        }

        // 返回实际受伤比例
        return Math.exp(-strength);
    }

    public static double getPhysicalDamageFactor(LivingEntity entity) {
        return getDamageFactorFromStrength(computeStrength(entity.getAttribute(PHYSICAL_DAMAGE_REDUCTION.get())));
    }

    public static double getMagicDamageFactor(LivingEntity entity) {
        return getDamageFactorFromStrength(computeStrength(entity.getAttribute(MAGIC_DAMAGE_REDUCTION.get())));
    }

    public static double getEnvironmentalDamageFactor(LivingEntity entity) {
        return getDamageFactorFromStrength(computeStrength(entity.getAttribute(ENVIRONMENTAL_DAMAGE_REDUCTION.get())));
    }

    public static double getAllDamageFactor(LivingEntity entity) {
        return getDamageFactorFromStrength(computeStrength(entity.getAttribute(DAMAGE_REDUCTION.get())));
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
}
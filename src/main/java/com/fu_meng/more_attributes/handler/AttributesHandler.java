package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import com.fu_meng.more_attributes.api.HurtManager;
import com.fu_meng.more_attributes.mixin.MeleeAttackGoalAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingBreatheEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.MAConfig.*;
import static com.fu_meng.more_attributes.MoreAttributesMod.LOGGER;
import static com.fu_meng.more_attributes.api.AttributeCalculationHelper.getHurtRecoveryAmount;
import static com.fu_meng.more_attributes.api.HurtManager.*;

/**
 * 属性伤害主管线（LivingAttackEvent → LivingHurtEvent → LivingDamageEvent）
 * 与杂项属性事件（再生/进食/氧气/挖掘/经验/仇恨/磁吸等）。
 * <p>
 * 弹射物属性见 {@link ProjectileAttributeHandler}，更强真伤见 {@link StrongTrueDamageHelper}，
 * 掉落倍率见 {@link LootDropHandler}，共享战斗判定见 {@link CombatHelper}。
 */
@Mod.EventBusSubscriber
public class AttributesHandler {
    private static final Map<UUID, Float> DAMAGE_RECORD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HURT_RECORD = new ConcurrentHashMap<>();
    // 记录攻击者本 tick 是否已触发过生命值相关伤害提升（每 tick 清空，条目生命周期严格为一个 tick）
    private static final Set<UUID> HEALTH_DAMAGE_TICK_RECORD = ConcurrentHashMap.newKeySet();
    // 记录玩家通过 AttackEntityEvent 触发的直接攻击目标 UUID，用于 healthDamagePlayerOnly 判定
    private static final Map<UUID, UUID> PLAYER_DIRECT_ATTACK_TARGET = new ConcurrentHashMap<>();

    // 小数累积器 PersistentData 键（统一命名空间前缀，避免与其他 mod 冲突）
    private static final String REMAINING_INVINCIBLE_TIME_KEY = "more_attributes$remaining_invincible_time";
    private static final String REMAINING_EATING_SPEED_KEY = "more_attributes$remaining_eating_speed";
    private static final String REMAINING_DRAW_SPEED_KEY = "more_attributes$remaining_draw_speed";
    private static final String REMAINING_OXYGEN_CONSUMPTION_KEY = "more_attributes$remaining_oxygen_consumption";

    /** 判断伤害源是否属于魔法伤害（由 consideredMagicDamage 配置定义） */
    private static boolean isMagicDamage(DamageSource source) {
        return source.typeHolder()
                .unwrapKey()
                .map(key -> consideredMagicDamage.contains(key.location()))
                .orElse(false);
    }

    /** 判断伤害源是否属于环境伤害类型（着火、摔落、爆炸、溺水等无来源实体的伤害） */
    private static boolean isEnvironmentalDamage(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE)        // 着火、岩浆、岩浆块、营火等
                || source.is(DamageTypeTags.IS_FALL)        // 摔落
                || source.is(DamageTypeTags.IS_EXPLOSION)   // TNT、烟花、床/重生锚爆炸
                || source.is(DamageTypeTags.IS_DROWNING)    // 溺水
                || source.is(DamageTypeTags.IS_FREEZING)    // 细雪冻伤
                || source.is(DamageTypeTags.IS_LIGHTNING)   // 雷击
                || source.is(DamageTypes.CACTUS)            // 仙人掌
                || source.is(DamageTypes.SWEET_BERRY_BUSH)  // 甜浆果丛
                || source.is(DamageTypes.IN_WALL)           // 窒息
                || source.is(DamageTypes.CRAMMING)          // 实体挤压
                || source.is(DamageTypes.FALLING_BLOCK)     // 掉落方块
                || source.is(DamageTypes.FALLING_ANVIL)     // 掉落铁砧
                || source.is(DamageTypes.FALLING_STALACTITE) // 掉落钟乳石
                || source.is(DamageTypes.FLY_INTO_WALL)     // 撞墙
                || source.is(DamageTypes.FELL_OUT_OF_WORLD) // 虚空
                || source.is(DamageTypes.OUTSIDE_BORDER);
    }

    /**
     * 伤害转化前的伤害记录（供 LivingDamageEvent 计算减免比例）。
     * takeMax=true 时与已有记录取最大值（onHurt 中增伤后的伤害更高），否则直接覆盖（onAttack）。
     */
    private static void recordDamageForConversion(LivingEntity attacker, float damage, boolean takeMax) {
        if (isOnDealExtraDamage()) return;
        double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
        double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
        if (trueDamageRatio <= 0 && magicDamageRatio <= 0) return;
        if (takeMax) {
            float damageRecord = Math.max(DAMAGE_RECORD.getOrDefault(attacker.getUUID(), 0f), damage);
            DAMAGE_RECORD.put(attacker.getUUID(), damageRecord);
        } else {
            DAMAGE_RECORD.put(attacker.getUUID(), damage);
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttack(LivingAttackEvent event) {
        float damage = event.getAmount();
        DamageSource source = event.getSource();
        LivingEntity victim = event.getEntity();
        LivingEntity attacker = source.getEntity() instanceof LivingEntity entity ? entity : null;

        // 处理最小受伤间隔
        double minimumInjuryInterval = victim.getAttributeValue(MINIMUM_INJURY_INTERVAL.get());
        if (minimumInjuryInterval > 0) {
            long lastHurtTime = HURT_RECORD.getOrDefault(victim.getUUID(), 0L);
            long currentTime = victim.level().getGameTime();
            if (currentTime - lastHurtTime < minimumInjuryInterval) {
                event.setCanceled(true);
            } else {
                HURT_RECORD.put(victim.getUUID(), currentTime);
            }
        }

        if (attacker == null) return;

        // 弹射物已在 ProjectileImpactEvent 中处理过闪避判定，此处不再重复处理
        Entity directEntity = source.getDirectEntity();
        if (directEntity != null && CombatHelper.PROJECTILE_RECORD.containsKey(directEntity.getUUID())) {
            // 移除已处理的弹射物记录，避免内存泄漏
            CombatHelper.PROJECTILE_RECORD.remove(directEntity.getUUID());
            return;
        }

        // 近战及其他攻击：进行闪避判定
        try {
            if (CombatHelper.rollDodge(attacker, victim)) {
                CombatHelper.DODGED_MELEE.put(victim.getUUID(), attacker.getUUID());
                event.setCanceled(true);
                return; // 已闪避，不再进行伤害记录
            }
        } catch (Exception e) {
            LOGGER.warn("计算攻击时出错: {}", e.getMessage());
        }

        // 伤害转化前的伤害记录
        recordDamageForConversion(attacker, damage, false);
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        DamageSource source = event.getSource();
        LivingEntity attacker = source.getEntity() instanceof LivingEntity entity ? entity : null;
        float damage = event.getAmount();

        // 处理无敌时间
        double invincibleTimeRate = victim.getAttributeValue(INVINCIBLE_TIME.get());
        if (invincibleTimeRate > 0) {
            victim.invulnerableTime = (int) (victim.invulnerableTime * invincibleTimeRate);
        }

        // 处理受击恢复属性
        float recoveryAmount = getHurtRecoveryAmount(victim);
        if (recoveryAmount > 0) {
            victim.heal(recoveryAmount);
        }

        // 处理伤害分摊（在攻击者侧增伤与受害者侧减伤之前，按原始伤害分摊）
        // 分摊伤害走 extraHurt 队列，由 isOnDealExtraDamage 守卫拦截，不会级联再分摊
        if (!isOnDealExtraDamage()) {
            damage = handleDamageSharing(victim, source, damage);
        }

        if (attacker != null) {
            // 处理忽视无敌时间
            double ignoreRate = AttributeCalculationHelper.getIgnoreInvincibleTimeRate(attacker);
            CompoundTag tag = victim.getPersistentData();
            if (ignoreRate != 0){
                double invincibleTime = victim.invulnerableTime * (1.0 - ignoreRate);
                double remainingTime = tag.getDouble(REMAINING_INVINCIBLE_TIME_KEY);
                double newTime = invincibleTime + remainingTime;
                victim.invulnerableTime = (int) newTime;
                tag.putDouble(REMAINING_INVINCIBLE_TIME_KEY, newTime - (int) newTime);
            } else {
                tag.remove(REMAINING_INVINCIBLE_TIME_KEY);
            }

            // 处理伤害倍率属性
            float multiplier = (float) attacker.getAttributeValue(DAMAGE_MULTIPLIER.get());
            // 魔法伤害倍率
            if (isMagicDamage(source)) {
                double magicDamageMultiplier = attacker.getAttributeValue(MAGIC_DAMAGE_MULTIPLIER.get()) - 1;
                multiplier += (float) magicDamageMultiplier;
            }
            damage *= multiplier;

            // 处理暴击
            // 开启神化兼容时跳过本模组的暴击处理，交由神化模组使用叠加后的属性值进行暴击判定
            if (!ApotheosisCritCompatibility || !ModList.get().isLoaded("attributeslib")) {
                double criticalChance = attacker.getAttributeValue(CRITICAL_CHANCE.get());
                double criticalDamage = attacker.getAttributeValue(CRITICAL_DAMAGE.get());

                if (attacker.getRandom().nextDouble() <= criticalChance) {
                    damage *= (float) criticalDamage;
                }
            }

            // 处理反伤
            double damageReflection =  victim.getAttributeValue(DAMAGE_REFLECTION.get());
            if (damageReflection > 0) {
                ResourceKey<DamageType> newSourceKey = source.typeHolder().unwrapKey().orElse(DamageTypes.MOB_ATTACK);
                DamageSource newSource = getDamageSource(newSourceKey, victim);
                float newDamage = (float) damageReflection * damage;

                extraHurt(attacker, newSource, newDamage);
            }

            // 处理背刺伤害加成/抵抗
            // 攻击者位于受害者背后半球（点积 < 0）判定为背刺
            double distanceSqr = victim.position().distanceToSqr(attacker.position());
            if (distanceSqr > 1.0E-6) {
                Vec3 look = victim.getLookAngle();
                Vec3 toAttacker = attacker.position().subtract(victim.position()).normalize();
                if (look.dot(toAttacker) < 0) {
                    // 背刺加成与抵抗均在强度域计算
                    float backstabFactor = AttributeCalculationHelper.getBackstabDamageFactor(attacker, victim);
                    if (backstabFactor != 1.0F) {
                        damage *= backstabFactor;
                    }
                }
            }

            // 处理近战伤害溅射
            double meleeSplashRange = attacker.getAttributeValue(MELEE_SPLASH_RANGE.get());
            double meleeSplashRatio = attacker.getAttributeValue(MELEE_SPLASH_RATIO.get());

            if (meleeSplashRange > 0 && meleeSplashRatio > 0) {
                meleeSplashRatio = unlimitedSplashDamage ? meleeSplashRatio : Math.min(1, meleeSplashRatio);
                float splashDamage = (float) (meleeSplashRatio * damage);
                AABB box = victim.getBoundingBox().inflate(meleeSplashRange);

                attacker.level().getEntitiesOfClass(LivingEntity.class, box, entity ->
                        entity != attacker && entity != victim && !CombatHelper.isPartner(attacker, entity)
                                && (entity instanceof Enemy || entity.getMobType() == victim.getMobType()))
                        .forEach(entity -> extraHurt(entity, source, splashDamage));
            }

            // 处理基于生命值的额外伤害（最大生命值伤害 + 当前生命值伤害）
            // 额外伤害队列（溅射/额外魔法伤害等）不再触发，避免递归叠加
            if (!isOnDealExtraDamage()) {
                // 通过 AttackEntityEvent 记录判定玩家直接攻击：仅玩家左键攻击实体会触发该事件
                // 命中匹配后立即移除记录，确保同 tick 内的横扫次级目标不再被判定为直接攻击
                boolean playerDirectAttack = attacker instanceof Player
                        && victim.getUUID().equals(PLAYER_DIRECT_ATTACK_TARGET.get(attacker.getUUID()));
                if (playerDirectAttack) {
                    PLAYER_DIRECT_ATTACK_TARGET.remove(attacker.getUUID());
                }
                if (!healthDamagePlayerOnly || playerDirectAttack) {
                    boolean canTrigger = !healthDamageOncePerTick
                            || !HEALTH_DAMAGE_TICK_RECORD.contains(attacker.getUUID());
                    if (canTrigger) {
                        float healthBonus = AttributeCalculationHelper.getHealthBasedDamageBonus(attacker, victim);
                        if (healthBonus > 0.0F) {
                            damage += healthBonus;
                            if (healthDamageOncePerTick) {
                                HEALTH_DAMAGE_TICK_RECORD.add(attacker.getUUID());
                            }
                        }
                    }
                }
            }
        }

        // 物理伤害减免
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            damage *= (float) AttributeCalculationHelper.getPhysicalDamageFactor(victim);
        }

        if (isMagicDamage(source)) {
            // 魔法伤害减免
            damage *= (float) AttributeCalculationHelper.getMagicDamageFactor(victim);

            // 通过护甲减伤规则进行计算
            double magicArmor = victim.getAttributeValue(MAGIC_ARMOR.get());
            double magicArmorToughness = victim.getAttributeValue(MAGIC_ARMOR_TOUGHNESS.get());
            damage = CombatRules.getDamageAfterAbsorb(damage, (float) magicArmor, (float) magicArmorToughness);
        }

        // 环境伤害减免
        if (isEnvironmentalDamage(source) && source.getEntity() == null) {
            damage *= (float) AttributeCalculationHelper.getEnvironmentalDamageFactor(victim);
        }

        // 全伤害减免
        damage *= (float) AttributeCalculationHelper.getAllDamageFactor(victim);

        // 应用修改后的伤害
        event.setAmount(damage);

        // 伤害转化前的伤害记录（增伤后的更高值）
        if (attacker != null) {
            recordDamageForConversion(attacker, damage, true);
        }
    }

    /**
     * 伤害分摊：受害者受到伤害时，范围内「伤害分摊属性值非默认值（>0）」的友方（坦克定位）
     * 有资格成为承担者，伤害按权重在受害者与各承担者之间分摊。
     * 属性值为默认值（0）的实体不会分摊别人的伤害（不进入承担者列表）。
     * 权重 = 各参与者（含受害者）的「属性值 + 默认权重」，保证受害者始终保留非零份额。
     * 可分摊上限 = min(伤害, 受害者最大生命值)，超出部分由受害者独担。
     * 承担者分摊额通过 HurtManager.extraHurt 结算（走正常伤害管线，由 isOnDealExtraDamage 守卫拦截避免级联）。
     * 返回受害者最终应承受的伤害。
     */
    private static float handleDamageSharing(LivingEntity victim, DamageSource source, float damage) {
        Entity attacker = source.getEntity();
        AABB box = victim.getBoundingBox().inflate(damageSharingRange);
        // 只有属性值非默认（>0）的友方才会分摊别人的伤害（坦克定位）
        List<LivingEntity> sharers = victim.level().getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity != victim && entity.isAlive() && CombatHelper.isPartner(victim, entity)
                        && AttributeCalculationHelper.getRawAttributeValue(entity, DAMAGE_SHARING.get()) > 0.0
                        && (attacker == null || entity != attacker));
        if (sharers.isEmpty()) return damage;

        double victimWeight = AttributeCalculationHelper.getDamageSharingWeight(victim);
        double totalWeight = victimWeight;
        for (LivingEntity sharer : sharers) {
            totalWeight += AttributeCalculationHelper.getDamageSharingWeight(sharer);
        }
        if (totalWeight <= 0.0) return damage;

        float sharableDamage = Math.min(damage, victim.getMaxHealth());
        float unsharableDamage = damage - sharableDamage;

        for (LivingEntity sharer : sharers) {
            double sharerWeight = AttributeCalculationHelper.getDamageSharingWeight(sharer);
            float sharedDamage = sharableDamage * (float) (sharerWeight / totalWeight);
            if (sharedDamage > 0.0F) {
                HurtManager.extraHurt(sharer, source, sharedDamage);
            }
        }

        float victimSharedDamage = sharableDamage * (float) (victimWeight / totalWeight);
        return victimSharedDamage + unsharableDamage;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
        LivingEntity victim = event.getEntity();
        AttributeInstance incomingDamageCap = victim.getAttribute(INCOMING_DAMAGE_CAP.get());
        double maxHealth = victim.getAttributeValue(Attributes.MAX_HEALTH);
        float damage = event.getAmount();

        float incomingDamageCapValue = AttributeCalculationHelper.getIncomingDamageCapValue(incomingDamageCap, maxHealth);
        if (damage > incomingDamageCapValue) {
            event.setAmount(incomingDamageCapValue);
        }

        // 处理固定减伤：按固定值减少受到的伤害，减后不低于配置的最低保留值
        // 默认不作用于环境伤害；只减不增，伤害低于最低保留值时原样保留
        double flatReduction = victim.getAttributeValue(FLAT_DAMAGE_REDUCTION.get());
        if (flatReduction > 0.0 && (flatDamageReductionAffectsEnvironmental || !isEnvironmentalDamage(event.getSource()))) {
            float reduced = (float) Math.max(flatDamageReductionMinDamage, event.getAmount() - flatReduction);
            if (reduced < event.getAmount()) {
                event.setAmount(reduced);
            }
        }

        // 处理斩杀阈值：攻击者的斩杀阈值属性，受害者当前血量低于阈值时直接处决
        // executeThresholdBlacklist 配置中的生物类型（默认为原版Boss）免疫斩杀
        if (attacker != null) {
            AttributeInstance executeThresholdAttr = attacker.getAttribute(EXECUTE_THRESHOLD.get());
            double executeThreshold = AttributeCalculationHelper.getExecuteThresholdValue(executeThresholdAttr, maxHealth);

            // 根据伤害类型叠加对应的专项斩杀阈值，与生命窃取的分类型拆分类似
            DamageSource source = event.getSource();
            boolean isMagic = isMagicDamage(source);
            boolean isRangedDamage = source.getDirectEntity() instanceof Projectile;
            boolean isMeleeDamage = attacker == source.getDirectEntity()
                    && attacker.distanceTo(victim) <= getAttackReach(attacker, victim);

            double specificExecuteThreshold;
            if (isMagic) {
                specificExecuteThreshold = AttributeCalculationHelper.getExecuteThresholdValue(
                        attacker.getAttribute(MAGIC_EXECUTE_THRESHOLD.get()), maxHealth);
            } else if (isRangedDamage) {
                specificExecuteThreshold = AttributeCalculationHelper.getExecuteThresholdValue(
                        attacker.getAttribute(RANGED_EXECUTE_THRESHOLD.get()), maxHealth);
            } else if (isMeleeDamage) {
                specificExecuteThreshold = AttributeCalculationHelper.getExecuteThresholdValue(
                        attacker.getAttribute(MELEE_EXECUTE_THRESHOLD.get()), maxHealth);
            } else {
                specificExecuteThreshold = 0;
            }
            executeThreshold += specificExecuteThreshold;

            if (executeThreshold > 0 && victim.getHealth() > 0 && victim.getHealth() <= executeThreshold
                    && !executeThresholdBlacklist.contains(ForgeRegistries.ENTITY_TYPES.getKey(victim.getType()))) {
                if (attacker instanceof Player player) {
                    player.displayClientMessage(Component.translatable("more_attributes.execute_triggered"), true);
                }
                if (!MAConfig.trueDamageMode.bypassesPipeline()) {
                    // 档 0（管线内）：并入事件伤害，不取消事件，
                    // 图腾、死亡事件与掉落全部交回原版处理（等效斩杀）
                    event.setAmount(victim.getHealth());
                    return;
                }
                // 档 1/2/3：复用真伤的强制扣血路径：覆写了 setHealth 的自定义 Boss 也能被处决
                // 特别处理自定义npc（不调用 die），避免出现双倍掉落
                boolean isCustomNpc = EntityType.getKey(victim.getType()).getNamespace().equals("customnpcs");
                StrongTrueDamageHelper.applyStrongerTrueDamage(victim, victim.getHealth(), event.getSource(), !isCustomNpc);
                event.setCanceled(true);
                return;
            }
        }

        // 处理伤害转化与额外真伤/额外魔法伤害
        if (attacker != null && !isOnDealExtraDamage()) {
            // 需要绕过伤害管线强制扣血的真伤总量（转化真伤 + 额外真伤），合并为一次结算
            float directTrueDamage = 0.0F;

            double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
            double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
            if (trueDamageRatio > 0 || magicDamageRatio > 0) {
                float damageRecord = DAMAGE_RECORD.getOrDefault(attacker.getUUID(), 0f);
                float reducedDamageRatio = damageRecord > 0 ? damage / damageRecord : 0;
                float trueDamage = Math.min((float) (damageRecord * trueDamageRatio), damageRecord);
                float magicDamage = (float) Math.min(((damageRecord - trueDamage) * magicDamageRatio), damageRecord - trueDamage);

                if (magicDamage > 0) {
                    DamageSource magicSource = HurtManager.getDamageSource(DamageTypes.MAGIC, attacker);
                    HurtManager.extraHurt(victim, magicSource, magicDamage);
                }

                float finalDamage = (damageRecord - trueDamage - magicDamage) * reducedDamageRatio + trueDamage;

                DAMAGE_RECORD.remove(attacker.getUUID());

                if (MAConfig.trueDamageMode.bypassesPipeline() && trueDamage > 0) {
                    // 真伤从伤害管线中移除以免重复结算，改由下方强制扣血统一结算
                    finalDamage -= trueDamage;
                    directTrueDamage += trueDamage;
                }
                event.setAmount(finalDamage);
            }

            // 玩家近战的额外伤害按攻击冷却缩减（与原版 Player.attack 的冷却缩减公式一致）；
            // 此时 resetAttackStrengthTicker 尚未执行（Forge 将其移至 attack() 末尾），
            // 可直接读到本次挥击的真实冷却。弹射物不消耗攻击冷却，
            // 远程路径（directEntity 为弹射物）维持满额。
            // 伤害转化类属性基于已造成伤害的占比，基础伤害已被原版缩减，无需重复处理
            float extraDamageFactor = attacker == event.getSource().getDirectEntity()
                    ? CombatHelper.getMeleeCooldownFactor(attacker) : 1.0F;

            // 处理额外真实伤害
            double extraTrueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get()) * extraDamageFactor;
            // 本次近战的伤害事件已正常触发，消费兜底登记，避免 tick 结束时重复结算
            StrongTrueDamageHelper.consumePendingMeleeTrueDamage(victim, attacker);
            if (MAConfig.trueDamageMode.bypassesPipeline()) {
                directTrueDamage += (float) extraTrueDamage;
            } else {
                event.setAmount(event.getAmount() + (float) extraTrueDamage);
            }

            // 真伤独立结算（按档位决定强制写入深度与是否绕过死亡保护）
            if (directTrueDamage > 0) {
                StrongTrueDamageHelper.applyStrongerTrueDamage(victim, directTrueDamage, event.getSource());
            }

            // 处理额外魔法伤害
            double extraMagicDamage = attacker.getAttributeValue(EXTRA_MAGIC_DAMAGE.get()) * extraDamageFactor;
            if (extraMagicDamage > 0) {
                DamageSource magicSource = HurtManager.getDamageSource(DamageTypes.MAGIC, attacker);
                HurtManager.extraHurt(victim, magicSource, (float) extraMagicDamage);
            }
        }

        // 处理生命窃取
        // 事件被其他处理器取消时伤害并未实际发生，不再吸血（真伤部分仍照常结算）
        if (attacker != null && !event.isCanceled()) {
            float newDamage = event.getAmount();
            double lifeSteal = attacker.getAttributeValue(LIFE_STEAL.get());

            // 根据伤害类型应用对应的专项生命窃取
            DamageSource source = event.getSource();
            boolean isMagic = isMagicDamage(source);
            boolean isRangedDamage = source.getDirectEntity() instanceof Projectile;
            boolean isMeleeDamage = attacker == source.getDirectEntity()
                    && attacker.distanceTo(victim) <= getAttackReach(attacker, victim);

            double specificLifeSteal;
            if (isMagic) {
                specificLifeSteal = attacker.getAttributeValue(MAGIC_LIFE_STEAL.get());
            } else if (isRangedDamage) {
                specificLifeSteal = attacker.getAttributeValue(RANGED_LIFE_STEAL.get());
            } else if (isMeleeDamage){
                specificLifeSteal = attacker.getAttributeValue(MELEE_LIFE_STEAL.get());
            } else {
                specificLifeSteal = 0;
            }

            double totalLifeSteal = lifeSteal + specificLifeSteal;
            if (totalLifeSteal > 0) {
                float stealAmount = newDamage * (float) totalLifeSteal;
                if (!unlimitedLifeSteal) {
                    stealAmount = Math.min(stealAmount, newDamage);
                }
                attacker.heal(stealAmount);
            }
        }
    }

    private static double getAttackReach(LivingEntity attacker, LivingEntity victim) {
        // 如果是玩家，直接返回触及距离
        if (attacker instanceof Player player) {
            return player.getAttributeValue(ForgeMod.ENTITY_REACH.get());
        }

        // 如果是怪物，尝试从其正在运行的近战目标中获取攻击距离
        if (attacker instanceof Mob mob) {
            return mob.goalSelector.getRunningGoals()
                    // 过滤出正在执行的近战目标
                    .filter(goal -> goal.getGoal() instanceof MeleeAttackGoal)
                    .findFirst()
                    // 将目标转换为 Accessor 接口
                    .map(goal -> (MeleeAttackGoalAccessor) goal.getGoal())
                    // 调用 Accessor 获取攻击距离的平方，并开根号转换为实际距离
                    .map(accessor -> Math.sqrt(accessor.getAttackReachSqr(victim)))
                    // 如果当前没有正在运行的近战目标，返回一个默认的怪物近战距离
                    .orElse(0.0);
        }

        return 2.0;
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttackEntity(AttackEntityEvent event) {
        // 记录玩家本次直接攻击的目标，用于 onHurt 中 healthDamagePlayerOnly 判定
        // 仅 AttackEntityEvent 触发时记录（玩家左键攻击实体），怪物近战与弹射物不会进入此处
        if (event.getTarget() instanceof LivingEntity victim) {
            PLAYER_DIRECT_ATTACK_TARGET.put(event.getEntity().getUUID(), victim.getUUID());
        }
        // 记录本次挥击的攻击冷却比例：伤害事件期间仍可实时读取（Forge 将 resetAttackStrengthTicker
        // 移至 attack() 末尾），但 tick 末兜底结算时 reset 已发生，需以此记录回溯
        CombatHelper.ATTACK_STRENGTH_RECORD.put(event.getEntity().getUUID(), event.getEntity().getAttackStrengthScale(0.5F));
    }

    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        LivingEntity living = event.getEntity();

        double healingEfficiency = living.getAttributeValue(HEALING_EFFICIENCY.get());
        event.setAmount((float) (event.getAmount() * healingEfficiency));
    }

    @SubscribeEvent
    public static void onPlayerXpChange(PlayerXpEvent.XpChange event) {
        Player player = event.getEntity();
        double ratio = player.getAttributeValue(EXPERIENCE_GAIN_RATIO.get());
        if (ratio != 1.0) {
            event.setAmount((int) Math.round(event.getAmount() * ratio));
        }
    }

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (!aggroEnabled) return;
        if (!(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy)) return;
        if (!(event.getNewTarget() instanceof Player originalTarget)) return;

        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        double rangeSqr = range * range;

        // 在追踪范围内搜索仇恨值（AGGRO属性值）最高的可攻击玩家
        Player highestAggroPlayer = mob.level().players().stream()
                .filter(p -> p.distanceToSqr(mob) < rangeSqr && p.isAlive() && !p.isSpectator() && !p.isCreative())
                .max(Comparator.comparingDouble(p -> p.getAttributeValue(AGGRO.get())))
                .orElse(null);

        // 仅当候选玩家仇恨值严格大于当前目标时才切换
        if (highestAggroPlayer != null && highestAggroPlayer != originalTarget) {
            double originalAggro = originalTarget.getAttributeValue(AGGRO.get());
            double highestAggro = highestAggroPlayer.getAttributeValue(AGGRO.get());
            if (originalAggro < highestAggro) {
                event.setNewTarget(highestAggroPlayer);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();

        // 处理再生属性与百分比再生属性
        // regenPerTick=true: 每 tick 平摊回 1/20（当前默认行为，平滑恢复）
        // regenPerTick=false: 每 20 tick 集中回一次完整值（每秒回一次）
        double healthRegen = living.getAttributeValue(HEALTH_REGEN.get());
        double percentRegen = living.getAttributeValue(PERCENT_HEALTH_REGEN.get());
        // 属性为默认值 0 时不调用 heal：heal(0) 仍会发布 LivingHealEvent，造成全服每 tick 的空事件开销
        if (regenPerTick) {
            if (healthRegen > 0) living.heal((float) (healthRegen / 20.0));
            if (percentRegen > 0) living.heal((float) (living.getMaxHealth() * percentRegen / 20.0));
        } else if (living.tickCount % 20 == 0) {
            if (healthRegen > 0) living.heal((float) healthRegen);
            if (percentRegen > 0) living.heal((float) (living.getMaxHealth() * percentRegen));
        }

        if (living instanceof Player player && !player.level().isClientSide) {
            // 处理物品拾取半径（磁吸效果）
            double radius = player.getAttributeValue(PICKUP_RADIUS.get());
            if (radius > 0) {
                AABB box = new AABB(player.getOnPos()).inflate(radius);
                player.level().getEntitiesOfClass(ItemEntity.class, box, item -> true)
                        .forEach(item -> pullTowards(player, item));
                player.level().getEntitiesOfClass(ExperienceOrb.class, box, orb -> true)
                        .forEach(orb -> pullTowards(player, orb));
            }
        }
    }

    /** 将掉落物/经验球向玩家牵引（磁吸效果） */
    private static void pullTowards(Player player, Entity entity) {
        if (entity.isRemoved()) return;
        entity.addDeltaMovement(
                player.position().subtract(entity.getX(), entity.getY(), entity.getZ()).normalize().scale(0.05F).add(0, 0.04F, 0)
        );
        entity.move(MoverType.SELF, entity.getDeltaMovement());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        AttributeCalculationHelper.clearStrengthCache();
        // 伤害记录在同 tick 的 Attack→Hurt→Damage 链路内写入并消费；
        // tick 起始清空可兜底攻击被其他处理器取消导致 LivingDamageEvent 未触发的残留，防止内存驻留
        DAMAGE_RECORD.clear();
        HEALTH_DAMAGE_TICK_RECORD.clear();
        PLAYER_DIRECT_ATTACK_TARGET.clear();
        CombatHelper.ATTACK_STRENGTH_RECORD.clear();
    }

    @SubscribeEvent
    public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
        LivingEntity entity = event.getEntity();
        Item item = event.getItem().getItem();
        CompoundTag tag = entity.getPersistentData();

        // 处理进食速度属性（可食用或可饮用）
        AttributeInstance eatingSpeed = entity.getAttribute(EATING_SPEED.get());
        if (eatingSpeed != null && (item.isEdible() || item instanceof PotionItem
                || item instanceof HoneyBottleItem || item instanceof MilkBucketItem)) {
            event.setDuration(shortenUseDuration(event.getDuration(), eatingSpeed.getValue() - 1, tag, REMAINING_EATING_SPEED_KEY));
        }

        // 处理拉弓速度属性
        AttributeInstance drawSpeed = entity.getAttribute(DRAW_SPEED.get());
        if (drawSpeed != null && item instanceof BowItem) {
            event.setDuration(shortenUseDuration(event.getDuration(), drawSpeed.getValue() - 1, tag, REMAINING_DRAW_SPEED_KEY));
        }
    }

    /** 按速度值缩短使用时间，小数部分累积到下次结算（速度值 = 属性值 - 1，负值减慢） */
    private static int shortenUseDuration(int duration, double speedValue, CompoundTag tag, String remainingKey) {
        double remaining = tag.getDouble(remainingKey);

        double total = speedValue + remaining;
        int newDuration = duration - (int) total;
        tag.putDouble(remainingKey, total - (int) total);

        return newDuration;
    }

    @SubscribeEvent
    public static void onPlayerMining(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        float originalSpeed = event.getOriginalSpeed();

        // 处理挖掘速度属性
        float newSpeed = originalSpeed * (float) player.getAttributeValue(MINING_SPEED.get());
        event.setNewSpeed(newSpeed);
    }

    @SubscribeEvent
    public static void onLivingBreath(LivingBreatheEvent event) {
        LivingEntity entity = event.getEntity();
        double oxygenConsumptionRate = entity.getAttributeValue(OXYGEN_CONSUMPTION_RATE.get());
        CompoundTag tag = entity.getPersistentData();

        int consumptionAmount = event.getConsumeAirAmount();
        int refillAmount = event.getRefillAirAmount();

        // 只有当氧气消耗速率不为1时，才进行处理
        if (oxygenConsumptionRate == 1) return;

        // 计算基础变动量：速率 * 基础变动方向
        // 消耗为正时，基础变动为正（消耗氧气值）；为负时，基础变动为负（恢复氧气值）
        int baseChange = consumptionAmount > 0 ? consumptionAmount : -refillAmount;

        if (baseChange == 0 && tag.getDouble(REMAINING_OXYGEN_CONSUMPTION_KEY) == 0) {
            return; // 既没有消耗也没有恢复，且没有余数，返回
        }

        // 计算实际总变动量（包含前次处理剩余小数）
        double shouldConsumption = oxygenConsumptionRate * baseChange + tag.getDouble(REMAINING_OXYGEN_CONSUMPTION_KEY);
        int finalConsumption = (int) shouldConsumption;

        // 更新小数余数
        double remainder = shouldConsumption % 1.0;
        tag.putDouble(REMAINING_OXYGEN_CONSUMPTION_KEY, remainder);

        // 根据最终计算结果的正负，决定是消耗还是恢复氧气
        if (finalConsumption > 0) {
            // 结果为正：消耗氧气
            event.setCanBreathe(false);
            event.setConsumeAirAmount(finalConsumption);
        } else if (finalConsumption < 0) {
            // 结果为负：恢复氧气
            event.setCanBreathe(true);
            event.setCanRefillAir(true);
            event.setRefillAirAmount(-finalConsumption);
        } else {
            // 结果为 0：不消耗也不恢复（小数积累不足以触发 1 点变动）
            event.setConsumeAirAmount(0);
            event.setRefillAirAmount(0);
        }
    }
}

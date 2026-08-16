package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import com.fu_meng.more_attributes.api.HurtManager;
import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;
import com.fu_meng.more_attributes.mixin.MeleeAttackGoalAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.MAConfig.*;
import static com.fu_meng.more_attributes.MoreAttributesMod.LOGGER;
import static com.fu_meng.more_attributes.MoreAttributesMod.queueServerWork;
import static com.fu_meng.more_attributes.api.AttributeCalculationHelper.getHurtRecoveryAmount;
import static com.fu_meng.more_attributes.api.HurtManager.*;

@Mod.EventBusSubscriber
public class AttributesHandler {
    private static final Map<UUID, Float> DAMAGE_RECORD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HURT_RECORD = new ConcurrentHashMap<>();
    // Map<弹射物, 受击者>
    private static final Map<UUID, UUID> PROJECTILE_RECORD = new ConcurrentHashMap<>();
    // 记录攻击者本 tick 是否已触发过生命值相关伤害提升
    private static final Map<UUID, Long> HEALTH_DAMAGE_TICK_RECORD = new ConcurrentHashMap<>();
    // 记录玩家通过 AttackEntityEvent 触发的直接攻击目标 UUID，用于 healthDamagePlayerOnly 判定
    private static final Map<UUID, UUID> PLAYER_DIRECT_ATTACK_TARGET = new ConcurrentHashMap<>();

    // 多重射击复制品标记键
    private static final String MULTISHOT_CLONE_KEY = "more_attributes$multishot_clone";

    private static boolean isMultishotClone(Entity entity) {
        return entity.getPersistentData().getBoolean(MULTISHOT_CLONE_KEY);
    }

    // 箭矢弹射 PersistentData 键
    private static final String ARROW_BOUNCE_REMAINING_KEY = "more_attributes$bounce_remaining";
    private static final String ARROW_BOUNCE_FALLOFF_KEY = "more_attributes$bounce_falloff";
    private static final String ARROW_BOUNCE_HIT_LIST_KEY = "more_attributes$bounce_hit_list";
    private static final String ARROW_BOUNCE_CLONE_KEY = "more_attributes$bounce_clone";

    private static boolean isBounceClone(Entity entity) {
        return entity.getPersistentData().getBoolean(ARROW_BOUNCE_CLONE_KEY);
    }

    /** 是否为已处理过的克隆弹射物（多重射击或弹射），需跳过属性重复应用与再次克隆 */
    private static boolean isProcessedClone(Entity entity) {
        return isMultishotClone(entity) || isBounceClone(entity);
    }

    private static void addBounceHitEntity(CompoundTag tag, UUID uuid) {
        ListTag list = tag.getList(ARROW_BOUNCE_HIT_LIST_KEY, Tag.TAG_STRING);
        list.add(StringTag.valueOf(uuid.toString()));
        tag.put(ARROW_BOUNCE_HIT_LIST_KEY, list);
    }

    private static boolean isBounceHitEntity(CompoundTag tag, UUID uuid) {
        ListTag list = tag.getList(ARROW_BOUNCE_HIT_LIST_KEY, Tag.TAG_STRING);
        String uuidStr = uuid.toString();
        for (int i = 0; i < list.size(); i++) {
            if (uuidStr.equals(list.getString(i))) return true;
        }
        return false;
    }

    private static void copyBounceHitList(CompoundTag src, CompoundTag dst) {
        ListTag list = src.getList(ARROW_BOUNCE_HIT_LIST_KEY, Tag.TAG_STRING);
        dst.put(ARROW_BOUNCE_HIT_LIST_KEY, list.copy());
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();

        // 处理多重射击复制品的击中销毁
        if (isMultishotClone(projectile)) {
            HitResult hitResult = event.getRayTraceResult();
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                // 击中方块：立即销毁，不触发 onHitBlock（防止箭矢插地、三叉戟滞留等）
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
            } else if (hitResult.getType() == HitResult.Type.ENTITY) {
                // 击中实体：允许造成伤害，下一 tick 销毁复制品（防止后续滞留/拾取/忠诚回归等副作用）
                queueServerWork(() -> {
                    if (!projectile.isRemoved()) {
                        projectile.discard();
                    }
                });
            }
            return;
        }

        // 弹射链已命中过的实体不再被这支箭命中：
        // 覆盖克隆箭从上一受害者体表射出时，下一 tick raycast 立即命中该受害者的边界情况（防止重复伤害与多消耗弹射次数）
        if (projectile instanceof AbstractArrow bounceArrow) {
            HitResult bounceHitResult = event.getRayTraceResult();
            if (bounceHitResult instanceof EntityHitResult bounceEhr
                    && bounceEhr.getEntity() instanceof LivingEntity bounceHitEntity
                    && isBounceHitEntity(bounceArrow.getPersistentData(), bounceHitEntity.getUUID())) {
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
                return;
            }
        }

        // 被击中实体需从命中结果获取（event.getEntity() 返回的是弹射物自身，不能用作受害者）
        LivingEntity victim = null;
        if (event.getRayTraceResult() instanceof EntityHitResult hitEhr && hitEhr.getEntity() instanceof LivingEntity living) {
            victim = living;
        }
        LivingEntity attacker = projectile.getOwner() instanceof LivingEntity living2 ? living2 : null;

        boolean dodged = false;
        if (victim != null && attacker != null) {
            double finalHitProb = AttributeCalculationHelper.getFinalHitProbability(attacker, victim);
            if (victim.getRandom().nextFloat() >= finalHitProb) {
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
                dodged = true;
                if (attacker instanceof Player player) {
                    player.displayClientMessage(Component.translatable("more_attributes.hit_miss", victim.getDisplayName()), true);
                }
            } else {
                PROJECTILE_RECORD.put(projectile.getUUID(), victim.getUUID());
            }
        }

        // 处理箭矢弹射：在闪避判定之后，被闪避（未实际命中）的箭不触发弹射
        if (!dodged && projectile instanceof AbstractArrow arrow) {
            handleArrowBounce(arrow, event);
        }

        // 弹射克隆箭命中后销毁（链式弹射已在上方完成），防止残留仅创造拾取的箭矢
        // 被闪避的克隆箭不销毁：箭被躲开后继续飞行，后续撞墙/命中时自然销毁
        if (!dodged && isBounceClone(projectile)) {
            HitResult hitResult = event.getRayTraceResult();
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                // 击中方块：立即销毁，不插地
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
            } else if (hitResult.getType() == HitResult.Type.ENTITY) {
                // 击中实体：允许造成伤害，下一 tick 销毁（防止穿透继续飞行与残留）
                queueServerWork(() -> {
                    if (!projectile.isRemoved()) {
                        projectile.discard();
                    }
                });
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        // 处理箭矢穿透属性
        if (event.getEntity() instanceof AbstractArrow arrow && arrow.getOwner() instanceof LivingEntity shooter) {
            if (isProcessedClone(arrow)) return; // 跳过克隆弹射物（已从原弹射物继承属性）
            double pierceCount = shooter.getAttributeValue(ARROW_PIERCE.get());
            arrow.setPierceLevel((byte) (arrow.getPierceLevel() + pierceCount));
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
        if (directEntity != null && PROJECTILE_RECORD.containsKey(directEntity.getUUID())) {
            // 移除已处理的弹射物记录，避免内存泄漏
            PROJECTILE_RECORD.remove(directEntity.getUUID());
            return;
        }

        // 近战及其他攻击：进行闪避判定
        try {
            double finalHitProb = AttributeCalculationHelper.getFinalHitProbability(attacker, victim);
            if (victim.getRandom().nextFloat() >= finalHitProb) {
                event.setCanceled(true);
                if (attacker instanceof Player player) {
                    player.displayClientMessage(Component.translatable("more_attributes.hit_miss", victim.getDisplayName()), true);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("计算攻击时出错: {}", e.getMessage());
        }

        // 伤害转化前的伤害记录
        if (!isOnDealExtraDamage()) {
            double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
            double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
            if (trueDamageRatio > 0 || magicDamageRatio > 0) {
                DAMAGE_RECORD.put(attacker.getUUID(), damage);
            }
        }
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

        // 判断伤害源是否属于魔法
        boolean isMagicDamage = source.typeHolder()
                .unwrapKey()
                .map(key -> consideredMagicDamage.contains(key.location()))
                .orElse(false);

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
                double remainingTime = tag.getDouble("RemainingInvincibleTime");
                double newTime = invincibleTime + remainingTime;
                victim.invulnerableTime = (int) newTime;
                tag.putDouble("RemainingInvincibleTime", newTime - (int) newTime);
            } else {
                tag.remove("RemainingInvincibleTime");
            }

            // 处理伤害倍率属性
            float multiplier;
            double damageMultiplier = attacker.getAttributeValue(DAMAGE_MULTIPLIER.get());
            multiplier = (float) damageMultiplier;
            // 魔法伤害倍率
            if (isMagicDamage) {
                double magicDamage = attacker.getAttributeValue(MAGIC_DAMAGE_MULTIPLIER.get());
                multiplier += (float) magicDamage;
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
                        entity != attacker && entity != victim && !isPartner(attacker, entity)
                                && (entity instanceof Enemy || entity.getMobType() == victim.getMobType()))
                        .forEach(entity -> extraHurt(entity, source, splashDamage));
            }

            // 处理基于生命值的额外伤害（最大生命值伤害 + 当前生命值伤害）
            // 额外伤害队列（溅射/额外魔法伤害等）不再触发，避免递归叠加
            // 每tick记录在 onServerTick START 阶段清空，因此 containsKey 即代表本tick已触发过
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
                            || !HEALTH_DAMAGE_TICK_RECORD.containsKey(attacker.getUUID());
                    if (canTrigger) {
                        float healthBonus = AttributeCalculationHelper.getHealthBasedDamageBonus(attacker, victim);
                        if (healthBonus > 0.0F) {
                            damage += healthBonus;
                            if (healthDamageOncePerTick) {
                                HEALTH_DAMAGE_TICK_RECORD.put(attacker.getUUID(), attacker.level().getGameTime());
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

        if (isMagicDamage) {
            // 魔法伤害减免
            damage *= (float) AttributeCalculationHelper.getMagicDamageFactor(victim);

            // 通过护甲减伤规则进行计算
            double magicArmor = victim.getAttributeValue(MAGIC_ARMOR.get());
            double magicArmorToughness = victim.getAttributeValue(MAGIC_ARMOR_TOUGHNESS.get());
            damage = CombatRules.getDamageAfterAbsorb(damage, (float) magicArmor, (float) magicArmorToughness);
        }

        // 环境伤害减免
        boolean isEnvironmental = source.is(DamageTypeTags.IS_FIRE)       // 着火、岩浆、岩浆块、营火等
                || source.is(DamageTypeTags.IS_FALL)       // 摔落
                || source.is(DamageTypeTags.IS_EXPLOSION)  // TNT、烟花、床/重生锚爆炸
                || source.is(DamageTypeTags.IS_DROWNING)   // 溺水
                || source.is(DamageTypeTags.IS_FREEZING)   // 细雪冻伤
                || source.is(DamageTypeTags.IS_LIGHTNING)  // 雷击
                || source.is(DamageTypes.CACTUS)           // 仙人掌
                || source.is(DamageTypes.SWEET_BERRY_BUSH) // 甜浆果丛
                || source.is(DamageTypes.IN_WALL)          // 窒息
                || source.is(DamageTypes.CRAMMING)         // 实体挤压
                || source.is(DamageTypes.FALLING_BLOCK)    // 掉落方块
                || source.is(DamageTypes.FALLING_ANVIL)    // 掉落铁砧
                || source.is(DamageTypes.FALLING_STALACTITE)// 掉落钟乳石
                || source.is(DamageTypes.FLY_INTO_WALL)     // 撞墙
                || source.is(DamageTypes.FELL_OUT_OF_WORLD) //虚空
                || source.is(DamageTypes.OUTSIDE_BORDER);

        if (isEnvironmental && source.getEntity() == null) {
            damage *= (float) AttributeCalculationHelper.getEnvironmentalDamageFactor(victim);
        }

        // 全伤害减免
        damage *= (float) AttributeCalculationHelper.getAllDamageFactor(victim);

        // 应用修改后的伤害
        event.setAmount(damage);

        // 伤害转化前的伤害记录
        if (attacker != null && !isOnDealExtraDamage()) {
            double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
            double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
            if (trueDamageRatio > 0 || magicDamageRatio > 0) {
                float damageRecord = Math.max(DAMAGE_RECORD.getOrDefault(attacker.getUUID(), 0f), damage);
                DAMAGE_RECORD.put(attacker.getUUID(), damageRecord);
            }
        }
    }

    private static boolean isPartner(LivingEntity entity, LivingEntity target) {
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
                entity -> entity != victim && entity.isAlive() && isPartner(victim, entity)
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

        // 处理斩杀阈值：攻击者的斩杀阈值属性，受害者当前血量低于阈值时直接处决（对所有生物生效）
        if (attacker != null) {
            AttributeInstance executeThresholdAttr = attacker.getAttribute(EXECUTE_THRESHOLD.get());
            double executeThreshold = AttributeCalculationHelper.getExecuteThresholdValue(executeThresholdAttr, maxHealth);
            if (executeThreshold > 0 && victim.getHealth() > 0 && victim.getHealth() <= executeThreshold) {
                DamageSource executeSource = event.getSource();
                victim.setHealth(0.0F);
                victim.die(executeSource);
                if (attacker instanceof Player player) {
                    player.displayClientMessage(Component.translatable("more_attributes.execute_triggered"), true);
                }
                event.setCanceled(true);
                return;
            }
        }


        // 处理伤害转化
        if (attacker != null) {
            if (!isOnDealExtraDamage()){
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
                    event.setAmount(finalDamage);
                    if (strongerTrueDamage && trueDamage > 0) {
                        float remainingHealth = victim.getHealth() - trueDamage;
                        queueServerWork(() -> {
                            if (victim.getHealth() >= remainingHealth) {
                                victim.setHealth(remainingHealth);
                            }
                        });
                    }
                }

                // 处理额外真实伤害
                double extraTrueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get());
                event.setAmount(event.getAmount() + (float) extraTrueDamage);

                // 处理额外魔法伤害
                double extraMagicDamage = attacker.getAttributeValue(EXTRA_MAGIC_DAMAGE.get());
                if (extraMagicDamage > 0) {
                    DamageSource magicSource = HurtManager.getDamageSource(DamageTypes.MAGIC, attacker);
                    HurtManager.extraHurt(victim, magicSource, (float) extraMagicDamage);
                }
            }

            // 处理生命窃取
            float newDamage = event.getAmount();
            double lifeSteal = attacker.getAttributeValue(LIFE_STEAL.get());

            // 根据伤害类型应用对应的专项生命窃取
            DamageSource source = event.getSource();
            boolean isMagicDamage = source.typeHolder()
                    .unwrapKey()
                    .map(key -> consideredMagicDamage.contains(key.location()))
                    .orElse(false);
            boolean isRangedDamage = source.getDirectEntity() instanceof Projectile;
            boolean isMeleeDamage = attacker == source.getDirectEntity()
                    && attacker.distanceTo(victim) <= getAttackReach(attacker, victim);

            double specificLifeSteal;
            if (isMagicDamage) {
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
        Player attacker = event.getEntity();
        LivingEntity victim = event.getTarget() instanceof LivingEntity living ? living : null;

        if (attacker == null || victim == null) return;

        // 记录玩家本次直接攻击的目标，用于 onHurt 中 healthDamagePlayerOnly 判定
        // 仅 AttackEntityEvent 触发时记录（玩家左键攻击实体），怪物近战与弹射物不会进入此处
        PLAYER_DIRECT_ATTACK_TARGET.put(attacker.getUUID(), victim.getUUID());

        if (!strongerTrueDamage) return;

        if (victim.isInvulnerable() || victim instanceof Player playerVictim && playerVictim.getAbilities().invulnerable) {
            double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get());
            LivingEntityAccessor victimMixin = (LivingEntityAccessor) victim;
            DamageSource damageSource = attacker.damageSources().playerAttack(attacker);
            if (victim.getHealth() - trueDamage > 0) {
                victim.setHealth((float) (victim.getHealth() - trueDamage));
                victimMixin.playHurtSound(damageSource);
                victim.hurtTime = 20;
                victim.hurtMarked = true;
            } else {
                victim.die(damageSource);
                SoundEvent soundevent = victimMixin.getDeathSound();
                if (soundevent != null) {
                    victim.playSound(soundevent, victimMixin.getSoundVolume(), (victim.getRandom().nextFloat() - victim.getRandom().nextFloat()) * 0.2F + 1.0F);
                }
                queueServerWork(() -> {
                    if (!victim.isDeadOrDying()) {
                        victim.setHealth(Float.NEGATIVE_INFINITY);
                        victim.remove(Entity.RemovalReason.KILLED);
                    }
                });
            }
        }
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
        if (regenPerTick) {
            living.heal((float) (healthRegen / 20.0));
            living.heal((float) (living.getMaxHealth() * percentRegen / 20.0));
        } else if (living.tickCount % 20 == 0) {
            living.heal((float) healthRegen);
            living.heal((float) (living.getMaxHealth() * percentRegen));
        }

        if (living instanceof Player player && !player.level().isClientSide) {
            // 处理物品拾取半径（磁吸效果）
            double radius = player.getAttributeValue(PICKUP_RADIUS.get());
            if (radius > 0) {
                AABB box = new AABB(player.getOnPos()).inflate(radius);
                player.level().getEntitiesOfClass(ItemEntity.class, box, item -> true).forEach(item -> {
                    if (item.isRemoved()) return;
                    item.addDeltaMovement(
                            player.position().subtract(item.getX(), item.getY(), item.getZ()).normalize().scale(0.05F).add(0, 0.04F, 0)
                    );
                    item.move(MoverType.SELF, item.getDeltaMovement());
                });
                player.level().getEntitiesOfClass(ExperienceOrb.class, box, orb -> true).forEach(orb -> {
                    if (orb.isRemoved()) return;
                    orb.addDeltaMovement(
                            player.position().subtract(orb.getX(), orb.getY(), orb.getZ()).normalize().scale(0.05F).add(0, 0.04F, 0)
                    );
                    orb.move(MoverType.SELF, orb.getDeltaMovement());
                });
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            AttributeCalculationHelper.clearStrengthCache();
            HEALTH_DAMAGE_TICK_RECORD.clear();
            PLAYER_DIRECT_ATTACK_TARGET.clear();
        }
    }

    @SubscribeEvent
    public static void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
        LivingEntity entity = event.getEntity();
        Item item = event.getItem().getItem();
        CompoundTag tag = entity.getPersistentData();

        // 处理进食速度属性
        AttributeInstance eatingSpeed = entity.getAttribute(EATING_SPEED.get());
        if (eatingSpeed != null) {
            // 判断物品是否可食用或可饮用
            boolean isEdible = item.isEdible();
            boolean isDrinkable = item instanceof PotionItem ||
                    item instanceof HoneyBottleItem ||
                    item instanceof MilkBucketItem;

            // 计算新的使用时间
            if (isEdible || isDrinkable) {
                double eatingSpeedValue = eatingSpeed.getValue() - 1;
                double remainingEatingSpeed = tag.getDouble("remainingEatingSpeed");

                double totalSpeed = eatingSpeedValue + remainingEatingSpeed;
                int newDuration = event.getDuration() - (int) totalSpeed;
                remainingEatingSpeed = totalSpeed - (int) totalSpeed;
                tag.putDouble("remainingEatingSpeed", remainingEatingSpeed);

                event.setDuration(newDuration);
            }
        }

        // 处理拉弓速度属性
        AttributeInstance drawSpeed = entity.getAttribute(DRAW_SPEED.get());
        if (drawSpeed != null) {
            // 判断物品是否为弓
            boolean isBow = item instanceof BowItem;

            // 计算新的使用时间
            if (isBow) {
                double drawingSpeedValue = drawSpeed.getValue() - 1;
                double remainingDrawSpeed = tag.getDouble("remainingDrawSpeed");

                double totalSpeed = drawingSpeedValue + remainingDrawSpeed;
                int newDuration = event.getDuration() - (int) totalSpeed;
                remainingDrawSpeed = totalSpeed - (int) totalSpeed;
                tag.putDouble("remainingDrawSpeed", remainingDrawSpeed);

                event.setDuration(newDuration);
            }
        }
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
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) {
            if (isProcessedClone(arrow)) return; // 跳过克隆弹射物
            if (arrow.getOwner() instanceof LivingEntity living) {
                double arrowDamage = living.getAttributeValue(ARROW_DAMAGE.get());
                double arrowVelocity = living.getAttributeValue(ARROW_VELOCITY.get());
                arrow.setBaseDamage(arrow.getBaseDamage() * arrowDamage);
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(arrowVelocity));

                // 处理箭矢精准度：负值时对原箭矢施加随机角度偏移
                double arrowAccuracy = living.getAttributeValue(ARROW_ACCURACY.get());
                if (arrowAccuracy < 0) {
                    applyAccuracyOffset(arrow, arrowAccuracy);
                }

                // 处理箭矢弹射：写入弹射次数与衰减系数到箭矢 PersistentData
                double arrowBounce = living.getAttributeValue(ARROW_BOUNCE.get());
                if (arrowBounce > 0) {
                    CompoundTag arrowTag = arrow.getPersistentData();
                    arrowTag.putInt(ARROW_BOUNCE_REMAINING_KEY, (int) arrowBounce);
                    arrowTag.putDouble(ARROW_BOUNCE_FALLOFF_KEY, arrowBounceDamageFalloff);
                }
            }
        }
    }

    /**
     * 多重射击：弹射物加入世界时，根据发射者的 MULTISHOT_AMOUNT 属性克隆额外弹射物。
     * 使用 LOW 优先级确保在 ARROW_DAMAGE/VELOCITY/PIERCE 等属性处理之后执行，
     * 使复制品能从已修改的原弹射物状态继承正确数值。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMultishotProjectileJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        if (isProcessedClone(projectile)) return;

        Entity owner = projectile.getOwner();
        if (!(owner instanceof LivingEntity shooter)) return;

        double multishotAmount = shooter.getAttributeValue(MULTISHOT_AMOUNT.get());
        int extraCount = (int) multishotAmount;
        if (extraCount <= 0) return;

        spawnMultishotClones(projectile, shooter, extraCount);
    }

    /**
     * 以扇形分布克隆额外弹射物。
     * 采用交替左右分布：i=0→左侧第1档，i=1→右侧第1档，i=2→左侧第2档，依此类推。
     * 每档间隔 multishotSpreadPerPoint 度（默认10°，与原版弩多重射击一致）。
     * 方向旋转使用四元数绕发射者上方向轴，与原版 CrossbowItem 实现一致。
     */
    private static void spawnMultishotClones(Projectile original, LivingEntity shooter, int extraCount) {
        Vec3 originalMotion = original.getDeltaMovement();
        double speed = originalMotion.length();
        if (speed < 1.0E-6) return;

        Vec3 dir = originalMotion.normalize();
        Vec3 up = shooter.getUpVector(1.0F);

        double arrowAccuracy = shooter.getAttributeValue(ARROW_ACCURACY.get());
        // 正值精准度减少多重射击扩散度：每点精准度降低 1 度散布角，最低为 0
        float effectiveSpread = (float) Math.max(0, multishotSpreadPerPoint - Math.max(0, arrowAccuracy));

        for (int i = 0; i < extraCount; i++) {
            int side = (i % 2 == 0) ? -1 : 1;
            int step = i / 2 + 1;
            float angleDeg = side * step * effectiveSpread;
            float angleRad = angleDeg * ((float) Math.PI / 180.0F);

            // 通过 NBT 序列化/反序列化创建同类型弹射物副本，兼容模组弹射物
            CompoundTag nbt = original.saveWithoutId(new CompoundTag());
            nbt.remove("UUID"); // 移除 UUID 以避免冲突，create() 会分配新 UUID
            Entity cloneEntity = original.getType().create(original.level());
            if (!(cloneEntity instanceof Projectile cloneProjectile)) continue;
            cloneEntity.load(nbt);

            // 标记为复制品
            cloneEntity.getPersistentData().putBoolean(MULTISHOT_CLONE_KEY, true);

            // 确保所有者正确
            cloneProjectile.setOwner(shooter);

            // 用四元数绕上方向轴旋转原始方向向量，实现扇形分布
            Quaternionf quaternion = new Quaternionf().setAngleAxis(angleRad, (float) up.x, (float) up.y, (float) up.z);
            Vector3f rotated = dir.toVector3f().rotate(quaternion);

            // 设置克隆体的方向和速度（inaccuracy=0 保证精确角度，无随机偏移）
            cloneProjectile.shoot(rotated.x(), rotated.y(), rotated.z(), (float) speed, 0.0F);

            // 负值精准度：对每个复制品施加独立的随机角度偏移
            if (arrowAccuracy < 0) {
                applyAccuracyOffset(cloneProjectile, arrowAccuracy);
            }

            // 箭矢类复制品标记为仅创造模式可拾取，防止刷箭
            if (cloneEntity instanceof AbstractArrow cloneArrow) {
                cloneArrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            original.level().addFreshEntity(cloneEntity);
        }
    }

    /**
     * 对弹射物施加随机角度偏移（仅当 arrow_accuracy < 0 时调用）。
     * 水平方向（偏航）偏移范围：±|accuracy|/2 度
     * 竖直方向（俯仰）偏移范围：±|accuracy|/20 度
     */
    private static void applyAccuracyOffset(Projectile projectile, double accuracy) {
        double magnitude = -accuracy;
        float yawRange = (float) (magnitude * 0.5);    // 水平 ±度
        float pitchRange = (float) (magnitude * 0.05);  // 竖直 ±度

        float yawDeg = (projectile.level().random.nextFloat() * 2.0F - 1.0F) * yawRange;
        float pitchDeg = (projectile.level().random.nextFloat() * 2.0F - 1.0F) * pitchRange;

        Vec3 motion = projectile.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-6) return;

        Vec3 dir = motion.normalize();

        // 建立局部正交基：dir(前)、right(右)、up(上)
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(worldUp);
        if (right.lengthSqr() < 1.0E-10) {
            right = new Vec3(0, 0, 1); // 方向与世界 Y 轴平行时改用 Z 轴
        }
        right = right.normalize();
        Vec3 up = right.cross(dir).normalize();

        float yawRad = yawDeg * ((float) Math.PI / 180.0F);
        float pitchRad = pitchDeg * ((float) Math.PI / 180.0F);

        // 先绕 up 轴旋转（水平偏航），再绕 right 轴旋转（竖直俯仰）
        Vector3f rotated = dir.toVector3f();
        rotated.rotate(new Quaternionf().setAngleAxis(yawRad, (float) up.x, (float) up.y, (float) up.z));
        rotated.rotate(new Quaternionf().setAngleAxis(pitchRad, (float) right.x, (float) right.y, (float) right.z));

        projectile.setDeltaMovement(new Vec3(rotated.x(), rotated.y(), rotated.z()).scale(speed));
    }

    /**
     * 箭矢弹射：箭矢命中生物时，若仍有弹射次数，克隆一支新箭射向最近的未命中敌人。
     * 每次弹射后原箭矢剩余次数 -1（兼容穿透：穿透箭每命中一个目标可各触发一次弹射）。
     */
    private static void handleArrowBounce(AbstractArrow arrow, ProjectileImpactEvent event) {
        // 仅服务端处理：客户端预测 tick 也会触发本事件，客户端克隆会产生幽灵箭导致不同步
        if (arrow.level().isClientSide()) return;

        CompoundTag tag = arrow.getPersistentData();
        int remaining = tag.getInt(ARROW_BOUNCE_REMAINING_KEY);
        if (remaining <= 0) return;

        // 仅在命中生物时触发弹射
        HitResult hitResult = event.getRayTraceResult();
        if (hitResult.getType() != HitResult.Type.ENTITY) return;
        if (!(hitResult instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof LivingEntity currentVictim)) return;

        LivingEntity shooter = arrow.getOwner() instanceof LivingEntity living ? living : null;
        if (shooter == null) return;

        // 将当前受害者加入已命中列表（避免克隆箭回头命中）
        addBounceHitEntity(tag, currentVictim.getUUID());

        // 搜索下一目标
        LivingEntity nextTarget = findBounceTarget(arrow, shooter, currentVictim);
        if (nextTarget == null) return;

        // 克隆一支新箭射向下一目标，并递减原箭矢剩余次数
        spawnBounceClone(arrow, shooter, nextTarget, remaining);
        tag.putInt(ARROW_BOUNCE_REMAINING_KEY, remaining - 1);
    }

    /**
     * 在弹射搜索范围内寻找最近的有效目标：非发射者、非当前命中链中的实体、非友方，
     * 且为「与当前受害者同种的实体」或「敌对实体」。
     * 射击非敌对生物（如村民、动物等测试实体）时，弹射会在同种实体间传导。
     */
    private static LivingEntity findBounceTarget(AbstractArrow arrow, LivingEntity shooter, LivingEntity currentVictim) {
        Vec3 origin = arrow.position();
        AABB box = new AABB(origin, origin).inflate(arrowBounceSearchRange);
        CompoundTag tag = arrow.getPersistentData();
        List<LivingEntity> candidates = arrow.level().getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive()
                        && entity != shooter
                        && entity != currentVictim
                        && !isPartner(shooter, entity)
                        && !isBounceHitEntity(tag, entity.getUUID())
                        && (entity instanceof Enemy || entity.getType() == currentVictim.getType()));
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
        return candidates.get(0);
    }

    /**
     * 通过 NBT 序列化克隆一支新箭，从命中点射向下一目标。
     * 复用多重射击的克隆范式：标记为弹射克隆以跳过属性重复应用与再次克隆。
     */
    private static void spawnBounceClone(AbstractArrow original, LivingEntity shooter,
                                         LivingEntity nextTarget, int remaining) {
        CompoundTag originalTag = original.getPersistentData();
        double falloff = originalTag.contains(ARROW_BOUNCE_FALLOFF_KEY)
                ? originalTag.getDouble(ARROW_BOUNCE_FALLOFF_KEY) : arrowBounceDamageFalloff;

        CompoundTag nbt = original.saveWithoutId(new CompoundTag());
        nbt.remove("UUID"); // 移除 UUID 以避免冲突，create() 会分配新 UUID
        Entity cloneEntity = original.getType().create(original.level());
        if (!(cloneEntity instanceof AbstractArrow clone)) return;
        cloneEntity.load(nbt);

        CompoundTag cloneTag = cloneEntity.getPersistentData();
        // 标记为弹射克隆（跳过多重射击/箭矢属性重复处理）
        cloneTag.putBoolean(ARROW_BOUNCE_CLONE_KEY, true);
        // 设置弹射状态：剩余次数 -1，衰减系数，显式复制已命中列表（防止 load 未携带 PersistentData）
        cloneTag.putInt(ARROW_BOUNCE_REMAINING_KEY, remaining - 1);
        cloneTag.putDouble(ARROW_BOUNCE_FALLOFF_KEY, falloff);
        copyBounceHitList(originalTag, cloneTag);

        // 伤害衰减
        clone.setBaseDamage(clone.getBaseDamage() * falloff);

        // 清除穿透等级：弹射链跳数由剩余次数控制，防止克隆箭穿透造成不可预测的连锁分支与残留
        clone.setPierceLevel((byte) 0);

        // 确保所有者正确
        clone.setOwner(shooter);

        // 从命中点射向下一目标躯干中心
        Vec3 start = original.position();
        Vec3 targetCenter = nextTarget.position().add(0, nextTarget.getBbHeight() * 0.5, 0);
        Vec3 dir = targetCenter.subtract(start).normalize();
        clone.setPos(start.x, start.y, start.z);
        double speed = Math.max(original.getDeltaMovement().length(), 1.0);
        clone.shoot(dir.x, dir.y, dir.z, (float) speed, 0.0F);

        // 防刷箭
        clone.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        original.level().addFreshEntity(clone);
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

        if (baseChange == 0 && tag.getDouble("remainingOxygenConsumption") == 0) {
            return; // 既没有消耗也没有恢复，且没有余数，返回
        }

        // 计算实际总变动量（包含前次处理剩余小数）
        double shouldConsumption = oxygenConsumptionRate * baseChange + tag.getDouble("remainingOxygenConsumption");
        int finalConsumption = (int) shouldConsumption;

        // 更新小数余数
        double remainder = shouldConsumption % 1.0;
        tag.putDouble("remainingOxygenConsumption", remainder);

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
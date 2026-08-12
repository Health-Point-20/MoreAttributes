package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import com.fu_meng.more_attributes.api.HurtManager;
import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;
import com.fu_meng.more_attributes.mixin.MeleeAttackGoalAccessor;
import net.minecraft.nbt.CompoundTag;
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
import static com.fu_meng.more_attributes.api.HurtManager.*;

@Mod.EventBusSubscriber
public class AttributesHandler {
    private static final Map<UUID, Float> DAMAGE_RECORD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HURT_RECORD = new ConcurrentHashMap<>();
    // Map<弹射物, 受击者>
    private static final Map<UUID, UUID> PROJECTILE_RECORD = new ConcurrentHashMap<>();

    // 多重射击复制品标记键
    private static final String MULTISHOT_CLONE_KEY = "more_attributes$multishot_clone";

    private static boolean isMultishotClone(Entity entity) {
        return entity.getPersistentData().getBoolean(MULTISHOT_CLONE_KEY);
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

        LivingEntity victim = event.getEntity() instanceof LivingEntity living ? living : null;
        LivingEntity attacker = projectile.getOwner() instanceof LivingEntity living ? living : null;

        if (victim == null || attacker == null) return;

        double finalHitProb = AttributeCalculationHelper.getFinalHitProbability(attacker, victim);
        if (victim.getRandom().nextFloat() >= finalHitProb) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
            if (attacker instanceof Player player) {
                player.displayClientMessage(Component.translatable("more_attributes.hit_miss", victim.getDisplayName()), true);
            }
        } else {
            PROJECTILE_RECORD.put(projectile.getUUID(), victim.getUUID());
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        // 处理箭矢穿透属性
        if (event.getEntity() instanceof AbstractArrow arrow && arrow.getOwner() instanceof LivingEntity shooter) {
            if (isMultishotClone(arrow)) return; // 跳过多重射击复制品（已从原弹射物继承属性）
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

        // 处理忽视无敌时间
        if (attacker != null) {
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
        boolean isEnvironmental = source.is(DamageTypeTags.IS_FIRE)       // 着火、岩浆、岩浆块、营火、火球等
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

        // 处理再生属性
        double healthRegen = living.getAttributeValue(HEALTH_REGEN.get());
        living.heal((float) (healthRegen/20.0));

        // 处理百分比再生属性
        double percentRegen = living.getAttributeValue(PERCENT_HEALTH_REGEN.get());
        living.heal((float) (living.getMaxHealth() * percentRegen/20.0));

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
            if (isMultishotClone(arrow)) return; // 跳过多重射击复制品
            if (arrow.getOwner() instanceof LivingEntity living) {
                double arrowDamage = living.getAttributeValue(ARROW_DAMAGE.get());
                double arrowVelocity = living.getAttributeValue(ARROW_VELOCITY.get());
                arrow.setBaseDamage(arrow.getBaseDamage() * arrowDamage);
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(arrowVelocity));
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
        if (isMultishotClone(projectile)) return;

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

        for (int i = 0; i < extraCount; i++) {
            int side = (i % 2 == 0) ? -1 : 1;
            int step = i / 2 + 1;
            float angleDeg = side * step * (float) multishotSpreadPerPoint;
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

            // 箭矢类复制品标记为仅创造模式可拾取，防止刷箭
            if (cloneEntity instanceof AbstractArrow cloneArrow) {
                cloneArrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            original.level().addFreshEntity(cloneEntity);
        }
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
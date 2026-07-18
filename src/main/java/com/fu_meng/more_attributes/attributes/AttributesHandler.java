package com.fu_meng.more_attributes.attributes;

import com.fu_meng.more_attributes.api.AttributeCalculationHelper;
import com.fu_meng.more_attributes.api.HurtManager;
import com.fu_meng.more_attributes.mixin.LivingEntityMixin;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.MAConfig.strongerTrueDamage;
import static com.fu_meng.more_attributes.MAConfig.unlimitedLiftSteal;
import static com.fu_meng.more_attributes.MoreAttributesMod.LOGGER;
import static com.fu_meng.more_attributes.MoreAttributesMod.queueServerWork;
import static com.fu_meng.more_attributes.api.HurtManager.isOnDealExtraDamage;

@Mod.EventBusSubscriber
public class AttributesHandler {
    private static final Map<UUID, Float> DAMAGE_RECORD = new ConcurrentHashMap<>();

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttack(LivingAttackEvent event) {
        float damage = event.getAmount();
        LivingEntity victim = event.getEntity();
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity entity ? entity : null;

        if (attacker == null) return;

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
        double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
        double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
        if (trueDamageRatio > 0 || magicDamageRatio > 0) {
            DAMAGE_RECORD.put(attacker.getUUID(), damage);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        DamageSource source = event.getSource();
        LivingEntity attacker = source.getEntity() instanceof LivingEntity entity ? entity : null;
        float amount = event.getAmount();

        // 处理无敌时间
        double invincibleTimeRate = victim.getAttributeValue(INVINCIBLE_TIME.get());
        if (invincibleTimeRate > 0) {
            victim.invulnerableTime = (int) (victim.invulnerableTime * invincibleTimeRate);
        }

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

            // 处理暴击
            double criticalChance = attacker.getAttributeValue(CRITICAL_CHANCE.get());
            double criticalDamage = attacker.getAttributeValue(CRITICAL_DAMAGE.get());
            if (attacker.getRandom().nextDouble() <= criticalChance) {
                amount *= (float) criticalDamage;
            }
        }

        // 物理伤害减免
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            amount *= (float) AttributeCalculationHelper.getPhysicalDamageFactor(victim);
        }

        // 判断伤害源是否属于魔法
        if (source.is(TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("more_attributes", "is_magic_damage")))) {
            // 魔法伤害减免
            amount *= (float) AttributeCalculationHelper.getMagicDamageFactor(victim);

            // 通过护甲减伤规则进行计算
            double magicArmor = victim.getAttributeValue(MAGIC_ARMOR.get());
            double magicArmorToughness = victim.getAttributeValue(MAGIC_ARMOR_TOUGHNESS.get());
            amount = CombatRules.getDamageAfterAbsorb(amount, (float) magicArmor, (float) magicArmorToughness);
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
            amount *= (float) AttributeCalculationHelper.getEnvironmentalDamageFactor(victim);
        }

        // 全伤害减免
        amount *= (float) AttributeCalculationHelper.getAllDamageFactor(victim);

        event.setAmount(amount);

        // 伤害转化前的伤害记录
        if (attacker != null) {
            double trueDamageRatio = attacker.getAttributeValue(TRUE_DAMAGE_RATIO.get());
            double magicDamageRatio = attacker.getAttributeValue(MAGIC_DAMAGE_RATIO.get());
            if (trueDamageRatio > 0 || magicDamageRatio > 0) {
                float damageRecord = Math.max(DAMAGE_RECORD.getOrDefault(attacker.getUUID(), 0f), amount);
                DAMAGE_RECORD.put(attacker.getUUID(), damageRecord);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
        LivingEntity victim = event.getEntity();
        AttributeInstance incomingDamageCap = victim.getAttribute(INCOMING_DAMAGE_CAP.get());
        double maxHealth = victim.getAttributeValue(Attributes.MAX_HEALTH);
        float damage = event.getAmount();

        float incomingDamageCapValue = getIncomingDamageCapValue(incomingDamageCap, maxHealth);
        if (damage > incomingDamageCapValue) {
            event.setAmount(incomingDamageCapValue);
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
                DamageSource magicSource = HurtManager.getDamageSource(DamageTypes.MAGIC, attacker);
                HurtManager.extraHurt(victim, magicSource, (float) extraMagicDamage);
            }
            // 处理生命窃取
            damage = event.getAmount();
            double lifeSteal = attacker.getAttributeValue(LIFE_STEAL.get());
            if (lifeSteal > 0) {
                float stealAmount = damage * (float) lifeSteal;
                if (!unlimitedLiftSteal) {stealAmount = Math.min(stealAmount, damage);}

                attacker.heal(stealAmount);
            }
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttackEntity(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        LivingEntity victim = event.getTarget() instanceof LivingEntity living ? living : null;

        if (attacker == null || victim == null) return;
        if (!strongerTrueDamage) return;

        if (victim.isInvulnerable() || victim instanceof Player playerVictim && playerVictim.getAbilities().invulnerable) {
            double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get());
            LivingEntityMixin victimMixin = (LivingEntityMixin) victim;
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

    public static float getIncomingDamageCapValue(AttributeInstance incomingDamageCap, double maxHealth) {
        if (incomingDamageCap != null) {
            Set<AttributeModifier> modifiers = incomingDamageCap.getModifiers();
            Set<AttributeModifier> additions = modifiers.stream().filter(modifier -> modifier.getOperation() == AttributeModifier.Operation.ADDITION).collect(Collectors.toSet());
            Set<AttributeModifier> multiplications = modifiers.stream().filter(modifier ->
                    modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_BASE
                            || modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL).collect(Collectors.toSet());
            // 筛选出最小值
            double minAddition = additions.stream().min(Comparator.comparingDouble(AttributeModifier::getAmount)).map(AttributeModifier::getAmount).orElse(Double.MAX_VALUE);
            double minMultiplication =  1 + multiplications.stream().min(Comparator.comparingDouble(AttributeModifier::getAmount)).map(AttributeModifier::getAmount).orElse(1D);

            return (float) Math.min(minAddition, minMultiplication * maxHealth);
        }
        return Float.POSITIVE_INFINITY;
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity living = event.getEntity();
        double healthRegen = living.getAttributeValue(HEALTH_REGEN.get());
        living.heal((float) (healthRegen/20.0));
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
            if (arrow.getOwner() instanceof LivingEntity living) {
                double arrowDamage = living.getAttributeValue(ARROW_DAMAGE.get());
                double arrowVelocity = living.getAttributeValue(ARROW_VELOCITY.get());
                arrow.setBaseDamage(arrow.getBaseDamage() * arrowDamage);
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(arrowVelocity));
            }
        }

    }
}
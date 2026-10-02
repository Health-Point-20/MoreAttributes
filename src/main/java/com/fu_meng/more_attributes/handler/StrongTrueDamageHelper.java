package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.MAConfig.TrueDamageMode;
import com.fu_meng.more_attributes.MoreAttributesMod;
import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sun.misc.Unsafe;

import static com.fu_meng.more_attributes.AttributesRegistry.EXTRA_TRUE_DAMAGE;

/**
 * 真伤（{@code trueDamageMode}）的强制写入结算与兜底，也供斩杀阈值复用。
 */
@Mod.EventBusSubscriber
public class StrongTrueDamageHelper {

    /** 玩家近战已发起、目标可能在自身 hurt() 里直接取消的“额外真伤”登记 */
    private static final Map<LivingEntity, Player> PENDING_MELEE_TRUE_DAMAGE = new ConcurrentHashMap<>();

    /** 由本模组真伤/斩杀导致、正在 {@code die()} 调用栈内的目标 */
    private static final Set<LivingEntity> PENDING_FORCED_DEATH = ConcurrentHashMap.newKeySet();

    /** 本次近战的伤害事件已正常触发，消费兜底登记 */
    static void consumePendingMeleeTrueDamage(LivingEntity victim, LivingEntity attacker) {
        if (PENDING_MELEE_TRUE_DAMAGE.get(victim) == attacker) {
            PENDING_MELEE_TRUE_DAMAGE.remove(victim);
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttackEntity(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        LivingEntity victim = event.getTarget() instanceof LivingEntity living ? living : null;

        if (victim == null) return;
        if (!MAConfig.trueDamageMode.bypassesPipeline()) return;

        double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get()) * CombatHelper.getMeleeCooldownFactor(attacker);

        if (victim.isInvulnerable() || victim instanceof Player playerVictim && playerVictim.getAbilities().invulnerable) {
            if (trueDamage <= 0) return;
            DamageSource damageSource = attacker.damageSources().playerAttack(attacker);
            // 真伤直接扣血，致死时已调用 die()
            if (applyStrongerTrueDamage(victim, trueDamage, damageSource)) {
                // 伤害管线不会触发，手动补上受击表现
                playHurtFeedback(victim, damageSource);
            }
        } else if (trueDamage > 0 && !victim.level().isClientSide) {
            // 目标在 hurt() 里取消伤害时事件不触发，登记后由 tick 末兜底结算
            PENDING_MELEE_TRUE_DAMAGE.put(victim, attacker);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 深度探针缓存持有实体引用，tick 起始清空
            DEEP_HEALTH_CACHE.clear();
            return;
        }

        // tick 末兜底结算登记表里的近战真伤；被闪避的目标不结算
        if (!PENDING_MELEE_TRUE_DAMAGE.isEmpty()) {
            PENDING_MELEE_TRUE_DAMAGE.forEach((victim, attacker) -> {
                UUID dodgedAttacker = CombatHelper.DODGED_MELEE.get(victim.getUUID());
                boolean wasDodged = dodgedAttacker != null && dodgedAttacker.equals(attacker.getUUID());
                if (!wasDodged && shouldSettleTrueDamage(victim)) {
                    // 按本次挥击记录的攻击冷却比例回溯结算
                    Float attackStrength = CombatHelper.ATTACK_STRENGTH_RECORD.get(attacker.getUUID());
                    float cooldownFactor = attackStrength != null ? CombatHelper.getMeleeCooldownFactor(attackStrength) : 1.0F;
                    double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get()) * cooldownFactor;
                    if (trueDamage > 0) {
                        DamageSource source = attacker.damageSources().playerAttack(attacker);
                        // 伤害管线未触发，结算后手动补上受击表现
                        if (applyStrongerTrueDamage(victim, trueDamage, source)) {
                            playHurtFeedback(victim, source);
                        }
                    }
                }
            });
            PENDING_MELEE_TRUE_DAMAGE.clear();
        }
        CombatHelper.DODGED_MELEE.clear();
    }

    /** 复刻原版 hurt 的受击表现（音效/受击帧/同步标记） */
    private static void playHurtFeedback(LivingEntity victim, DamageSource source) {
        LivingEntityAccessor victimMixin = (LivingEntityAccessor) victim;
        if (currentHealth(victim) > 0.0F) {
            victimMixin.playHurtSound(source);
            victim.hurtTime = 20;
            victim.hurtMarked = true;
        }
    }

    /** 当前真实血量；{@code getHealth()} 被门控时回退到原版同步血量镜像 */
    private static float currentHealth(LivingEntity victim) {
        float health = healthOf(victim);
        if (health > 0.0F) return health;
        float mirrored = readVanillaHealthData(victim);
        return Float.isNaN(mirrored) ? health : mirrored;
    }

    /** 是否仍应给该目标结算真伤：未移除且真实血量 > 0 */
    private static boolean shouldSettleTrueDamage(LivingEntity victim) {
        if (victim.isRemoved() || victim.level().isClientSide) return false;
        return currentHealth(victim) > 0.0F;
    }

    /** 真伤独立结算（档 1/2/3，对所有生物，含玩家）：按档位分层依次尝试强制写入 */
    public static boolean applyStrongerTrueDamage(LivingEntity victim, double amount, DamageSource source) {
        return applyStrongerTrueDamage(victim, amount, source, true);
    }

    static boolean applyStrongerTrueDamage(LivingEntity victim, double amount, DamageSource source,
                                           boolean callDie) {
        if (amount <= 0.0 || victim.level().isClientSide) return false;
        TrueDamageMode mode = MAConfig.trueDamageMode;
        // 档 0（管线内）不走强制扣血路径
        if (!mode.bypassesPipeline()) return false;

        float healthBefore = victim.getHealth();
        float mirrored = readVanillaHealthData(victim);
        boolean gatedGetter = healthBefore <= 0.0F && mirrored > 0.0F;
        if (gatedGetter) healthBefore = mirrored;
        if (healthBefore <= 0.0F) return false;
        // 档 1+ 恒定足额结算，不参与原版无敌帧
        float healthAfter = Math.max(0.0F, healthBefore - (float) amount);

        // 第一层：setHealth
        victim.setHealth(healthAfter);
        // 档 3：setHealth 被吞时，先试模组自己的血量写入口
        if (!gatedGetter && mode.invokesHealthWriters() && stillAboveTarget(victim, healthAfter)) {
            healthWriters(victim);
            if (!healthWriters(victim).isEmpty()) {
                writeViaHealthWriter(victim, healthAfter);
            }
        }
        // 未到位时直写原版同步血量数据，绕过覆写setHealth
        if (!gatedGetter && stillAboveTarget(victim, healthAfter)) {
            writeVanillaHealthData(victim, healthAfter);
        }
        // 第二层起（档 2/3）：类字段探针 + 深度对象图遍历
        if (mode.probesHealthBacking()) {
            // 门控实体直接落到无门控读写口那一层
            if (!gatedGetter) {
                forceSetHealthFields(victim, healthBefore, healthAfter);
                // 第三层（档 3）：引用类静态表
                if (stillAboveTarget(victim, healthAfter) && mode.searchesReferencedTables()) {
                    writeReferencedStaticTables(victim, healthAfter);
                }
                // 第四层（档 2/3）：深度对象图遍历
                if (stillAboveTarget(victim, healthAfter)) {
                    deepWriteHealth(victim, healthBefore, healthAfter);
                }
                // 第五层（档 3）：加密字符串血量
                if (stillAboveTarget(victim, healthAfter) && mode.rewritesEncryptedHealth()) {
                    writeEncryptedHealth(victim, healthBefore - healthAfter);
                }
                // 第五层第二部分（档 3）：数值↔字符串编解码血量字段
                if (stillAboveTarget(victim, healthAfter) && mode.rewritesEncryptedHealth()) {
                    writeViaEncodedHealth(victim, healthAfter);
                }
                // 第六层（档 3）：模组自己的血量读写口
                if (stillAboveTarget(victim, healthAfter) && mode.invokesHealthWriters()) {
                    writeViaHealthWriter(victim, healthAfter);
                }
                // 第七层（档 3）：影子血量单例
                if (stillAboveTarget(victim, healthAfter) && mode.invokesHealthWriters()) {
                    writeViaShadowStore(victim, healthAfter);
                }
                // 第七层第二部分（档 3）：校验(MAC)保护的静态权威记录
                if (stillAboveTarget(victim, healthAfter) && mode.invokesHealthWriters()) {
                    writeViaMacAuthority(victim, healthAfter, source);
                }
                // 第八层（档 3）：模组权威值直写（致死）与权威累计值按量写入（非致死）
                if (mode.invokesHealthWriters()) {
                    if (healthAfter <= 0.0F) {
                        writeViaModAuthority(victim);
                    } else if (shouldWriteModAuthority(victim, healthAfter)) {
                        writeViaModAuthorityDelta(victim, healthAfter);
                    }
                }
                // 第九层（档 3）：密文串血量（同步项 + 算子写口）
                if (stillAboveTarget(victim, healthAfter) && mode.invokesHealthWriters()) {
                    writeViaHealthOperator(victim, healthAfter);
                }
            }
            // 第十层（档 3）：持有对象读写口
            if (mode.invokesHealthWriters() && needsHolderWrite(gatedGetter, victim, healthAfter)) {
                writeViaHolderWriter(victim, healthAfter);
            }
            // 第十一层（档 3）：隐藏状态帧直写
            if (mode.invokesHealthWriters() && !gatedGetter && stillAboveTarget(victim, healthAfter)) {
                writeViaStateFrame(victim, healthAfter);
            }
        }

        // 诊断：整条级联走完仍未写到位
        if (MAConfig.debugProbes && !gatedGetter && mode.probesHealthBacking()
                && stillAboveTarget(victim, healthAfter)) {
            probeLog("级联走完仍未写到位：{}（{}）目标={} 当前={} 档位={}",
                    victim.getName().getString(), victim.getClass().getName(), healthAfter, victim.getHealth(), mode);
        }

        // 档 1/2 归零时先让原版的死亡保护机制介入
        if (!gatedGetter && victim.getHealth() <= 0.0F) {
            DamageSource deathSource = source != null ? source : victim.damageSources().generic();
            if (!mode.ignoresDeathProtection()
                    && ((LivingEntityAccessor) victim).invokeCheckTotemDeathProtection(deathSource)) {
                return true;
            }
            if (callDie) {
                // 档 3：先把所有已验证的绝对血量写入口写 0，再走 die() 与击杀入口兜底
                if (mode.invokesHealthWriters()) {
                    zeroAuthorityHealth(victim);
                }
                if (mode.ignoresDeathProtection()) {
                    // 登记本次死亡，使其不被死亡事件取消
                    PENDING_FORCED_DEATH.add(victim);
                    try {
                        victim.die(deathSource);
                    } finally {
                        PENDING_FORCED_DEATH.remove(victim);
                    }
                } else {
                    victim.die(deathSource);
                }
                if (mode.invokesHealthWriters()) {
                    forceModKillIfNeeded(victim, deathSource);
                }
            }
        }
        // 只有把血量写到位才算结算成功
        return !stillAboveTarget(victim, healthAfter);
    }

    /** 上一层写入后血量仍高于目标值——只有此时才值得继续下一层兜底 */
    private static boolean stillAboveTarget(LivingEntity victim, float healthAfter) {
        float now = victim.getHealth();
        return now > healthAfter && !matches(now, healthAfter);
    }

    /** 数值一致性判定：小值用固定容差，大值按 1e-6 相对缩放 */
    private static boolean matches(float actual, float expected) {
        return Math.abs(actual - expected) <= Math.max(PROBE_EPSILON, Math.abs(expected) * 1.0E-6F);
    }

    /** 档 3：让本模组真伤/斩杀造成的死亡不被取消（仅限登记过的目标） */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (PENDING_FORCED_DEATH.remove(event.getEntity())) {
            event.setCanceled(false);
        }
    }

    /** 原版同步血量数据访问器，只解析一次 */
    private static EntityDataAccessor<Float> vanillaHealthAccessor;
    private static boolean vanillaHealthAccessorResolved;

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> getVanillaHealthAccessor() {
        if (!vanillaHealthAccessorResolved) {
            vanillaHealthAccessorResolved = true;
            for (String name : new String[]{"DATA_HEALTH_ID", "f_20961_"}) {
                try {
                    Field field = LivingEntity.class.getDeclaredField(name);
                    field.setAccessible(true);
                    vanillaHealthAccessor = (EntityDataAccessor<Float>) field.get(null);
                    break;
                } catch (Throwable ignored) {
                    // 换下一个名字
                }
            }
        }
        return vanillaHealthAccessor;
    }

    /** 直接写原版同步血量数据，绕过 setHealth */
    private static void writeVanillaHealthData(LivingEntity victim, float healthAfter) {
        EntityDataAccessor<Float> accessor = getVanillaHealthAccessor();
        if (accessor == null) return;
        try {
            victim.getEntityData().set(accessor, Math.max(0.0F, Math.min(healthAfter, victim.getMaxHealth())));
        } catch (Throwable ignored) {
            // 忽略写入失败
        }
    }

    /** 读原版同步血量数据（{@code DATA_HEALTH_ID}） */
    private static float readVanillaHealthData(LivingEntity victim) {
        EntityDataAccessor<Float> accessor = getVanillaHealthAccessor();
        if (accessor == null) return Float.NaN;
        try {
            return victim.getEntityData().get(accessor);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    // ===== 深度探针：有界对象图遍历 + 行为验证，找出承载血量的位点 =====

    /** 一个“可读写血量”的位点 */
    private interface HealthSlot {
        double read();

        void write(double value);
    }

    private record DeepHealthSlots(List<HealthSlot> direct, List<HealthSlot> damageTaken) {
    }

    /** 深度探针结果缓存（同 tick 内复用，tick 结束清空） */
    private static final Map<LivingEntity, DeepHealthSlots> DEEP_HEALTH_CACHE = new ConcurrentHashMap<>();

    private static final int DEEP_MAX_DEPTH = 6;
    private static final int DEEP_MAX_NODES = 2000;
    private static final int DEEP_MAX_COLLECTION_SIZE = 64;
    private static final int DEEP_MAX_BYTE_ARRAY = 256;

    /** 深度探针写入血量：直接位点写目标值，累计伤害位点加上扣血量 */
    private static void deepWriteHealth(LivingEntity victim, float healthBefore, float healthAfter) {
        DeepHealthSlots slots = DEEP_HEALTH_CACHE.computeIfAbsent(
                victim, StrongTrueDamageHelper::deepFindHealthSlots);
        // 「总量 - 已消耗」型派生血量：只写消耗位点
        if (writeDerivedHealth(victim, slots.direct(), slots.damageTaken(), healthAfter)) return;
        writeHealthSlots(slots.direct(), slots.damageTaken(), healthAfter, healthBefore - healthAfter);
    }

    /** 「总量 − 已消耗」型派生血量：只写消耗位点，未达成则还原 */
    private static boolean writeDerivedHealth(LivingEntity victim, List<HealthSlot> direct,
                                              List<HealthSlot> taken, float healthAfter) {
        if (direct.isEmpty() || taken.isEmpty()) return false;
        double total = derivedTotal(victim, direct, taken);
        if (Double.isNaN(total)) return false;
        double[] backups = new double[taken.size()];
        for (int i = 0; i < taken.size(); i++) {
            try {
                backups[i] = taken.get(i).read();
            } catch (Throwable ignored) {
                backups[i] = Double.NaN;
            }
        }
        // 绝对值写入，夹在 [0, 总量] 内
        double consumed = Math.min(Math.max(total - healthAfter, 0.0), total);
        for (HealthSlot slot : taken) {
            try {
                slot.write(consumed);
            } catch (Throwable ignored) {
                // 单个位点写入失败不影响其它位点
            }
        }
        if (!stillAboveTarget(victim, healthAfter)) return true;
        for (int i = 0; i < taken.size(); i++) {
            if (Double.isNaN(backups[i])) continue;
            try {
                taken.get(i).write(backups[i]);
            } catch (Throwable ignored) {
                // 还原失败则保持写入值
            }
        }
        return false;
    }

    /** 找出「总量 − 已消耗」结构里的总量读数；不适用时返回 {@code NaN} */
    private static double derivedTotal(LivingEntity victim, List<HealthSlot> direct, List<HealthSlot> taken) {
        double health;
        try {
            health = victim.getHealth();
        } catch (Throwable ignored) {
            return Double.NaN;
        }
        double tolerance = Math.max(1.0, Math.abs(health) * 0.01);
        for (HealthSlot directSlot : direct) {
            double total;
            try {
                total = directSlot.read();
            } catch (Throwable ignored) {
                continue;
            }
            if (Double.isNaN(total)) continue;
            for (HealthSlot takenSlot : taken) {
                double consumed;
                try {
                    consumed = takenSlot.read();
                } catch (Throwable ignored) {
                    continue;
                }
                if (Double.isNaN(consumed)) continue;
                if (Math.abs(total - consumed - health) <= tolerance) return total;
            }
        }
        return Double.NaN;
    }

    /** 按位点约定写回：直接血量位点写目标值，累计伤害位点加上本次扣血量 */
    private static void writeHealthSlots(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                         float healthAfter, float damage) {
        for (HealthSlot slot : direct) {
            try {
                slot.write(healthAfter);
            } catch (Throwable ignored) {
                // 单个位点写入失败不影响其它位点
            }
        }
        for (HealthSlot slot : damageTaken) {
            // 读不出原值（NaN）就不写
            double original;
            try {
                original = slot.read();
            } catch (Throwable ignored) {
                continue;
            }
            if (Double.isNaN(original)) continue;
            try {
                slot.write(original + damage);
            } catch (Throwable ignored) {
                // 单个位点写入失败不影响其它位点
            }
        }
    }

    /** 从实体出发做有界对象图遍历 + 行为验证，找出承载血量的位点 */
    private static DeepHealthSlots deepFindHealthSlots(LivingEntity victim) {
        List<HealthSlot> direct = new ArrayList<>();
        List<HealthSlot> damageTaken = new ArrayList<>();
        // 实体自身数值字段已由类字段探针识别，这里复用结论
        HealthBackingFields backing = identifyHealthBackingFields(victim);
        for (Field field : backing.directHealth()) {
            direct.add(new NumberFieldSlot(victim, field));
        }
        for (Field field : backing.damageTaken()) {
            damageTaken.add(new NumberFieldSlot(victim, field));
        }
        float baseHealth = victim.getHealth();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{victim, 0});
        visited.add(victim);

        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < DEEP_MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            int depth = (Integer) entry[1];
            if (node == null) continue;
            Class<?> clazz = node.getClass();

            if (node instanceof Map<?, ?> map) {
                // 以当前实体（或其 UUID）为键的表：逐键做行为验证
                collectMapEntitySlots(direct, damageTaken, map, victim, baseHealth);
                if (depth >= DEEP_MAX_DEPTH || map.size() > DEEP_MAX_COLLECTION_SIZE) continue;
                for (Object value : map.values()) {
                    if (value != null && visited.add(value)) queue.add(new Object[]{value, depth + 1});
                }
                continue;
            }
            if (node instanceof Iterable<?> iterable) {
                if (depth >= DEEP_MAX_DEPTH) continue;
                int count = 0;
                for (Object value : iterable) {
                    if (count++ >= DEEP_MAX_COLLECTION_SIZE) break;
                    if (value != null && visited.add(value)) queue.add(new Object[]{value, depth + 1});
                }
                continue;
            }
            if (clazz.isArray()) {
                Class<?> component = clazz.getComponentType();
                if (component == float.class) {
                    float[] array = (float[]) node;
                    for (int i = 0; i < array.length; i++) {
                        collectHealthSlot(direct, damageTaken, new FloatArraySlot(array, i), victim, baseHealth);
                    }
                } else if (component == double.class) {
                    double[] array = (double[]) node;
                    for (int i = 0; i < array.length; i++) {
                        collectHealthSlot(direct, damageTaken, new DoubleArraySlot(array, i), victim, baseHealth);
                    }
                } else if (component == byte.class) {
                    byte[] array = (byte[]) node;
                    if (array.length % 4 == 0 && array.length <= DEEP_MAX_BYTE_ARRAY) {
                        for (int i = 0; i + 4 <= array.length; i += 4) {
                            collectHealthSlot(direct, damageTaken, new ByteArrayFloatSlot(array, i, true), victim, baseHealth);
                            collectHealthSlot(direct, damageTaken, new ByteArrayFloatSlot(array, i, false), victim, baseHealth);
                        }
                    }
                } else if (!component.isPrimitive() && depth < DEEP_MAX_DEPTH) {
                    for (Object element : (Object[]) node) {
                        if (element != null && visited.add(element)) queue.add(new Object[]{element, depth + 1});
                    }
                }
                continue;
            }

            if (depth >= DEEP_MAX_DEPTH || skipDeepClass(clazz)) continue;
            for (Field field : deepCandidateFields(clazz)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    // 只处理模组类声明的静态位点
                    if (isModClass(field.getDeclaringClass())) {
                        collectStaticHealthSlot(direct, damageTaken, queue, field, victim, baseHealth, depth, visited);
                    }
                    continue;
                }
                Class<?> type = field.getType();
                try {
                    if (type == float.class || type == double.class) {
                        // 自身数值字段已识别，不重复探测
                        if (node != victim) {
                            collectHealthSlot(direct, damageTaken, new NumberFieldSlot(node, field), victim, baseHealth);
                        }
                    } else if (!type.isPrimitive()) {
                        Object value = field.get(node);
                        if (value instanceof Float || value instanceof Double) {
                            collectHealthSlot(direct, damageTaken, new BoxedNumberFieldSlot(node, field, value instanceof Float), victim, baseHealth);
                        } else if (value != null && visited.add(value)) {
                            String fieldName = field.getName().toLowerCase(Locale.ROOT);
                            // capability / 数据 / 血量相关的对象优先遍历
                            if (fieldName.contains("capab") || fieldName.contains("data")
                                    || fieldName.contains("health") || fieldName.contains("sync")) {
                                queue.addFirst(new Object[]{value, depth + 1});
                            } else {
                                queue.add(new Object[]{value, depth + 1});
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 字段不可访问则跳过
                }
            }
        }
        return new DeepHealthSlots(direct, damageTaken);
    }

    /** 静态作用域位点收集：静态镜像字段与“以实体为键”的表条目 */
    private static void collectStaticHealthSlot(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                                Deque<Object[]> queue, Field field, LivingEntity victim,
                                                float baseHealth, int depth, Set<Object> visited) {
        Class<?> type = field.getType();
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
            return;
        }
        try {
            if (type == float.class || type == double.class) {
                collectHealthSlot(direct, damageTaken, new NumberFieldSlot(null, field), victim, baseHealth);
                return;
            }
            if (Map.class.isAssignableFrom(type)) {
                if (!(field.get(null) instanceof Map<?, ?> map)) return;
                collectMapEntitySlots(direct, damageTaken, map, victim, baseHealth);
                if (depth < DEEP_MAX_DEPTH && map.size() <= DEEP_MAX_COLLECTION_SIZE && visited.add(map)) {
                    queue.add(new Object[]{map, depth + 1});
                }
                return;
            }
            if (Iterable.class.isAssignableFrom(type) && depth < DEEP_MAX_DEPTH) {
                Object value = field.get(null);
                if (value != null && visited.add(value)) queue.add(new Object[]{value, depth + 1});
            }
        } catch (Throwable ignored) {
            // 静态字段不可读则跳过
        }
    }

    /** 表里“以该实体（或其 UUID）为键”的位点：逐键做行为验证，命中才保留 */
    private static void collectMapEntitySlots(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                              Map<?, ?> map, LivingEntity victim, float baseHealth) {
        for (Object key : new Object[]{victim, victim.getUUID()}) {
            // 与静态表层共用 TableEntrySlot：优先走 Map.Entry.setValue 改写
            HealthSlot slot = tableEntrySlot(map, key);
            if (slot == null) continue;
            collectHealthSlot(direct, damageTaken, slot, victim, baseHealth);
        }
    }

    /** 是否为模组自定义类（排除 JDK / 原版 / Forge / 常见依赖） */
    private static boolean isModClass(Class<?> clazz) {
        return isModClassName(clazz.getName());
    }

    /** 同上，按类名判断（供尚未加载的引用类使用） */
    private static boolean isModClassName(String name) {
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                || name.startsWith("com.mojang.") || name.startsWith("org.lwjgl.")
                || name.startsWith("it.unimi.") || name.startsWith("com.google.")
                || name.startsWith("org.slf4j") || name.startsWith("org.apache"));
    }

    /** 原版 / Forge 类：字段级探针一律跳过 */
    private static boolean isVanillaClass(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.");
    }

    /** 行为验证：扰动该位点，只有能让 getHealth() 变化的才算命中 */
    private static void collectHealthSlot(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                          HealthSlot slot, LivingEntity victim, float baseHealth) {
        int direction = probeHealthSlot(victim, slot, baseHealth);
        if (direction == PROBE_DIRECT) {
            direct.add(slot);
        } else if (direction == PROBE_TAKEN) {
            damageTaken.add(slot);
        }
    }

    /** 行为验证：扰动位点观察 {@code getHealth()} 走向后还原，无变化再反向补探 */
    private static int probeHealthSlot(LivingEntity victim, HealthSlot slot, float baseHealth) {
        double original;
        try {
            original = slot.read();
        } catch (Throwable ignored) {
            return PROBE_MISS;
        }
        if (Double.isNaN(original)) return PROBE_MISS;
        double delta = Math.max(1.0, Math.abs(original) * 0.01);
        int direction = perturbAndClassify(victim, slot, original, delta, baseHealth);
        if (direction != PROBE_MISS) return direction;
        int reverse = perturbAndClassify(victim, slot, original, -delta, baseHealth);
        return reverse == PROBE_MISS ? PROBE_MISS : -reverse;
    }

    /** 写入 {@code original + delta} 并观察 {@code getHealth()} 走向，最后还原位点 */
    private static int perturbAndClassify(LivingEntity victim, HealthSlot slot, double original, double delta,
                                          float baseHealth) {
        double perturbed = original + delta;
        if (!Double.isFinite(perturbed)) return PROBE_MISS;
        // 扰动值夹在 (0, 最大生命]，且只在不会反转扰动方向时上钳
        double max = maxHealthOf(victim);
        if (delta > 0.0 && Double.isFinite(max) && max > original && perturbed > max) {
            perturbed = max;
        }
        if (perturbed <= 0.0) {
            probeLog("扰动跳过（会落到 <= 0）：位点原值={} delta={}", original, delta);
            return PROBE_MISS;
        }
        try {
            slot.write(perturbed);
        } catch (Throwable ignored) {
            return PROBE_MISS;
        }
        try {
            float probed = victim.getHealth();
            if (probed > baseHealth + PROBE_EPSILON) return PROBE_DIRECT;
            if (probed < baseHealth - PROBE_EPSILON) return PROBE_TAKEN;
            return PROBE_MISS;
        } finally {
            try {
                slot.write(original);
            } catch (Throwable ignored) {
                // 还原失败则保持扰动值
            }
        }
    }

    /** 跳过 JDK / 大型世界对象 */
    private static boolean skipDeepClass(Class<?> clazz) {
        if (clazz == Class.class || clazz == String.class || clazz == ClassLoader.class || clazz == Thread.class) return true;
        String name = clazz.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.google.") || name.startsWith("it.unimi.")
                || name.startsWith("org.slf4j") || name.startsWith("org.apache") || name.startsWith("com.mojang.")
                || name.startsWith("net.minecraft.world.level.") || name.startsWith("net.minecraft.server.")
                || name.startsWith("net.minecraft.client.") || name.startsWith("net.minecraft.world.entity.ai.");
    }

    /** 深度遍历的候选字段（含父类链、非合成，实例字段已 setAccessible），按类缓存 */
    private static List<Field> deepCandidateFields(Class<?> clazz) {
        return DEEP_FIELD_CACHE.computeIfAbsent(clazz, type -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current : hierarchy(type)) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isSynthetic()) continue;
                    if (!Modifier.isStatic(field.getModifiers())) {
                        try {
                            field.setAccessible(true);
                        } catch (Throwable ignored) {
                            continue;
                        }
                    }
                    fields.add(field);
                }
            }
            return fields;
        });
    }

    /** 深度遍历候选字段缓存（字段本身不持有实体引用，可跨 tick 复用） */
    private static final Map<Class<?>, List<Field>> DEEP_FIELD_CACHE = new ConcurrentHashMap<>();

    // ===== 成员访问：VarHandle 优先、反射兜底 =====
    private static final Map<Field, VarHandle> FIELD_VAR_HANDLES = new ConcurrentHashMap<>();
    private static final Set<Field> NO_FIELD_VAR_HANDLE = ConcurrentHashMap.newKeySet();

    private static VarHandle varHandleOf(Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers()) || NO_FIELD_VAR_HANDLE.contains(field)) {
            return null;
        }
        VarHandle cached = FIELD_VAR_HANDLES.get(field);
        if (cached != null) return cached;
        VarHandle handle;
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            handle = lookup.unreflectVarHandle(field);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            // 句柄不可用则记入负缓存
            NO_FIELD_VAR_HANDLE.add(field);
            return null;
        }
        FIELD_VAR_HANDLES.put(field, handle);
        return handle;
    }

    /** OOM / StackOverflow 等致命错误不应被安静失败吞掉 */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError error) throw error;
    }

    /** float/double 实例字段的统一读写位点 */
    @SuppressWarnings("CatchMayIgnoreException")
    private record NumberFieldSlot(Object owner, Field field) implements HealthSlot {
        @Override
        public double read() {
            try {
                VarHandle handle = varHandleOf(field);
                if (handle != null) {
                    Object value = handle.get(owner);
                    return value instanceof Number number ? number.doubleValue() : Double.NaN;
                }
                return field.getType() == float.class ? field.getFloat(owner) : field.getDouble(owner);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                return Double.NaN;
            }
        }

        @Override
        public void write(double value) {
            try {
                VarHandle handle = varHandleOf(field);
                if (handle != null) {
                    if (field.getType() == float.class) {
                        handle.set(owner, (float) value);
                    } else {
                        handle.set(owner, value);
                    }
                    return;
                }
                if (field.getType() == float.class) {
                    field.setFloat(owner, (float) value);
                } else {
                    field.setDouble(owner, value);
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 写入失败即放弃
            }
        }
    }

    /** 装箱数值字段（如 SynchedEntityData 里存的 Float） */
    private record BoxedNumberFieldSlot(Object owner, Field field, boolean asFloat) implements HealthSlot {
        @Override
        public double read() {
            try {
                VarHandle handle = varHandleOf(field);
                Object value = handle != null ? handle.get(owner) : field.get(owner);
                return value instanceof Number number ? number.doubleValue() : Double.NaN;
            } catch (Throwable t) {
                rethrowIfFatal(t);
                return Double.NaN;
            }
        }

        @Override
        @SuppressWarnings("CatchMayIgnoreException")
        public void write(double value) {
            try {
                Object boxed = asFloat ? (Object) (float) value : (Object) value;
                VarHandle handle = varHandleOf(field);
                if (handle != null) {
                    handle.set(owner, boxed);
                } else {
                    field.set(owner, boxed);
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 写入失败即放弃
            }
        }
    }

    private record FloatArraySlot(float[] array, int index) implements HealthSlot {
        @Override
        public double read() {
            return array[index];
        }

        @Override
        public void write(double value) {
            array[index] = (float) value;
        }
    }

    private record DoubleArraySlot(double[] array, int index) implements HealthSlot {
        @Override
        public double read() {
            return array[index];
        }

        @Override
        public void write(double value) {
            array[index] = value;
        }
    }

    /** byte[4] 按 IEEE 754 位解释成 float */
    private record ByteArrayFloatSlot(byte[] array, int offset, boolean bigEndian) implements HealthSlot {
        @Override
        public double read() {
            int bits = bigEndian
                    ? ((array[offset] & 0xFF) << 24) | ((array[offset + 1] & 0xFF) << 16) | ((array[offset + 2] & 0xFF) << 8) | (array[offset + 3] & 0xFF)
                    : ((array[offset + 3] & 0xFF) << 24) | ((array[offset + 2] & 0xFF) << 16) | ((array[offset + 1] & 0xFF) << 8) | (array[offset] & 0xFF);
            return Float.intBitsToFloat(bits);
        }

        @Override
        public void write(double value) {
            int bits = Float.floatToIntBits((float) value);
            if (bigEndian) {
                array[offset] = (byte) (bits >>> 24);
                array[offset + 1] = (byte) (bits >>> 16);
                array[offset + 2] = (byte) (bits >>> 8);
                array[offset + 3] = (byte) bits;
            } else {
                array[offset] = (byte) bits;
                array[offset + 1] = (byte) (bits >>> 8);
                array[offset + 2] = (byte) (bits >>> 16);
                array[offset + 3] = (byte) (bits >>> 24);
            }
        }
    }

    // ===== 类字段探针：按类缓存承载血量的字段（直接血量 / 累计伤害两种） =====

    /** 每个类只探测一次：血量的承载字段（直接血量 / 累计伤害 / 同步数据） */
    private static final Map<Class<?>, HealthBackingFields> HEALTH_FIELD_CACHE = new ConcurrentHashMap<>();

    /** 探针判定阈值 */
    private static final float PROBE_EPSILON = 1.0E-3F;

    /** 行为探针诊断日志（默认关闭，见 {@code MAConfig.debugProbes}） */
    private static void probeLog(String format, Object... args) {
        if (MAConfig.debugProbes) {
            // 格式串整体作为日志 message，探针实参放在可变参位
            String message = "[真伤探针] " + format;
            MoreAttributesMod.LOGGER.info(message, args);
        }
    }

    /** 行为探针结果：命中“当前血量”位点（位点变大则 getHealth() 变大） */
    private static final int PROBE_DIRECT = 1;
    /** 行为探针结果：命中“累计伤害”位点（位点变大则 getHealth() 变小） */
    private static final int PROBE_TAKEN = -1;
    /** 行为探针结果：该位点不影响 getHealth() */
    private static final int PROBE_MISS = 0;

    private record HealthBackingFields(List<Field> directHealth, List<Field> damageTaken,
                                       List<EntityDataAccessor<?>> directData,
                                       List<EntityDataAccessor<?>> takenData) {
    }

    /** 收集实体类（含父类）中“非原版/非 Forge、非 static/final 的 float/double”字段作为候选 */
    private static List<Field> collectCandidateFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            // 只扫模组自定义类
            if (isVanillaClass(current)) continue;
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic()) continue;
                Class<?> fieldType = field.getType();
                if (fieldType != float.class && fieldType != double.class) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                    // 字段不可访问则跳过
                }
            }
        }
        return fields;
    }

    /** 用行为探针识别承载血量的字段，按类缓存 */
    private static HealthBackingFields identifyHealthBackingFields(LivingEntity victim) {
        return HEALTH_FIELD_CACHE.computeIfAbsent(victim.getClass(), type -> {
            List<Field> direct = new ArrayList<>();
            List<Field> taken = new ArrayList<>();
            List<EntityDataAccessor<?>> directData = new ArrayList<>();
            List<EntityDataAccessor<?>> takenData = new ArrayList<>();
            List<Field> candidates = collectCandidateFields(type);
            float healthBeforeProbe = victim.getHealth();

            for (Field field : candidates) {
                int direction = probeHealthSlot(victim, new NumberFieldSlot(victim, field), healthBeforeProbe);
                if (direction == PROBE_DIRECT) {
                    direct.add(field);
                } else if (direction == PROBE_TAKEN) {
                    taken.add(field);
                }
            }

            // 血量也存在同步数据里的处理
            try {
                SynchedEntityData entityData = victim.getEntityData();
                for (EntityDataAccessor<?> accessor : collectDataAccessors(type)) {
                    HealthSlot slot = dataAccessorSlot(entityData, accessor);
                    if (slot == null) continue;
                    int direction = probeHealthSlot(victim, slot, healthBeforeProbe);
                    if (direction == PROBE_DIRECT) {
                        directData.add(accessor);
                    } else if (direction == PROBE_TAKEN) {
                        takenData.add(accessor);
                    }
                }
            } catch (Throwable ignored) {
            }

            return new HealthBackingFields(direct, taken, directData, takenData);
        });
    }

    /** 同步数据血量位点：读写都走 {@code SynchedEntityData}，只接受 Float/Double/Integer/Short/Byte */
    private record SyncedNumberSlot(SynchedEntityData entityData, EntityDataAccessor<Object> accessor)
            implements HealthSlot {
        @Override
        public double read() {
            try {
                Object current = entityData.get(accessor);
                return current instanceof Number number ? number.doubleValue() : Double.NaN;
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }

        @Override
        public void write(double value) {
            try {
                Object current = entityData.get(accessor);
                entityData.set(accessor, boxLike(current, value));
            } catch (Throwable ignored) {
                // 写入失败即放弃
            }
        }
    }

    /** 按位点当前的装箱类型把数值装箱 */
    private static Object boxLike(Object current, double value) {
        return boxNumber(current == null ? float.class : current.getClass(), value);
    }

    /** 把同步数据 accessor 包装成可读写位点，只接受非 Long 的数值项 */
    private static SyncedNumberSlot dataAccessorSlot(SynchedEntityData entityData, EntityDataAccessor<?> accessor) {
        try {
            Object current = entityData.get(accessor);
            if (!(current instanceof Number) || current instanceof Long) return null;
        } catch (Throwable ignored) {
            // 该 accessor 未在此实体注册等情况
            return null;
        }
        return new SyncedNumberSlot(entityData, rawAccessor(accessor));
    }

    /** 收集实体类（含父类）中“非原版/非 Forge 类声明的静态 EntityDataAccessor”，作为同步数据候选 */
    private static List<EntityDataAccessor<?>> collectDataAccessors(Class<?> clazz) {
        List<EntityDataAccessor<?>> accessors = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (isVanillaClass(current)) continue;
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                if (readStaticFieldAny(field) instanceof EntityDataAccessor<?> accessor) {
                    accessors.add(accessor);
                }
            }
        }
        return accessors;
    }

    /** 按约定改写血量字段：直接字段写目标值，累计伤害字段加上扣血量 */
    private static void forceSetHealthFields(LivingEntity victim, float healthBefore, float healthAfter) {
        HealthBackingFields backing = identifyHealthBackingFields(victim);
        float damage = healthBefore - healthAfter;
        List<HealthSlot> direct = new ArrayList<>(backing.directHealth().size() + backing.directData().size());
        List<HealthSlot> taken = new ArrayList<>(backing.damageTaken().size() + backing.takenData().size());
        for (Field field : backing.directHealth()) {
            direct.add(new NumberFieldSlot(victim, field));
        }
        for (Field field : backing.damageTaken()) {
            taken.add(new NumberFieldSlot(victim, field));
        }
        // 同步数据里的血量读写都走 SynchedEntityData
        try {
            SynchedEntityData entityData = victim.getEntityData();
            for (EntityDataAccessor<?> accessor : backing.directData()) {
                direct.add(new SyncedNumberSlot(entityData, rawAccessor(accessor)));
            }
            for (EntityDataAccessor<?> accessor : backing.takenData()) {
                SyncedNumberSlot slot = new SyncedNumberSlot(entityData, rawAccessor(accessor));
                // 计数器型血量：档 3 优先用模组自己的“设定计数”入口写入
                CounterWriter writer = MAConfig.trueDamageMode.invokesHealthWriters()
                        ? counterWriter(victim, slot) : null;
                taken.add(writer == null ? slot : new CounterWriterSlot(slot, writer, victim));
            }
        } catch (Throwable ignored) {
            // 取同步数据失败时只处理字段位点
        }
        // 「总量 - 已消耗」型派生血量：只写消耗位点
        if (writeDerivedHealth(victim, direct, taken, healthAfter)) return;
        writeHealthSlots(direct, taken, healthAfter, damage);
    }

    // ===== 计数器型血量：getHealth() = 总量 − 计数 =====

    private static final CounterWriter NO_COUNTER_WRITER = new CounterWriter(null, 0, 0, null);
    private static final Map<Class<?>, CounterWriter> COUNTER_WRITER_CACHE = new ConcurrentHashMap<>();

    private record CounterWriter(Method method, int entityIndex, int numberIndex, Class<?> numberType) {
    }

    /** 计数器位点写入口：优先走“设定计数”入口，保持私有字段与同步数据一致 */
    private record CounterWriterSlot(HealthSlot backing, CounterWriter writer, LivingEntity victim)
            implements HealthSlot {
        @Override
        public double read() {
            return backing.read();
        }

        @Override
        public void write(double value) {
            Object[] args = new Object[2];
            args[writer.entityIndex()] = victim;
            args[writer.numberIndex()] = boxNumber(writer.numberType(), value);
            try {
                writer.method().invoke(null, args);
            } catch (Throwable ignored) {
                // 写入失败即放弃
            }
        }
    }

    /** 按目标类型装箱：基本类型与包装类型都接受，未列出的类型按 double 处理 */
    private static Object boxNumber(Class<?> type, double value) {
        if (type == float.class || type == Float.class) return (float) value;
        if (type == int.class || type == Integer.class) return (int) value;
        if (type == short.class || type == Short.class) return (short) value;
        if (type == byte.class || type == Byte.class) return (byte) value;
        if (type == long.class || type == Long.class) return (long) value;
        return value;
    }

    /** 找“绝对设定计数”的静态入口，按行为验证确认写入与还原都成立 */
    private static CounterWriter counterWriter(LivingEntity victim, HealthSlot counter) {
        Class<?> type = victim.getClass();
        CounterWriter cached = COUNTER_WRITER_CACHE.get(type);
        if (cached != null) return cached == NO_COUNTER_WRITER ? null : cached;
        CounterWriter found = null;
        double current;
        try {
            current = counter.read();
        } catch (Throwable ignored) {
            current = Double.NaN;
        }
        if (Double.isFinite(current)) {
            double healthBefore = healthOf(victim);
            double delta = Math.max(1.0, Math.abs(current) * 0.01);
            List<Class<?>> owners = new ArrayList<>();
            for (Class<?> clazz : hierarchy(type)) {
                if (!isVanillaClass(clazz)) owners.add(clazz);
            }
            for (Class<?> clazz : withNestedClasses(owners, REFERENCE_MAX_CLASSES)) {
                if (clazz == null || isVanillaClass(clazz)) continue;
                found = findCounterWriter(clazz, victim, current, healthBefore, delta);
                if (found != null) break;
            }
        }
        COUNTER_WRITER_CACHE.put(type, found == null ? NO_COUNTER_WRITER : found);
        return found;
    }

    private static CounterWriter findCounterWriter(Class<?> clazz, LivingEntity victim,
                                                   double current, double healthBefore, double delta) {
        for (Method method : declaredStaticMethods(clazz)) {
            if (method.getParameterCount() != 2) continue;
            Class<?> returns = method.getReturnType();
            if (returns != void.class && returns != boolean.class) continue;
            Class<?>[] params = method.getParameterTypes();
            int entityIndex = -1;
            int numberIndex = -1;
            for (int i = 0; i < 2; i++) {
                Class<?> param = params[i];
                if (param == int.class || param == short.class || param == byte.class
                        || param == float.class || param == double.class) {
                    if (numberIndex >= 0) {
                        numberIndex = -1;
                        break;
                    }
                    numberIndex = i;
                } else if (param.isInstance(victim)) {
                    if (entityIndex >= 0) {
                        entityIndex = -1;
                        break;
                    }
                    entityIndex = i;
                } else {
                    entityIndex = -1;
                    break;
                }
            }
            if (entityIndex < 0 || numberIndex < 0) continue;
            Class<?> numberType = params[numberIndex];
            Object boxed = boxNumber(numberType, current + delta);
            double written = ((Number) boxed).doubleValue();
            Object[] args = new Object[2];
            args[entityIndex] = victim;
            args[numberIndex] = boxed;
            if (!invokeQuietly(method, args)) continue;
            boolean hit = matches(healthOf(victim), (float) (healthBefore - (written - current)));
            // 无论是否命中都按原计数还原
            args[numberIndex] = boxNumber(numberType, current);
            invokeQuietly(method, args);
            if (!hit) continue;
            // 还原不成功则弃用
            if (!matches(healthOf(victim), (float) healthBefore)) continue;
            return new CounterWriter(method, entityIndex, numberIndex, numberType);
        }
        return null;
    }

    // ===== 加密血量探针（档 3）：用可往返加解密方法改写密文 =====

    /** 派生血量信号方法：实体继承链上的无参 float/double getter（按类缓存） */
    private static final Map<Class<?>, List<Method>> HEALTH_SIGNAL_CACHE = new ConcurrentHashMap<>();

    /** 信号方法采样上限 */
    private static final int SIGNAL_MAX_METHODS = 96;

    /** 加/解密方法对（按类缓存） */
    private static final Map<Class<?>, CryptoPair> CRYPTO_PAIR_CACHE = new ConcurrentHashMap<>();

    /** 加密血量改写方案（按类缓存） */
    private static final Map<Class<?>, List<EncryptedPlan>> ENCRYPTED_PLAN_CACHE = new ConcurrentHashMap<>();

    /** 用于验证加解密互逆性的随机数字样本 */
    private static final String[] CRYPTO_PROBES = {"0", "7", "42", "1234", "9.75", "100.5"};

    /** 可选方法对上限 */
    private static final int CRYPTO_MAX_CANDIDATES = 128;

    private static final CryptoPair NO_CRYPTO = new CryptoPair(null, null);

    /** 加密血量方案：明文语义方向，{@code +1} = 已受伤量，{@code -1} = 剩余血量 */
    private record EncryptedPlan(Field field, EntityDataAccessor<?> accessor, int sign) {
    }

    /** 一个可读写的字符串位点（普通字段或同步数据） */
    private interface StringSlot {
        String read();

        void write(String value);
    }

    private record StringFieldSlot(Object owner, Field field) implements StringSlot {
        @Override
        public String read() {
            try {
                return (String) field.get(owner);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public void write(String value) {
            try {
                field.set(owner, value);
            } catch (Throwable ignored) {
                // 写入失败即放弃
            }
        }
    }

    private record SyncedStringSlot(SynchedEntityData entityData, EntityDataAccessor<Object> accessor)
            implements StringSlot {
        @Override
        public String read() {
            try {
                return entityData.get(accessor) instanceof String text ? text : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public void write(String value) {
            try {
                entityData.set(accessor, value);
            } catch (Throwable ignored) {
                // 写入失败即放弃
            }
        }
    }

    /** 字符串位点与其在类上的标识（供按类缓存改写方案） */
    private record StringCandidate(Field field, EntityDataAccessor<?> accessor, StringSlot slot) {
    }

    /** 一对互逆的字符串方法：{@code decrypt(encrypt(明文)) == 明文} */
    private record CryptoPair(Method encrypt, Method decrypt) {
        /** 任一侧缺失即为不可用 */
        boolean unusable() {
            return encrypt == null || decrypt == null;
        }

        String encryptValue(String plain) {
            if (encrypt == null) return null;
            try {
                return encrypt.invoke(null, plain) instanceof String text ? text : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        Double decryptValue(String raw) {
            if (decrypt == null || raw == null || raw.isEmpty()) return null;
            try {
                Object plain = decrypt.invoke(null, raw);
                return plain instanceof String text ? Double.parseDouble(text) : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 改写加密血量 */
    private static void writeEncryptedHealth(LivingEntity victim, float damage) {
        if (!(damage > 0.0F)) return;
        List<EncryptedPlan> plans = ENCRYPTED_PLAN_CACHE.get(victim.getClass());
        if (plans == null) {
            plans = discoverEncryptedPlans(victim);
            // 按类缓存（含空结果）
            ENCRYPTED_PLAN_CACHE.put(victim.getClass(), plans);
        }
        if (plans.isEmpty()) return;

        CryptoPair pair = cryptoPair(victim);
        if (pair.unusable()) return;

        for (EncryptedPlan plan : plans) {
            StringSlot slot = resolveSlot(victim, plan);
            if (slot == null) continue;
            Double stored = pair.decryptValue(slot.read());
            if (stored == null || !Double.isFinite(stored)) continue;
            double target = Math.max(0.0, stored + plan.sign() * (double) damage);
            String encrypted = pair.encryptValue(plainText(target));
            if (encrypted == null) continue;
            slot.write(encrypted);
            return;
        }
    }

    /** 首次遇到该类实体时识别“哪个字符串是血量、其明文是什么语义” */
    private static List<EncryptedPlan> discoverEncryptedPlans(LivingEntity victim) {
        CryptoPair pair = cryptoPair(victim);
        if (pair.unusable()) return List.of();
        List<Method> signals = healthSignalMethods(victim.getClass());
        if (signals.isEmpty()) return List.of();

        float maxHealth = victim.getMaxHealth();
        if (!(maxHealth > 0.0F)) return List.of();
        double limit = maxHealth * 1.5 + 1.0;

        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            Double stored = pair.decryptValue(raw);
            if (stored == null || !Double.isFinite(stored)) continue;
            if (stored < -limit || stored > limit) continue;
            Integer sign = confirmEncryptedSign(victim, candidate.slot(), pair, raw, stored, signals, limit);
            if (sign == null) continue;
            return List.of(new EncryptedPlan(candidate.field(), candidate.accessor(), sign));
        }
        return List.of();
    }

    /** 行为验证：把密文改写为「明文 + delta」，由 getter 的变化符号判定方向 */
    private static Integer confirmEncryptedSign(LivingEntity victim, StringSlot slot, CryptoPair pair,
                                                String raw, double stored, List<Method> signals, double limit) {
        double delta = Math.max(1.0, Math.abs(stored) * 0.01);
        String perturbed = pair.encryptValue(plainText(stored + delta));
        if (perturbed == null) return null;
        float maxBefore = victim.getMaxHealth();
        for (Method signal : signals) {
            Double before = invokeForNumber(signal, victim, NO_ARGS);
            if (before == null || before.isNaN() || before < -limit || before > limit) continue;
            try {
                slot.write(perturbed);
                if (!matches(victim.getMaxHealth(), maxBefore)) return null;
                Double after = invokeForNumber(signal, victim, NO_ARGS);
                if (after != null) {
                    double observed = after - before;
                    if (Math.abs(observed - delta) <= PROBE_EPSILON) return -1;
                    if (Math.abs(observed + delta) <= PROBE_EPSILON) return 1;
                }
            } finally {
                slot.write(raw);
            }
        }
        return null;
    }

    /** 实体继承链上的无参 float/double getter（按类缓存） */
    private static List<Method> healthSignalMethods(Class<?> type) {
        return HEALTH_SIGNAL_CACHE.computeIfAbsent(type, clazz -> {
            List<Method> methods = new ArrayList<>();
            for (Class<?> current : hierarchy(clazz)) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.isSynthetic() || method.getParameterCount() != 0) continue;
                    Class<?> returns = method.getReturnType();
                    if (returns != float.class && returns != double.class) continue;
                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    methods.add(method);
                    if (methods.size() >= SIGNAL_MAX_METHODS) return methods;
                }
            }
            return methods;
        });
    }

    /** 候选字符串位点：实体类的 String 实例字段 + 同步数据里当前值为 String 的 accessor */
    private static List<StringCandidate> encryptedStringCandidates(LivingEntity victim) {
        List<StringCandidate> candidates = new ArrayList<>();
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                candidates.add(new StringCandidate(field, null, new StringFieldSlot(victim, field)));
            }
        }
        SynchedEntityData entityData = victim.getEntityData();
        for (EntityDataAccessor<?> accessor : collectDataAccessors(victim.getClass())) {
            try {
                if (!(entityData.get(accessor) instanceof String)) continue;
            } catch (Throwable ignored) {
                continue;
            }
            candidates.add(new StringCandidate(null, accessor,
                    new SyncedStringSlot(entityData, rawAccessor(accessor))));
        }
        return candidates;
    }

    private static StringSlot resolveSlot(LivingEntity victim, EncryptedPlan plan) {
        if (plan.field() != null) return new StringFieldSlot(victim, plan.field());
        if (plan.accessor() != null) return new SyncedStringSlot(victim.getEntityData(), rawAccessor(plan.accessor()));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Object> rawAccessor(EntityDataAccessor<?> accessor) {
        return (EntityDataAccessor<Object>) accessor;
    }

    /** 明文格式化：避免科学计数法 */
    private static String plainText(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static CryptoPair cryptoPair(LivingEntity victim) {
        Class<?> type = victim.getClass();
        CryptoPair cached = CRYPTO_PAIR_CACHE.get(type);
        if (cached != null) return cached;
        CryptoPair found = findCryptoPair(victim);
        CRYPTO_PAIR_CACHE.put(type, found);
        return found;
    }

    /** 在实体字节码可达的模组类里找一对互逆的静态 {@code (String) -> String} 方法 */
    private static CryptoPair findCryptoPair(LivingEntity victim) {
        List<Method> candidates = stringStringMethods(victim.getClass());
        if (candidates.size() < 2) return NO_CRYPTO;
        String[] probes = cryptoProbes(victim);
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw == null || raw.isEmpty()) continue;
            for (Method decrypt : candidates) {
                if (implausibleHealth(victim, decryptAsNumber(decrypt, raw))) continue;
                for (Method encrypt : candidates) {
                    if (encrypt != decrypt && roundTripsOn(decrypt, encrypt, probes)) {
                        return new CryptoPair(encrypt, decrypt);
                    }
                }
            }
        }
        for (Method encrypt : candidates) {
            for (Method decrypt : candidates) {
                if (encrypt == decrypt) continue;
                // 两者可互相还原（对称算法）时无法区分方向，放弃
                if (roundTripsOn(decrypt, encrypt, probes) && !roundTripsOn(encrypt, decrypt, probes)) {
                    return new CryptoPair(encrypt, decrypt);
                }
            }
        }
        return NO_CRYPTO;
    }

    /** 收集实体可达模组类里的静态 {@code (String) -> String} 方法 */
    private static List<Method> stringStringMethods(Class<?> type) {
        List<Method> candidates = new ArrayList<>();
        try {
            for (Class<?> clazz : referencedModClasses(type)) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (candidates.size() >= CRYPTO_MAX_CANDIDATES) return candidates;
                    if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
                    if (method.getParameterCount() != 1) continue;
                    if (method.getParameterTypes()[0] != String.class || method.getReturnType() != String.class) {
                        continue;
                    }
                    if (cannotAccess(method)) continue;
                    candidates.add(method);
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败时按已收集到的候选继续
        }
        return candidates;
    }

    /** 把某个「解密口」的结果当数字读出来（解不出数字返回 null） */
    private static Double decryptAsNumber(Method decrypt, String raw) {
        try {
            if (decrypt.invoke(null, raw) instanceof String text) return Double.parseDouble(text);
        } catch (Throwable ignored) {
            // 解不出来即视为不是解密口
        }
        return null;
    }

    /** 该数是否不像这个实体的血量 */
    private static boolean implausibleHealth(LivingEntity victim, Double value) {
        if (value == null || !Double.isFinite(value)) return true;
        float max = victim.getMaxHealth();
        double limit = max > 0.0F ? max * 1.5 + 1.0 : Math.abs(victim.getHealth()) * 1.5 + 1.0;
        return value < -limit || value > limit;
    }

    /** 实体域 + 固定值的明文样本 */
    private static String[] cryptoProbes(LivingEntity victim) {
        List<String> probes = new ArrayList<>(Arrays.asList(CRYPTO_PROBES));
        addCryptoProbe(probes, victim.getHealth());
        addCryptoProbe(probes, victim.getMaxHealth());
        addCryptoProbe(probes, victim.getMaxHealth() * 0.5);
        addCryptoProbe(probes, 0.0);
        return probes.toArray(new String[0]);
    }

    private static void addCryptoProbe(List<String> probes, double value) {
        if (!Double.isFinite(value)) return;
        String text = plainText(value);
        if (!probes.contains(text)) probes.add(text);
    }

    /** 校验 {@code outer(inner(x)) == x} 对全部样本成立（inner 为加密、outer 为解密） */
    private static boolean roundTripsOn(Method outer, Method inner, String[] probes) {
        for (String probe : probes) {
            try {
                if (!(inner.invoke(null, probe) instanceof String middle)) return false;
                if (!(outer.invoke(null, middle) instanceof String back)) return false;
                if (!probe.equals(back)) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    // ===== 数值↔字符串编解码血量字段（档 3）：改写主字段与镜像字段 =====

    /** 数值编解码器：{@code decode(encode(x)) == x}（encode 可带随机化，故只验证单向还原） */
    private record ValueCodec(Method encode, Method decode) {
    }

    /** 编码血量方案：编解码器 + 主字段 + 镜像字段（可选）+ 镜像包装方法（可选） */
    private record EncodedHealthPlan(ValueCodec codec, Field health, Field shadow, Method wrap) {
    }

    /** 编码血量方案按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, EncodedHealthPlan> ENCODED_HEALTH_CACHE = new ConcurrentHashMap<>();

    /** 外部权威缓存「注销口」按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, List<Method>> AUTHORITY_PURGE_CACHE = new ConcurrentHashMap<>();

    /** 编解码互逆性的数字样本 */
    private static final float[] ENCODE_PROBES = {0.0F, 1.0F, 7.0F, 42.0F, 100.5F, 1000.0F};

    /** 用实体自己的编码器改写血量主字段与镜像字段 */
    private static void writeViaEncodedHealth(LivingEntity victim, float healthAfter) {
        EncodedHealthPlan plan = encodedHealthPlan(victim);
        if (plan == null) return;
        String encoded = encodeValue(plan.codec(), healthAfter);
        if (encoded == null) return;
        // 先注销外部权威缓存里该实体的登记
        purgeAuthority(victim);
        String previousHealth = readStringField(plan.health(), victim);
        String previousShadow = plan.shadow() == null ? null : readStringField(plan.shadow(), victim);
        writeStringField(plan.health(), victim, encoded);
        if (plan.shadow() != null && plan.wrap() != null) {
            String shadow = invokeWrap(plan.wrap(), encoded);
            if (shadow != null) writeStringField(plan.shadow(), victim, shadow);
        }
        float now = healthOf(victim);
        if (matches(now, healthAfter)) {
            probeLog("编码数值字段层写入生效：目标={} getHealth={}", healthAfter, now);
            return;
        }
        probeLog("编码数值字段层未生效，已还原：目标={} getHealth={}", healthAfter, now);
        writeStringField(plan.health(), victim, previousHealth);
        if (plan.shadow() != null) writeStringField(plan.shadow(), victim, previousShadow);
    }

    private static EncodedHealthPlan encodedHealthPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        EncodedHealthPlan cached = ENCODED_HEALTH_CACHE.get(type);
        if (cached != null) return cached;
        EncodedHealthPlan found;
        try {
            found = discoverEncodedHealth(victim);
        } catch (Throwable ignored) {
            return null;
        }
        if (found != null) ENCODED_HEALTH_CACHE.put(type, found);
        return found;
    }

    /** 识别「主字段 + 镜像字段 + 编解码器」三件套，全部靠行为验证 */
    private static EncodedHealthPlan discoverEncodedHealth(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        ValueCodec codec = valueCodec(victim);
        if (codec == null) return null;

        Field main = null;
        outer:
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                if (confirmsValueField(victim, field, codec, health)) {
                    main = field;
                    break outer;
                }
            }
        }
        if (main == null) return null;

        Field shadow = null;
        Method wrap = null;
        String mainRaw = readStringField(main, victim);
        if (mainRaw != null) {
            List<Method> wrappers = stringWrappers(victim.getClass());
            outer2:
            for (Class<?> current : hierarchy(victim.getClass())) {
                for (Field field : current.getDeclaredFields()) {
                    if (field == main || field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() != String.class) continue;
                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String otherRaw = readStringField(field, victim);
                    if (otherRaw == null) continue;
                    for (Method candidate : wrappers) {
                        if (otherRaw.equals(invokeWrap(candidate, mainRaw))) {
                            shadow = field;
                            wrap = candidate;
                            break outer2;
                        }
                    }
                }
            }
        }
        return new EncodedHealthPlan(codec, main, shadow, wrap);
    }

    /** 行为验证某 String 字段是否为血量主字段 */
    private static boolean confirmsValueField(LivingEntity victim, Field field, ValueCodec codec, float health) {
        String raw = readStringField(field, victim);
        if (raw == null || raw.isEmpty()) return false;
        Double stored = decodeValue(codec, raw);
        if (stored == null || !Double.isFinite(stored)) return false;
        double delta = Math.max(1.0, Math.abs(health) * 0.01);
        if (Math.abs(stored - health) > delta) return false;
        String perturbed = encodeValue(codec, health + delta);
        if (perturbed == null) return false;
        try {
            writeStringField(field, victim, perturbed);
            return matches(healthOf(victim), (float) (health + delta));
        } finally {
            writeStringField(field, victim, raw);
        }
    }

    /** 实体继承链上的静态数值编解码器对：{@code decode(encode(x)) == x} */
    private static ValueCodec valueCodec(LivingEntity victim) {
        List<Method> encoders = new ArrayList<>();
        List<Method> decoders = new ArrayList<>();
        for (Class<?> current : hierarchy(victim.getClass())) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                Class<?> returns = method.getReturnType();
                if (cannotAccess(method)) continue;
                if (returns == String.class && (param == float.class || param == double.class)) {
                    encoders.add(method);
                } else if (param == String.class && (returns == float.class || returns == double.class)) {
                    decoders.add(method);
                }
            }
        }
        if (encoders.isEmpty() || decoders.isEmpty()) return null;
        float[] probes = encodeProbes(victim);
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw == null || raw.isEmpty()) continue;
            for (Method decoder : decoders) {
                if (implausibleHealth(victim, decodeAsNumber(decoder, raw))) continue;
                for (Method encoder : encoders) {
                    if (codecRoundTripsOn(encoder, decoder, probes)) return new ValueCodec(encoder, decoder);
                }
            }
        }
        for (Method encoder : encoders) {
            for (Method decoder : decoders) {
                if (codecRoundTripsOn(encoder, decoder, probes)) return new ValueCodec(encoder, decoder);
            }
        }
        return null;
    }

    /** 数值解码器读数（解不出数值返回 null） */
    private static Double decodeAsNumber(Method decoder, String raw) {
        try {
            if (decoder.invoke(null, raw) instanceof Number number) return number.doubleValue();
        } catch (Throwable ignored) {
            // 解不出来即视为不是解码口
        }
        return null;
    }

    /** 实体域 + 固定值的数值样本 */
    private static float[] encodeProbes(LivingEntity victim) {
        double[] candidates = {
                victim.getHealth(), victim.getMaxHealth(), victim.getMaxHealth() * 0.5,
                victim.getHealth() - 1.0, victim.getHealth() + 1.0,
                ENCODE_PROBES[0], ENCODE_PROBES[1], ENCODE_PROBES[2], ENCODE_PROBES[3],
                ENCODE_PROBES[4], ENCODE_PROBES[5]
        };
        float[] probes = new float[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            probes[i] = Double.isFinite(candidates[i]) ? (float) candidates[i] : 0.0F;
        }
        return probes;
    }

    private static boolean codecRoundTripsOn(Method encoder, Method decoder, float[] probes) {
        for (float probe : probes) {
            try {
                Object middle = encoder.invoke(null, boxNumber(encoder.getParameterTypes()[0], probe));
                if (!(middle instanceof String text)) return false;
                Object back = decoder.invoke(null, text);
                if (!(back instanceof Number number)) return false;
                if (Math.abs(number.doubleValue() - probe) > PROBE_EPSILON) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    private static String encodeValue(ValueCodec codec, double value) {
        if (codec == null) return null;
        try {
            Class<?> param = codec.encode().getParameterTypes()[0];
            return codec.encode().invoke(null, boxNumber(param, value)) instanceof String text ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double decodeValue(ValueCodec codec, String raw) {
        if (codec == null || raw == null || raw.isEmpty()) return null;
        try {
            return codec.decode().invoke(null, raw) instanceof Number number ? number.doubleValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 实体继承链上的静态 {@code (String) -> String} 方法（镜像字段的包装器） */
    private static List<Method> stringWrappers(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] != String.class || method.getReturnType() != String.class) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                methods.add(method);
            }
        }
        return methods;
    }

    private static String invokeWrap(Method wrap, String value) {
        if (wrap == null) return null;
        return invokeObject(wrap, null, new Object[]{value}) instanceof String text ? text : null;
    }

    private static String readStringField(Field field, Object owner) {
        try {
            return field.get(owner) instanceof String text ? text : null;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    @SuppressWarnings("CatchMayIgnoreException")
    private static void writeStringField(Field field, Object owner, String value) {
        try {
            field.set(owner, value);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            // 写入失败即放弃
        }
    }

    // ---- 外部权威缓存注销 ----

    private static void purgeAuthority(LivingEntity victim) {
        for (Method purge : authorityPurges(victim)) {
            invokeQuietly(purge, new Object[]{victim});
        }
    }

    private static List<Method> authorityPurges(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<Method> cached = AUTHORITY_PURGE_CACHE.get(type);
        if (cached != null) return cached;
        List<Method> found = new ArrayList<>();
        float anchor = currentHealth(victim);
        if (!Float.isNaN(anchor)) {
            for (Class<?> clazz : healthCoreCandidates(victim)) {
                if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
                Method reader = authorityReader(clazz, victim, anchor);
                if (reader == null) continue;
                found.addAll(authorityRemovers(clazz, victim, reader));
            }
        }
        if (!found.isEmpty()) AUTHORITY_PURGE_CACHE.put(type, found);
        return found;
    }

    /** 类的静态方法（含非公开；供权威读口 / 注销口探测） */
    private static List<Method> declaredStaticMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Method[] declared;
        try {
            declared = clazz.getDeclaredMethods();
        } catch (Throwable ignored) {
            return result;
        }
        for (Method method : declared) {
            if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            result.add(method);
        }
        return result;
    }

    /** 静态读口：接收单个实体、返回其血量（与锚点一致） */
    private static Method authorityReader(Class<?> clazz, LivingEntity victim, float anchor) {
        for (Method method : declaredStaticMethods(clazz)) {
            if (method.getParameterCount() != 1) continue;
            Class<?> returns = method.getReturnType();
            if (returns != float.class && returns != double.class) continue;
            if (!method.getParameterTypes()[0].isInstance(victim)) continue;
            Double value = invokeForNumber(method, null, new Object[]{victim});
            if (value != null && !value.isNaN()
                    && Math.abs(value - anchor) <= Math.max(1.0, Math.abs(anchor) * 0.01)) {
                return method;
            }
        }
        return null;
    }

    /** 静态注销口：调用后上面那个读口对本实体返回 NaN */
    private static List<Method> authorityRemovers(Class<?> clazz, LivingEntity victim, Method reader) {
        List<Method> removers = new ArrayList<>();
        for (Method method : declaredStaticMethods(clazz)) {
            if (method == reader || method.getParameterCount() != 1) continue;
            Class<?> returns = method.getReturnType();
            if (returns != void.class && returns != boolean.class) continue;
            if (!method.getParameterTypes()[0].isInstance(victim)) continue;
            Object[] args = {victim};
            if (!invokeQuietly(method, args)) continue;
            Double after = invokeForNumber(reader, null, args);
            if (after != null && after.isNaN()) {
                removers.add(method);
            }
        }
        return removers;
    }

    // ===== 引用类静态表探针：改写「以实体为键」的静态 Map 条目 =====

    /** 引用关系展开深度与规模上限 */
    private static final int REFERENCE_MAX_DEPTH = 2;
    private static final int REFERENCE_MAX_CLASSES = 512;
    /** 「找字段登记表 / 静态容器 / 编解码器」这类间接持有形态用的更深一跳 */
    private static final int REGISTRY_MAX_DEPTH = 3;
    private static final int REFERENCE_MAX_TABLES = 64;

    /** 每个类只解析一次字节码，缓存它引用的类名（内部名，'/' 分隔） */
    private static final Map<Class<?>, List<String>> REFERENCED_CLASS_CACHE = new ConcurrentHashMap<>();

    /** 每个实体类只找一次静态表字段 */
    private static final Map<Class<?>, List<Field>> REFERENCED_TABLE_CACHE = new ConcurrentHashMap<>();

    /** 可达模组类 + 嵌套类：静态表查找与核心类候选共用 */
    private static final Map<Class<?>, List<Class<?>>> REFERENCED_NESTED_CACHE = new ConcurrentHashMap<>();

    /** 宽包名可达类 + 嵌套类：健康读写口与模组击杀口共用 */
    private static final Map<Class<?>, List<Class<?>>> WRITER_CANDIDATE_CACHE = new ConcurrentHashMap<>();

    /** 表位点的行为结论缓存：实体类 →（表字段 + 键类型）→ 探针结论 */
    private static final Map<Class<?>, Map<TableSlotKey, TableSlotProbe>> TABLE_SLOT_PROBE_CACHE =
            new ConcurrentHashMap<>();

    /** 键类型遍历顺序：false = 实体对象，true = 实体 UUID */
    private static final boolean[] KEY_IS_UUID = {false, true};

    /** 表位点标识：同一张表的不同键类型分开分类 */
    private record TableSlotKey(Field table, boolean uuidKey) {
    }

    /** 表位点的探针结论（只含类级稳定数据） */
    private record TableSlotProbe(int direction, boolean perturbsMaxHealth) {
    }

    /** 条目类继承链上可写的 value 字段偏移（按类缓存一次） */
    private static final Map<Class<?>, OptionalLong> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    /** Unsafe 实例（延迟解析；取不到则为 null） */
    private static Unsafe unsafe;
    private static boolean unsafeResolved;

    /** 在可达模组类的静态表里找“以该实体（或其 UUID）为键”的条目并写入全部命中表 */
    private static void writeReferencedStaticTables(LivingEntity victim, float healthAfter) {
        List<Field> tables = REFERENCED_TABLE_CACHE.computeIfAbsent(victim.getClass(),
                StrongTrueDamageHelper::findReferencedTables);
        if (tables.isEmpty()) return;

        float baseHealth = victim.getHealth();
        List<HealthSlot> direct = new ArrayList<>();
        List<HealthSlot> taken = new ArrayList<>();
        List<TableSlotEntry> unconfirmed = new ArrayList<>();
        for (Field field : tables) {
            try {
                if (!(field.get(null) instanceof Map<?, ?> map)) continue;
                for (boolean uuidKey : KEY_IS_UUID) {
                    HealthSlot slot = tableEntrySlot(map, uuidKey ? victim.getUUID() : victim);
                    if (slot == null) continue;
                    TableSlotProbe probe = tableSlotProbe(victim, field, uuidKey, slot, baseHealth);
                    if (probe.direction() == PROBE_DIRECT) {
                        direct.add(slot);
                    } else if (probe.direction() == PROBE_TAKEN) {
                        taken.add(slot);
                    } else {
                        unconfirmed.add(new TableSlotEntry(slot, probe));
                    }
                }
            } catch (Throwable ignored) {
                // 单个表不可读则跳过
            }
        }
        // 没有任何表被确认是血量存储时不动手
        if (direct.isEmpty() && taken.isEmpty()) return;
        // 「总量 - 已消耗」型派生血量：只写消耗位点
        if (writeDerivedHealth(victim, direct, taken, healthAfter)) return;
        writeHealthSlots(direct, taken, healthAfter, baseHealth - healthAfter);
        // 已确认存在血量表，才同步数值等于当前血量的镜像条目
        for (TableSlotEntry entry : unconfirmed) {
            HealthSlot slot = entry.slot();
            double value = slot.read();
            if (Double.isNaN(value) || !matches((float) value, baseHealth)) continue;
            // 排除最大生命表
            if (entry.probe().perturbsMaxHealth()) continue;
            try {
                slot.write(healthAfter);
            } catch (Throwable ignored) {
                // 单个位点写入失败不影响其它位点
            }
        }
    }

    /** 本次结算用到的“未确认位点 + 其探针结论”（只活在单次调用里） */
    private record TableSlotEntry(HealthSlot slot, TableSlotProbe probe) {
    }

    /** 取（或首次探测）某表位点的行为结论，按「实体类 + 表字段 + 键类型」缓存 */
    private static TableSlotProbe tableSlotProbe(LivingEntity victim, Field table, boolean uuidKey,
                                                 HealthSlot slot, float baseHealth) {
        Map<TableSlotKey, TableSlotProbe> perClass = TABLE_SLOT_PROBE_CACHE.computeIfAbsent(
                victim.getClass(), type -> new ConcurrentHashMap<>());
        return perClass.computeIfAbsent(new TableSlotKey(table, uuidKey),
                key -> new TableSlotProbe(probeHealthSlot(victim, slot, baseHealth),
                        perturbsMaxHealth(victim, slot)));
    }

    /** “以该实体（或其 UUID）为键”的表条目位点；表里没有该键则返回 null */
    @SuppressWarnings("unchecked")
    private static HealthSlot tableEntrySlot(Map<?, ?> map, Object key) {
        try {
            if (!map.containsKey(key)) return null;
            Object value = map.get(key);
            return new TableEntrySlot((Map<Object, Object>) map, key,
                    value instanceof Number number ? number.getClass() : Double.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 模组类上的静态 Map 字段（静态表探测与 MAC 权威记录探测共用） */
    private static boolean isStaticMapField(Field field) {
        return Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                && Map.class.isAssignableFrom(field.getType());
    }

    /** 实体类沿常量池引用关系可达的模组类里的静态 Map 字段 */
    private static List<Field> findReferencedTables(Class<?> entityType) {
        List<Class<?>> candidates = referencedNestedClasses(entityType);
        List<Field> tables = new ArrayList<>();
        for (Class<?> candidate : candidates) {
            for (Field field : candidate.getDeclaredFields()) {
                if (tables.size() >= REFERENCE_MAX_TABLES) break;
                if (isStaticMapField(field)) {
                    try {
                        field.setAccessible(true);
                        tables.add(field);
                    } catch (Throwable ignored) {
                        // 字段不可访问则跳过
                    }
                }
            }
        }
        return tables;
    }

    /** 把每个候选类的嵌套类一并纳入扫描范围 */
    private static List<Class<?>> withNestedClasses(List<Class<?>> candidates, int maxClasses) {
        List<Class<?>> holders = new ArrayList<>();
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Class<?>> queue = new ArrayDeque<>(candidates);
        while (!queue.isEmpty() && holders.size() < maxClasses) {
            Class<?> current = queue.poll();
            if (current == null || !seen.add(current)) continue;
            holders.add(current);
            try {
                Collections.addAll(queue, current.getDeclaredClasses());
            } catch (Throwable ignored) {
                // 取不到嵌套类时只扫描该类自身
            }
        }
        return holders;
    }

    /** 常量池可达模组类 + 嵌套类（静态表查找与核心类候选共用） */
    private static List<Class<?>> referencedNestedClasses(Class<?> entityType) {
        List<Class<?>> cached = REFERENCED_NESTED_CACHE.get(entityType);
        if (cached != null) return cached;
        List<Class<?>> found = withNestedClasses(referencedModClasses(entityType), REFERENCE_MAX_CLASSES);
        // 空结果不缓存
        if (!found.isEmpty()) REFERENCED_NESTED_CACHE.put(entityType, found);
        return found;
    }

    /** 宽包名可达类 + 嵌套类（健康读写口与模组击杀口共用） */
    private static List<Class<?>> writerCandidateClasses(Class<?> entityType) {
        List<Class<?>> cached = WRITER_CANDIDATE_CACHE.get(entityType);
        if (cached != null) return cached;
        List<Class<?>> found = withNestedClasses(referencedWriterClasses(entityType), WRITER_REFERENCE_MAX_CLASSES);
        // 空结果不缓存
        if (!found.isEmpty()) WRITER_CANDIDATE_CACHE.put(entityType, found);
        return found;
    }

    /** 从实体类出发沿常量池引用关系广度优先展开，收集可达的模组类（不含起点自身） */
    private static List<Class<?>> referencedModClasses(Class<?> entityType) {
        return referencedClasses(entityType, false, REFERENCE_MAX_DEPTH, REFERENCE_MAX_CLASSES);
    }

    /** 更深一跳的模组类展开，给「找字段登记表 / 静态容器 / 编解码器」这类间接持有形态用 */
    private static List<Class<?>> referencedModClassesDeep(Class<?> entityType) {
        return referencedClasses(entityType, false, REGISTRY_MAX_DEPTH, REFERENCE_MAX_CLASSES);
    }

    /** 血量写入口探针用的可达类集合：模组类之外还包含伪装成 {@code net.minecraft.*} 的核心库 */
    private static List<Class<?>> referencedWriterClasses(Class<?> entityType) {
        return referencedClasses(entityType, true, WRITER_REFERENCE_MAX_DEPTH, WRITER_REFERENCE_MAX_CLASSES);
    }

    private static List<Class<?>> referencedClasses(Class<?> entityType, boolean enginePackages,
                                                    int maxDepth, int maxClasses) {
        List<Class<?>> found = new ArrayList<>();
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{entityType, 0});
        seen.add(entityType);
        // 父类链一并作为起点
        for (Class<?> parent = entityType.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
            if (refusesReferenced(enginePackages, parent.getName()) || !seen.add(parent)) continue;
            found.add(parent);
            queue.add(new Object[]{parent, 0});
        }
        while (!queue.isEmpty() && found.size() < maxClasses) {
            Object[] entry = queue.poll();
            Class<?> current = (Class<?>) entry[0];
            int depth = (Integer) entry[1];
            for (String name : referencedClasses(current)) {
                if (name.startsWith("[")) continue;
                String binaryName = name.replace('/', '.');
                if (refusesReferenced(enginePackages, binaryName)) continue;
                Class<?> referenced = loadClass(binaryName, entityType.getClassLoader());
                if (referenced == null || referenced.isInterface() || !seen.add(referenced)) continue;
                found.add(referenced);
                if (depth + 1 < maxDepth) {
                    queue.add(new Object[]{referenced, depth + 1});
                }
            }
        }
        return found;
    }

    /** 引用类是否纳入候选：模组类恒纳入；{@code enginePackages} 为真时连伪装成原版/Forge 的类一起纳入 */
    private static boolean refusesReferenced(boolean enginePackages, String binaryName) {
        if (isUnsafeToInvoke(binaryName)) return true;
        return !isModClassName(binaryName) && (!enginePackages || isNotEnginePackageName(binaryName));
    }

    /** 名称落在原版 / Forge / Mojang 前缀里的类 */
    private static boolean isNotEnginePackageName(String name) {
        return !name.startsWith("net.minecraft.") && !name.startsWith("net.minecraftforge.")
                && !name.startsWith("com.mojang.");
    }

    /** 反射调用时禁止触碰的类前缀（如 LWJGL / blaze3d） */
    private static boolean isUnsafeToInvoke(String binaryName) {
        return binaryName.startsWith("org.lwjgl.") || binaryName.startsWith("com.mojang.blaze3d.");
    }

    /** 类文件常量池里列出的被引用类名（内部名）；解析失败返回空列表 */
    private static List<String> referencedClasses(Class<?> type) {
        return REFERENCED_CLASS_CACHE.computeIfAbsent(type, target -> {
            byte[] bytes = readClassBytes(target);
            return bytes == null ? List.of() : parseReferencedClasses(bytes);
        });
    }

    /** 读取类文件字节；取不到时该探针整体跳过 */
    private static byte[] readClassBytes(Class<?> type) {
        String path = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        byte[] bytes = readResource(type.getResourceAsStream("/" + path));
        if (bytes == null && loader != null) {
            bytes = readResource(loader.getResourceAsStream(path));
        }
        if (bytes == null && context != null && context != loader) {
            bytes = readResource(context.getResourceAsStream(path));
        }
        return bytes;
    }

    private static byte[] readResource(InputStream stream) {
        if (stream == null) return null;
        try (stream) {
            return stream.readAllBytes();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 最小常量池解析：取出所有 {@code CONSTANT_Class_info}(tag 7) 指向的类名 */
    private static List<String> parseReferencedClasses(byte[] bytes) {
        return parseClassNames(bytes, false);
    }

    /** 常量池解析：{@code collectStringNames} 为真时还收集类名形态的 {@code Utf8} 字符串常量 */
    private static List<String> parseClassNames(byte[] bytes, boolean collectStringNames) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int poolSize = in.readUnsignedShort();
            String[] utf8 = new String[poolSize];
            int[] classNames = new int[poolSize];
            List<String> stringNames = collectStringNames ? new ArrayList<>() : null;
            for (int i = 1; i < poolSize; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> {
                        String value = in.readUTF();
                        utf8[i] = value;
                        if (stringNames != null && looksLikeInternalClassName(value)) stringNames.add(value);
                    }
                    case 7 -> classNames[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++;
                    }
                    default -> {
                        return List.of();
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (int index : classNames) {
                if (index > 0 && index < poolSize && utf8[index] != null) names.add(utf8[index]);
            }
            if (stringNames != null) names.addAll(stringNames);
            return names;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** 值是否形如 JVM 内部类名（{@code a/b/C$D}） */
    private static boolean looksLikeInternalClassName(String value) {
        if (value == null) return false;
        int length = value.length();
        if (length < 3) return false;
        boolean hasSeparator = false;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '/') {
                if (i == 0 || i + 1 == length) return false;
                hasSeparator = true;
            } else if (c != '_' && c != '$' && !(c >= '0' && c <= '9')
                    && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        return hasSeparator;
    }

    /** 只加载不初始化；失败返回 null */
    private static Class<?> loadClass(String binaryName, ClassLoader loader) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 扰动位点后观察 {@code getMaxHealth()} 是否变化 */
    private static boolean perturbsMaxHealth(LivingEntity victim, HealthSlot slot) {
        double original;
        try {
            original = slot.read();
        } catch (Throwable ignored) {
            return false;
        }
        if (Double.isNaN(original)) return false;
        double delta = Math.max(1.0, Math.abs(original) * 0.01);
        float maxBefore = victim.getMaxHealth();
        try {
            slot.write(original + delta);
            if (!matches(victim.getMaxHealth(), maxBefore)) return true;
            slot.write(original - delta);
            return !matches(victim.getMaxHealth(), maxBefore);
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                slot.write(original);
            } catch (Throwable ignored) {
                // 还原失败则保持扰动值
            }
        }
    }

    /** 静态表条目位点：优先走条目自身的 {@code setValue}，依次退回 Unsafe 与常规 put */
    private record TableEntrySlot(Map<Object, Object> map, Object key, Class<?> valueType) implements HealthSlot {
        @Override
        public double read() {
            try {
                Object value = map.get(key);
                if (value instanceof Number number) return number.doubleValue();
            } catch (Throwable ignored) {
                // 读取被守卫拦截时退回直接读条目字段
            }
            List<Map.Entry<?, ?>> entries = findEntries();
            if (entries.isEmpty()) return Double.NaN;
            OptionalLong offset = entryValueOffset(entries.get(0).getClass());
            if (offset.isEmpty()) return Double.NaN;
            Object stored = unsafeGetObject(entries.get(0), offset.getAsLong());
            return stored instanceof Number number ? number.doubleValue() : Double.NaN;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void write(double value) {
            Object boxed = boxNumber(valueType, value);
            List<Map.Entry<?, ?>> entries = findEntries();
            if (!entries.isEmpty()) {
                boolean wrote = false;
                for (Map.Entry<?, ?> entry : entries) {
                    try {
                        ((Map.Entry<Object, Object>) entry).setValue(boxed);
                        wrote = true;
                    } catch (Throwable ignored) {
                        // 单个条目写不进去时继续尝试其余条目
                    }
                }
                if (wrote) return;
                OptionalLong offset = entryValueOffset(entries.get(0).getClass());
                if (offset.isPresent()) {
                    for (Map.Entry<?, ?> entry : entries) {
                        unsafePutObject(entry, offset.getAsLong(), boxed);
                    }
                    return;
                }
            }
            try {
                map.put(key, boxed);
            } catch (Throwable ignored) {
                // 常规写入也被拒绝时维持现状
            }
        }

        /** 在 entrySet 里找出所有 key 匹配的条目 */
        private List<Map.Entry<?, ?>> findEntries() {
            List<Map.Entry<?, ?>> matched = new ArrayList<>();
            try {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object entryKey = entry == null ? null : entry.getKey();
                    if (entryKey == null) continue;
                    if (entryKey == key || entryKey.equals(key)) {
                        matched.add(entry);
                    }
                }
            } catch (Throwable ignored) {
                // 表不支持 entrySet 时没有这条退回路径
            }
            return matched;
        }
    }

    // ===== 血量写入口探针：找「读血量 / 写血量」方法对 =====

    private static final Map<Class<?>, List<HealthWriter>> HEALTH_WRITER_CACHE = new ConcurrentHashMap<>();
    /** 单个实体类最多尝试多少个“候选读方法” */
    private static final int WRITER_MAX_CANDIDATES = 512;
    /** 写入口探针的可达类展开预算 */
    private static final int WRITER_REFERENCE_MAX_DEPTH = 4;
    private static final int WRITER_REFERENCE_MAX_CLASSES = 4096;

    /** 模组自己的血量写入口（写方法 + 调用模板 + 数值位下标） */
    private record HealthWriter(Method method, Object[] template, int valueIndex) {
    }

    /** 用模组自己的写入口把血量改成目标值，全部写完仍不到位则按原值还原 */
    private static void writeViaHealthWriter(LivingEntity victim, float healthAfter) {
        List<HealthWriter> writers = healthWriters(victim);
        if (writers.isEmpty()) return;
        float before = healthOf(victim);
        for (HealthWriter writer : writers) {
            setViaWriter(writer, victim, healthAfter);
        }
        if (matches(healthOf(victim), healthAfter)) return;
        if (!Float.isNaN(before)) {
            for (HealthWriter writer : writers) {
                setViaWriter(writer, victim, before);
            }
        }
    }

    private static void setViaWriter(HealthWriter writer, LivingEntity victim, float value) {
        invokeQuietly(writer.method(), fillArgs(writer.method(), writer.template(), writer.valueIndex(), victim, value));
    }

    // ===== 死亡流程兜底（档 3）：权威血量清零 + 击杀入口 =====

    /** 把该实体所有已验证的绝对血量写入口都写成 0，直到实体自认不再是活的 / 已被移除 */
    private static void zeroAuthorityHealth(LivingEntity victim) {
        for (HealthWriter writer : healthWriters(victim)) {
            setViaWriter(writer, victim, 0.0F);
            if (victim.isRemoved() || !victim.isAlive()) return;
        }
    }

    private static final Map<Class<?>, Method> MOD_KILL_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NO_MOD_KILL = ConcurrentHashMap.newKeySet();

    /** 血量已归零、但实体自认尚未死亡 → 调模组自己的击杀入口兜底 */
    private static void forceModKillIfNeeded(LivingEntity victim, DamageSource source) {
        if (victim.isRemoved() || victim.isDeadOrDying() || victim.getHealth() > 0.0F) return;
        Method kill = modKillEntry(victim, source);
        if (kill == null) return;
        PENDING_FORCED_DEATH.add(victim);
        try {
            invokeQuietly(kill, new Object[]{victim, source});
        } finally {
            PENDING_FORCED_DEATH.remove(victim);
        }
    }

    /** 模组自己的“击杀入口”（调用后实体被移除或不再自认存活），命中与否都按类缓存 */
    private static Method modKillEntry(LivingEntity victim, DamageSource source) {
        Class<?> type = victim.getClass();
        Method cached = MOD_KILL_CACHE.get(type);
        if (cached != null) return cached;
        if (NO_MOD_KILL.contains(type)) return null;
        for (Class<?> clazz : writerCandidateClasses(type)) {
            if (clazz == null || !isModClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) continue;
                Class<?> returns = method.getReturnType();
                if (returns != void.class && returns != boolean.class) continue;
                if (params[1] != DamageSource.class || !params[0].isInstance(victim)) continue;
                invokeQuietly(method, new Object[]{victim, source});
                if (victim.isRemoved() || !victim.isAlive()) {
                    MOD_KILL_CACHE.put(type, method);
                    return method;
                }
            }
        }
        NO_MOD_KILL.add(type);
        return null;
    }

    /** 由模板生成一次调用的实参：留空的定位位按形参类型补当前实体，数值位填入给定值 */
    private static Object[] fillArgs(Method method, Object[] template, int valueIndex,
                                     LivingEntity victim, double value) {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (i == valueIndex) {
                args[i] = boxNumber(params[i], value);
                continue;
            }
            // 定位实参随实例变化，模板里一律留空，只能在调用时按当前实体填
            Object fixed = i < template.length ? template[i] : null;
            args[i] = fixed != null ? fixed : identityArg(params[i], victim);
        }
        return args;
    }

    /** 实体的定位实参：实体本身 / 实体 id / 实体 UUID */
    private static Object identityArg(Class<?> param, LivingEntity victim) {
        if (param == int.class || param == Integer.class) return victim.getId();
        if (param == UUID.class) return victim.getUUID();
        return victim;
    }

    private static List<HealthWriter> healthWriters(LivingEntity victim) {
        return HEALTH_WRITER_CACHE.computeIfAbsent(victim.getClass(),
                type -> discoverHealthWriters(victim));
    }

    /** 识别该类实体的“血量写入口”：读方法与写方法都要过行为验证 */
    private static List<HealthWriter> discoverHealthWriters(LivingEntity victim) {
        float healthBefore = healthOf(victim);
        if (Float.isNaN(healthBefore) || !(healthBefore > 0.0F)) return List.of();
        double delta = Math.max(1.0, Math.abs(healthBefore) * 0.01);
        List<HealthWriter> found = new ArrayList<>();
        int inspected = 0;
        List<Class<?>> candidates = writerCandidateClasses(victim.getClass());
        for (Class<?> clazz : candidates) {
            if (inspected == WRITER_MAX_CANDIDATES) break;
            // 原生/图形栈必须挡在反射调用之前
            if (isUnsafeToInvoke(clazz.getName())) continue;
            if (!isModClass(clazz) && isNotEnginePackageName(clazz.getName())) continue;
            List<Method> methods = publicStaticMethods(clazz);
            for (Method reader : methods) {
                if (inspected == WRITER_MAX_CANDIDATES) break;
                Class<?> returns = reader.getReturnType();
                if (returns != float.class && returns != double.class) continue;
                // 读方法可以没有数值位（如 get(UUID)），此时目标血量只能填在写方法的参数里
                int readerValue = singleNumberParam(reader);
                List<Object[]> templates = argTemplates(reader, readerValue, victim);
                if (templates.isEmpty()) continue;
                // 预算只统计真正进入探测的读口
                inspected++;
                try {
                    reader.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                for (Object[] readerTemplate : templates) {
                    if (!readsVictimHealth(reader, readerTemplate, readerValue, victim, healthBefore)) continue;
                    if (probeWriterCandidates(methods, reader, readerValue, readerTemplate,
                            victim, delta, found)) {
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** 为已通过读验证的读方法逐个试写方法：任一试成即记入 {@code found} 并返回 true */
    private static boolean probeWriterCandidates(List<Method> methods, Method reader, int readerValue,
                                                 Object[] readerTemplate, LivingEntity victim, double delta,
                                                 List<HealthWriter> found) {
        for (Method candidate : methods) {
            if (candidate == reader) continue;
            Class<?> returns = candidate.getReturnType();
            if (returns != void.class && returns != boolean.class) continue;
            int valueIndex = writerValueIndex(reader, readerValue, candidate);
            if (valueIndex < 0) continue;
            Object[] template = writerTemplate(readerTemplate, candidate.getParameterTypes().length);
            try {
                candidate.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            if (probeWriter(candidate, template, valueIndex, victim, delta)) {
                found.add(new HealthWriter(candidate, template, valueIndex));
                return true;
            }
        }
        return false;
    }

    /** 读方法取值验证：以固定参数调用它，返回值必须正好等于该实体当前血量 */
    private static boolean readsVictimHealth(Method reader, Object[] template, int valueIndex,
                                             LivingEntity victim, float healthBefore) {
        Double value = invokeForNumber(reader, null, fillArgs(reader, template, valueIndex, victim, 0.0));
        return value != null && matches(value.floatValue(), healthBefore);
    }

    /** 写方法行为验证：写入「当前血量 ± delta」，看当前血量是否正好随之变化 */
    private static boolean probeWriter(Method writer, Object[] template, int valueIndex,
                                       LivingEntity victim, double delta) {
        float healthBefore = healthOf(victim);
        float maxBefore = maxHealthOf(victim);
        if (Float.isNaN(healthBefore) || Float.isNaN(maxBefore)) return false;
        double down = healthBefore - delta;
        // 向下探（不越界）；血量不足一个 delta 时才退回向上探
        double[] probes = down > 0.0 ? new double[]{down, healthBefore + delta} : new double[]{healthBefore + delta};
        for (double probe : probes) {
            if (!invokeQuietly(writer, fillArgs(writer, template, valueIndex, victim, probe))) return false;
            float healthNow = healthOf(victim);
            float maxNow = maxHealthOf(victim);
            if (matches(healthNow, (float) probe)) {
                invokeQuietly(writer, fillArgs(writer, template, valueIndex, victim, healthBefore));
                return true;
            }
            // 没命中也要还原
            if (!matches(healthNow, healthBefore)) {
                invokeQuietly(writer, fillArgs(writer, template, valueIndex, victim, healthBefore));
            }
            if (!matches(maxNow, maxBefore)) {
                invokeQuietly(writer, fillArgs(writer, template, valueIndex, victim, maxBefore));
            }
            // 观察到的是最大血量在动，换下一个常量再试
            if (matches(maxNow, (float) (maxBefore + (probe - healthBefore)))) return false;
        }
        return false;
    }

    /** 写方法与读方法的参数表对应关系 → 目标血量应填的参数下标，形状不匹配返回 -1 */
    private static int writerValueIndex(Method reader, int readerValue, Method writer) {
        Class<?>[] readerParams = reader.getParameterTypes();
        Class<?>[] writerParams = writer.getParameterTypes();
        if (writerParams.length == readerParams.length) {
            return readerValue >= 0 && sameParamTypes(readerParams, writerParams) ? readerValue : -1;
        }
        if (readerValue >= 0 || writerParams.length != readerParams.length + 1) return -1;
        Class<?> last = writerParams[writerParams.length - 1];
        if (last != float.class && last != double.class) return -1;
        for (int i = 0; i < readerParams.length; i++) {
            if (readerParams[i] != writerParams[i]) return -1;
        }
        return writerParams.length - 1;
    }

    /** 写方法的调用模板 = 读方法模板（多出的数值位留空） */
    private static Object[] writerTemplate(Object[] readerTemplate, int writerParamCount) {
        return writerParamCount == readerTemplate.length
                ? readerTemplate.clone()
                : Arrays.copyOf(readerTemplate, writerParamCount);
    }

    private static boolean sameParamTypes(Class<?>[] left, Class<?>[] right) {
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) return false;
        }
        return true;
    }

    /** 构造调用参数模板：数值位与定位位留空，只放行能明确填对的参数类型 */
    private static List<Object[]> argTemplates(Method method, int valueIndex, LivingEntity victim) {
        Class<?>[] params = method.getParameterTypes();
        Object[] base = new Object[params.length];
        int enumIndex = -1;
        Class<?> enumType = null;
        boolean carriesIdentity = false;
        for (int i = 0; i < params.length; i++) {
            if (i == valueIndex) continue;
            Class<?> param = params[i];
            // 定位位留空待调用时填；其余类型一律放弃，尤其 long 这类可能是地址的参数
            boolean identity = param == int.class || param == Integer.class || param == UUID.class
                    || param.isInstance(victim);
            if (!identity && !param.isEnum()) return List.of();
            if (param.isEnum()) {
                // 两个枚举参数无法判定组合，放弃
                if (enumIndex >= 0) return List.of();
                enumIndex = i;
                enumType = param;
            } else {
                carriesIdentity = true;
            }
        }
        // 血量存取口必须按实体定位
        if (!carriesIdentity) return List.of();
        if (enumIndex < 0) return Collections.singletonList(base);
        Object[] constants;
        try {
            constants = enumType.getEnumConstants();
        } catch (Throwable ignored) {
            return List.of();
        }
        if (constants == null || constants.length == 0 || constants.length > 8) return List.of();
        List<Object[]> templates = new ArrayList<>();
        for (Object constant : constants) {
            Object[] template = base.clone();
            template[enumIndex] = constant;
            templates.add(template);
        }
        return templates;
    }

    /** 唯一的 float/double 参数下标；没有或多个则返回 -1 */
    private static int singleNumberParam(Method method) {
        int index = -1;
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i] != float.class && params[i] != double.class) continue;
            if (index >= 0) return -1;
            index = i;
        }
        return index;
    }

    /** 类的公开静态方法（非合成） */
    private static List<Method> publicStaticMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Method[] declared;
        try {
            declared = clazz.getDeclaredMethods();
        } catch (Throwable ignored) {
            return result;
        }
        for (Method method : declared) {
            int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && !method.isSynthetic()) {
                result.add(method);
            }
        }
        return result;
    }

    // ===== 持有对象读写口探针：以同步血量为锚点配无门控读写口 =====

    /** 读方法的实参取值规则 */
    private static final int ARG_IDENTITY = 0;
    /** 实参取自实体自身的同类型字段（如实体持有的密钥表） */
    private static final int ARG_ENTITY_FIELD = 1;
    /** 实参取自持有对象自身的同类型字段 */
    private static final int ARG_HOLDER_FIELD = 2;

    /** 持有对象上的“无门控血量读口”（{@code path} 从实体出发逐跳解析持有对象） */
    private record HolderReader(List<Field> path, Method method, int[] kinds, Field[] sources) {
    }

    /** 持有对象上的“无门控血量写口”（单个 float/double 形参） */
    private record HolderWriter(List<Field> path, Method method) {
    }

    private record HolderPlan(HolderReader reader, HolderWriter writer) {
    }

    /** 持有对象读写口：按实体类缓存（只存类级数据） */
    private static final Map<Class<?>, List<HolderPlan>> HOLDER_PLAN_CACHE = new ConcurrentHashMap<>();

    private static final int HOLDER_MAX_DEPTH = 4;
    private static final int HOLDER_MAX_NODES = 256;
    private static final int HOLDER_MAX_READERS = 8;
    private static final int HOLDER_MAX_ARG_SPECS = 8;
    private static final int HOLDER_MAX_READER_PARAMS = 2;

    /** 门控实体或仍未写到位时，就走持有对象读写口 */
    private static boolean needsHolderWrite(boolean gatedGetter, LivingEntity victim, float healthAfter) {
        return gatedGetter || stillAboveTarget(victim, healthAfter);
    }

    /** 用实体可达对象上的无门控写口把血量改成目标值 */
    private static void writeViaHolderWriter(LivingEntity victim, float healthAfter) {
        for (HolderPlan plan : holderPlans(victim)) {
            Object holder = resolveHolder(victim, plan.writer().path());
            if (holder == null) continue;
            float before = readViaHolderReader(victim, plan.reader());
            if (!invokeInstanceWithNumber(plan.writer().method(), holder, healthAfter)) continue;
            float now = readViaHolderReader(victim, plan.reader());
            if (!Float.isNaN(now) && matches(now, healthAfter)) return;
            // 没写到位就按读口读到的原值还原
            if (!Float.isNaN(before)) invokeInstanceWithNumber(plan.writer().method(), holder, before);
        }
    }

    /** 该类实体的持有对象读写口（首次触发才探测）；锚点无效时不缓存 */
    private static List<HolderPlan> holderPlans(LivingEntity victim) {
        List<HolderPlan> cached = HOLDER_PLAN_CACHE.get(victim.getClass());
        if (cached != null) return cached;
        float anchor = currentHealth(victim);   // 锚点 = 真实血量
        if (Float.isNaN(anchor) || !(anchor > 0.0F)) return List.of();
        List<Object[]> holders = reachableHolders(victim);
        List<HolderPlan> found = new ArrayList<>();
        // 读方法可能不止一个吻合，逐个配写方法
        for (HolderReader reader : findHolderReaders(victim, holders, anchor)) {
            HolderWriter writer = findHolderWriter(victim, holders, reader, anchor);
            if (writer != null) {
                found.add(new HolderPlan(reader, writer));
                break;
            }
        }
        HOLDER_PLAN_CACHE.put(victim.getClass(), found);
        return found;
    }

    /** 实体可达的持有对象（含自身）：只走实例引用字段，不展开集合/数组/其它实体 */
    private static List<Object[]> reachableHolders(LivingEntity victim) {
        List<Object[]> holders = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{victim, List.<Field>of(), 0});
        visited.add(victim);
        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < HOLDER_MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            @SuppressWarnings("unchecked")
            List<Field> path = (List<Field>) entry[1];
            int depth = (Integer) entry[2];
            holders.add(new Object[]{path, node});
            if (depth >= HOLDER_MAX_DEPTH) continue;
            for (Field field : deepCandidateFields(node.getClass())) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                Object value;
                try {
                    value = field.get(node);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null || value instanceof Entity && value != victim || !isHolderNode(value)) continue;
                if (!visited.add(value)) continue;
                List<Field> childPath = new ArrayList<>(path);
                childPath.add(field);
                queue.add(new Object[]{value, childPath, depth + 1});
            }
        }
        return holders;
    }

    /** 值得当作“血量保管者”展开的对象：排除字符串/数值/数组/集合/大对象 */
    private static boolean isHolderNode(Object value) {
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return false;
        if (value.getClass().isArray() || value instanceof Iterable<?> || value instanceof Map<?, ?>) return false;
        if (value instanceof Class<?> || value instanceof ClassLoader) return false;
        return !skipDeepClass(value.getClass());
    }

    /** 找出读值正好等于锚点的无门控读方法 */
    private static List<HolderReader> findHolderReaders(LivingEntity victim, List<Object[]> holders, float anchor) {
        List<HolderReader> found = new ArrayList<>();
        for (Object[] entry : holders) {
            Object holder = entry[1];
            @SuppressWarnings("unchecked")
            List<Field> path = (List<Field>) entry[0];
            for (Method method : numericReturningMethods(holder.getClass())) {
                for (Object[] spec : argSpecs(method, victim, holder)) {
                    HolderReader reader = new HolderReader(path, method, (int[]) spec[0], (Field[]) spec[1]);
                    Object[] args = resolveArgs(reader, victim, holder);
                    if (args == null) continue;
                    Double value = invokeForNumber(method, holder, args);
                    if (value != null && matches(value.floatValue(), anchor)) {
                        found.add(reader);
                        break;
                    }
                }
                if (found.size() >= HOLDER_MAX_READERS) return found;
            }
        }
        return found;
    }

    /** 用读口验证“单个 float/double 形参的写方法”：写入 probe 后读口必须正好等于 probe */
    private static HolderWriter findHolderWriter(LivingEntity victim, List<Object[]> holders,
                                                 HolderReader reader, float anchor) {
        double delta = Math.max(1.0, Math.abs(anchor) * 0.01);
        double down = anchor - delta;
        double[] probes = down > 0.0 ? new double[]{down, anchor + delta} : new double[]{anchor + delta};
        for (Object[] entry : holders) {
            Object holder = entry[1];
            @SuppressWarnings("unchecked")
            List<Field> path = (List<Field>) entry[0];
            for (Method method : singleNumberSetters(holder.getClass())) {
                for (double probe : probes) {
                    if (!invokeInstanceWithNumber(method, holder, probe)) break;
                    float observed = readViaHolderReader(victim, reader);
                    if (!Float.isNaN(observed) && matches(observed, (float) probe)) {
                        invokeInstanceWithNumber(method, holder, anchor);
                        return new HolderWriter(path, method);
                    }
                    invokeInstanceWithNumber(method, holder, anchor);
                    // 这个方法确实动了血量但不是“按值写入”，换下一个
                    if (!Float.isNaN(observed) && !matches(observed, anchor)) break;
                }
            }
        }
        return null;
    }

    /** 持有对象上的模组读方法：非 static、返回 float/double、参数不超过 2 个 */
    private static List<Method> numericReturningMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (!isModClass(current)) continue;
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) continue;
                Class<?> returns = method.getReturnType();
                if (returns != float.class && returns != double.class) continue;
                if (method.getParameterCount() > HOLDER_MAX_READER_PARAMS) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                result.add(method);
            }
        }
        return result;
    }

    /** 持有对象上的模组单参写方法：非 static、恰好一个 float/double 形参、返回 void/boolean */
    private static List<Method> singleNumberSetters(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (!isModClass(current)) continue;
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) continue;
                Class<?> returns = method.getReturnType();
                if (returns != void.class && returns != boolean.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != float.class && params[0] != double.class) continue;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                result.add(method);
            }
        }
        return result;
    }

    /** 方法各形参的取值方案组合（每种方案包含“每位取哪类来源 + 具体字段”） */
    private static List<Object[]> argSpecs(Method method, LivingEntity victim, Object holder) {
        Class<?>[] params = method.getParameterTypes();
        List<Object[]> specs = new ArrayList<>();
        specs.add(new Object[]{new int[params.length], new Field[params.length]});
        for (int i = 0; i < params.length; i++) {
            List<Object[]> choices = argChoices(params[i], victim, holder);
            if (choices.isEmpty()) return List.of();
            List<Object[]> next = new ArrayList<>();
            for (Object[] spec : specs) {
                for (Object[] choice : choices) {
                    int[] kinds = ((int[]) spec[0]).clone();
                    Field[] sources = ((Field[]) spec[1]).clone();
                    kinds[i] = (Integer) choice[0];
                    sources[i] = (Field) choice[1];
                    next.add(new Object[]{kinds, sources});
                    if (next.size() >= HOLDER_MAX_ARG_SPECS) break;
                }
                if (next.size() == HOLDER_MAX_ARG_SPECS) break;
            }
            specs = next;
        }
        return specs;
    }

    /** 某个形参的可选来源：实体定位值（实体/id/UUID）、持有者的同类型字段、实体的同类型字段 */
    private static List<Object[]> argChoices(Class<?> param, LivingEntity victim, Object holder) {
        List<Object[]> choices = new ArrayList<>();
        if (param == int.class || param == Integer.class || param == UUID.class || param.isInstance(victim)) {
            choices.add(new Object[]{ARG_IDENTITY, null});
        }
        if (!param.isPrimitive()) {
            Field onHolder = firstFieldOfType(holder.getClass(), param);
            if (onHolder != null) choices.add(new Object[]{ARG_HOLDER_FIELD, onHolder});
            Field onVictim = firstFieldOfType(victim.getClass(), param);
            if (onVictim != null) choices.add(new Object[]{ARG_ENTITY_FIELD, onVictim});
        }
        return choices;
    }

    /** 继承链上第一个类型匹配的字段（含 static/final，如实体持有的密钥表） */
    private static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> current : hierarchy(owner)) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || field.getType() != type) continue;
                try {
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                    // 字段不可访问则继续
                }
            }
        }
        return null;
    }

    /** 按读口记录的来源补齐实参（定位值随实体变化；字段值从实体/持有对象当前状态取） */
    private static Object[] resolveArgs(HolderReader reader, LivingEntity victim, Object holder) {
        Class<?>[] params = reader.method().getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            int kind = reader.kinds()[i];
            if (kind == ARG_IDENTITY) {
                args[i] = identityArg(params[i], victim);
                continue;
            }
            Object owner = kind == ARG_HOLDER_FIELD ? holder : victim;
            if (owner == null || reader.sources()[i] == null) return null;
            try {
                args[i] = reader.sources()[i].get(owner);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return args;
    }

    /** 读口取值（读不出返回 NaN） */
    private static float readViaHolderReader(LivingEntity victim, HolderReader reader) {
        Object holder = resolveHolder(victim, reader.path());
        if (holder == null) return Float.NaN;
        Object[] args = resolveArgs(reader, victim, holder);
        if (args == null) return Float.NaN;
        Double value = invokeForNumber(reader.method(), holder, args);
        return value == null ? Float.NaN : value.floatValue();
    }

    /** 按字段路径从实体解析出持有对象 */
    private static Object resolveHolder(LivingEntity victim, List<Field> path) {
        Object node = victim;
        for (Field field : path) {
            if (node == null) return null;
            try {
                node = field.get(node);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return node;
    }

    /** 调用“单参 float/double”方法（实例方法，target 为持有对象） */
    private static boolean invokeInstanceWithNumber(Method method, Object target, double value) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1) return false;
        try {
            method.invoke(target, boxNumber(params[0], value));
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 类继承链（本类 → 父类，不含 {@code Object}），按类缓存 */
    private static List<Class<?>> hierarchy(Class<?> clazz) {
        return CLASS_HIERARCHY_CACHE.computeIfAbsent(clazz, type -> {
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                chain.add(current);
            }
            return chain;
        });
    }

    /** 继承链缓存（只存类，可跨 tick 复用） */
    private static final Map<Class<?>, List<Class<?>>> CLASS_HIERARCHY_CACHE = new ConcurrentHashMap<>();

    /** 无参反射调用的实参（复用空数组） */
    private static final Object[] NO_ARGS = new Object[0];

    /** 反射调用并返回结果：静态方法 {@code target} 传 null；失败或返回 void 时返回 null */
    private static Object invokeObject(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    private static boolean invokeQuietly(Method method, Object[] args) {
        try {
            method.invoke(null, args);
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 反射调用取数值：失败或返回值不是数值时返回 null */
    private static Double invokeForNumber(Method method, Object target, Object[] args) {
        return invokeObject(method, target, args) instanceof Number number ? number.doubleValue() : null;
    }

    private static float healthOf(LivingEntity victim) {
        try {
            return victim.getHealth();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Float.NaN;
        }
    }

    private static float maxHealthOf(LivingEntity victim) {
        try {
            return victim.getMaxHealth();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Float.NaN;
        }
    }

    // ===== 「代管血量」核心类的候选来源（影子层与权威层共用，档 3）=====

    /** 模组文件路径 → 从该文件字节码里收割到的候选类名。文件只解析一次 */
    private static final Map<String, List<String>> MOD_SEED_NAME_CACHE = new ConcurrentHashMap<>();
    /** 模组文件路径 → 候选类及其嵌套类。空结果不缓存 */
    private static final Map<String, List<Class<?>>> MOD_SEED_EXPANSION_CACHE = new ConcurrentHashMap<>();

    /** 收割预算：读取的 class 字节总量 / 候选类名总数 / 目录递归深度 / 嵌套类展开上限 */
    private static final long MOD_SEED_MAX_BYTES = 64 * 1024 * 1024;
    private static final int MOD_SEED_MAX_CLASSES = 4096;
    private static final int MOD_SEED_MAX_DEPTH = 32;
    private static final int MOD_SEED_NESTED_MAX = 8192;

    /** 影子层 / 权威层共用的候选类集合：实体常量池可达类 + 模组文件收割到的类名 */
    private static List<Class<?>> healthCoreCandidates(LivingEntity victim) {
        Set<Class<?>> candidates = new LinkedHashSet<>(referencedNestedClasses(victim.getClass()));
        candidates.addAll(seedClassExpansion(victim));
        return new ArrayList<>(candidates);
    }

    /** 收割到的种子类 + 其嵌套类 */
    private static List<Class<?>> seedClassExpansion(LivingEntity victim) {
        Path path = modFilePath(victim);
        if (path == null) return List.of();
        String key = path.toString();
        List<Class<?>> cached = MOD_SEED_EXPANSION_CACHE.get(key);
        if (cached != null) return cached;
        List<String> names = MOD_SEED_NAME_CACHE.computeIfAbsent(key, ignored -> harvestSeedNames(path));
        if (names.isEmpty()) return List.of();
        ClassLoader loader = victim.getClass().getClassLoader();
        List<Class<?>> seeds = new ArrayList<>();
        for (String name : names) {
            Class<?> clazz = loadClass(name.replace('/', '.'), loader);
            if (clazz != null) seeds.add(clazz);
        }
        // 空结果不缓存
        if (seeds.isEmpty()) return List.of();
        List<Class<?>> expanded = withNestedClasses(seeds, MOD_SEED_NESTED_MAX);
        MOD_SEED_EXPANSION_CACHE.put(key, expanded);
        return expanded;
    }

    /** 实体所属模组的文件路径；取不到返回 null */
    private static Path modFilePath(LivingEntity victim) {
        try {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
            if (key == null || isBuiltinNamespace(key.getNamespace())) return null;
            IModFileInfo info = ModList.get().getModFileById(key.getNamespace());
            IModFile file = info == null ? null : info.getFile();
            return file == null ? null : file.getFilePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 游戏本体命名空间不做收割 */
    private static boolean isBuiltinNamespace(String namespace) {
        return "minecraft".equals(namespace) || "forge".equals(namespace);
    }

    /** 读取模组文件里的所有 class 条目，收割「常量池类引用 + 类名形态的字符串常量」 */
    private static List<String> harvestSeedNames(Path path) {
        Set<String> names = new LinkedHashSet<>();
        long[] budget = {MOD_SEED_MAX_BYTES};
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path, MOD_SEED_MAX_DEPTH)) {
                    Iterator<Path> entries = walk.iterator();
                    while (entries.hasNext() && canHarvest(names, budget)) {
                        Path entry = entries.next();
                        if (!entry.toString().endsWith(".class")) continue;
                        byte[] bytes;
                        try {
                            bytes = Files.readAllBytes(entry);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        collectSeedNames(bytes, names, budget);
                    }
                }
            } else {
                // 模组文件容器（.jar / .zip）
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements() && canHarvest(names, budget)) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                        byte[] bytes;
                        try (InputStream stream = zip.getInputStream(entry)) {
                            bytes = stream.readAllBytes();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        collectSeedNames(bytes, names, budget);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 文件不可读/格式不符时该种子源整体跳过
        }
        return List.copyOf(names);
    }

    private static boolean canHarvest(Set<String> names, long[] budget) {
        return names.size() < MOD_SEED_MAX_CLASSES && budget[0] > 0;
    }

    /** 解析单个 class 文件，把其中的模组类名收进候选集（按读取字节数扣预算） */
    private static void collectSeedNames(byte[] bytes, Set<String> names, long[] budget) {
        if (bytes == null || bytes.length == 0 || !canHarvest(names, budget)) return;
        budget[0] -= bytes.length;
        for (String name : parseClassNames(bytes, true)) {
            if (names.size() >= MOD_SEED_MAX_CLASSES) return;
            if (name.startsWith("[")) continue;
            if (!isModClassName(name.replace('/', '.'))) continue;
            names.add(name);
        }
    }

    // ===== 影子血量单例写入（档 3）：把 uuid 写进单例的 Map/Set 字段 =====

    /** 影子单例方案（只存类级：单例访问器 + 容器字段） */
    private record ShadowPlan(Field singletonField, Method singletonFactory, Field trackedSet,
                              Field healthMap, List<Field> dieGates, List<Field> removeGates) {
    }

    private static final Map<Class<?>, List<ShadowPlan>> SHADOW_PLAN_CACHE = new ConcurrentHashMap<>();
    private static final int SHADOW_MAX_PLANS = 4;
    private static final int SHADOW_MAX_CONTAINERS = 32;
    /** 用影子单例的容器字段把血量改成目标值；未写到位则整体还原 */
    private static void writeViaShadowStore(LivingEntity victim, float healthAfter) {
        List<ShadowPlan> plans = shadowPlans(victim);
        for (ShadowPlan plan : plans) {
            Object shadow = resolveSingleton(plan.singletonField(), plan.singletonFactory());
            if (shadow == null) continue;
            Object mapObject = readFieldObject(plan.healthMap(), shadow);
            if (!(mapObject instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<Object, Object> healthMap = (Map<Object, Object>) mapObject;
            UUID uuid = victim.getUUID();
            Object previous = healthMap.get(uuid);
            boolean had = healthMap.containsKey(uuid);
            boolean tracked = addToSet(plan.trackedSet(), shadow, uuid);
            try {
                healthMap.put(uuid, healthAfter);
            } catch (Throwable ignored) {
                if (tracked) removeFromSet(plan.trackedSet(), shadow, uuid);
                continue;
            }

            if (healthAfter <= 0.0F) {
                addAllToSets(plan.dieGates(), shadow, uuid);
            }
            // getHealth() 是本层唯一的校验依据
            float now = healthOf(victim);
            if (matches(now, healthAfter)) {
                probeLog("影子层写入生效：目标={} getHealth={}", healthAfter, now);
                return;
            }
            probeLog("影子层未生效，已还原：目标={} getHealth={}", healthAfter, now);
            // 没写到位就整体还原
            try {
                if (had) healthMap.put(uuid, previous);
                else healthMap.remove(uuid);
            } catch (Throwable ignored) {
                // 还原尽力而为
            }
            if (tracked) removeFromSet(plan.trackedSet(), shadow, uuid);
            removeAllFromSets(plan.dieGates(), shadow, uuid);
        }
    }

    private static List<ShadowPlan> shadowPlans(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<ShadowPlan> cached = SHADOW_PLAN_CACHE.get(type);
        if (cached != null) return cached;
        List<ShadowPlan> found;
        try {
            found = discoverShadowPlans(victim);
        } catch (Throwable t) {
            return List.of();
        }
        if (!found.isEmpty()) SHADOW_PLAN_CACHE.put(type, found);
        return found;
    }

    private static List<ShadowPlan> discoverShadowPlans(LivingEntity victim) {
        // 锚点用真实血量
        float anchor = currentHealth(victim);
        if (Float.isNaN(anchor) || anchor <= 0.0F) return List.of();
        List<Class<?>> candidates = healthCoreCandidates(victim);
        List<ShadowPlan> plans = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            if (plans.size() >= SHADOW_MAX_PLANS) break;
            if (isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            List<Object[]> accessors = singletonAccessors(clazz);
            if (accessors.isEmpty()) continue;
            for (Object[] accessor : accessors) {
                ShadowPlan plan = probeShadow(victim, accessor);
                if (plan != null) {
                    plans.add(plan);
                    break;   // 一个单例只取一个方案
                }
            }
        }
        return plans;
    }

    /** 静态单例访问器：静态字段（类型可实现本类）或公开静态无参工厂（返回本类） */
    private static List<Object[]> singletonAccessors(Class<?> clazz) {
        List<Object[]> accessors = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) continue;
            if (!clazz.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            accessors.add(new Object[]{field, null});
        }
        for (Method method : clazz.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isStatic(modifiers) || method.isSynthetic() || method.getParameterCount() != 0) continue;
            if (!clazz.isAssignableFrom(method.getReturnType())) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            accessors.add(new Object[]{null, method});
        }
        return accessors;
    }

    private static Object resolveSingleton(Field field, Method factory) {
        try {
            return field != null ? field.get(null) : factory != null ? factory.invoke(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 行为验证某个单例是不是「影子血量表」：找 (Set, Map) 组合与生死门控 Set */
    private static ShadowPlan probeShadow(LivingEntity victim, Object[] accessor) {
        Object shadow = resolveSingleton((Field) accessor[0], (Method) accessor[1]);
        if (shadow == null) return null;
        List<Field> sets = containerFields(shadow.getClass(), Set.class);
        List<Field> maps = containerFields(shadow.getClass(), Map.class);
        if (sets.isEmpty() || maps.isEmpty()) return null;
        float healthBefore = healthOf(victim);
        if (Float.isNaN(healthBefore) || healthBefore <= 0.0F) return null;
        // 用「独有值」而不是 0 当探针
        float probe = Math.max(1.0F, Math.min(7.25F, healthBefore * 0.5F));
        UUID uuid = victim.getUUID();
        for (Field setField : sets) {
            if (!ensureInSet(setField, shadow, uuid)) continue;
            Field healthMap = null;
            // 单表试：把 uuid 加进 Set，逐个把 Map 的该键写成 probe
            for (Field mapField : maps) {
                Object[] snapshot = snapshotMap(mapField, shadow, uuid);
                if (snapshot == null || !setMapValue(mapField, shadow, uuid, probe)) continue;
                float now = healthOf(victim);
                restoreMap(snapshot, shadow, uuid);
                if (Math.abs(now - probe) <= PROBE_EPSILON) {
                    healthMap = mapField;
                    break;
                }
            }
            // 多表同时写：单表试不出来时再试
            if (healthMap == null) {
                List<Object[]> snapshots = new ArrayList<>();
                boolean allSet = true;
                for (Field mapField : maps) {
                    Object[] snapshot = snapshotMap(mapField, shadow, uuid);
                    if (snapshot == null || !setMapValue(mapField, shadow, uuid, probe)) {
                        allSet = false;
                        break;
                    }
                    snapshots.add(snapshot);
                }
                float now = healthOf(victim);
                if (allSet && Math.abs(now - probe) <= PROBE_EPSILON) {
                    // 逐个还原以定位真正承载血量的那张表
                    for (Object[] snapshot : snapshots) {
                        restoreMap(snapshot, shadow, uuid);
                        if (Math.abs(healthOf(victim) - probe) > PROBE_EPSILON) {
                            healthMap = (Field) snapshot[0];
                            setMapValue(healthMap, shadow, uuid, probe);
                            break;
                        }
                    }
                }
                for (Object[] snapshot : snapshots) {
                    restoreMap(snapshot, shadow, uuid);
                }
            }
            if (healthMap == null) {
                removeFromSet(setField, shadow, uuid);
                continue;
            }
            // 找「生死门控」：加入 uuid 后 isDeadOrDying / isRemoved 是否翻转
            boolean deadBefore = victim.isDeadOrDying();
            boolean removedBefore = victim.isRemoved();
            List<Field> dieGates = new ArrayList<>();
            List<Field> removeGates = new ArrayList<>();
            for (Field gateField : sets) {
                if (gateField == setField) continue;
                if (ensureInSet(gateField, shadow, uuid)) {
                    try {
                        if (!deadBefore && victim.isDeadOrDying()) dieGates.add(gateField);
                        else if (!removedBefore && victim.isRemoved()) removeGates.add(gateField);
                    } catch (Throwable ignored) {
                        // 查询失败则跳过
                    }
                    removeFromSet(gateField, shadow, uuid);
                }
            }
            removeFromSet(setField, shadow, uuid);
            return new ShadowPlan((Field) accessor[0], (Method) accessor[1],
                    setField, healthMap, dieGates, removeGates);
        }
        return null;
    }

    /** 取 Set 字段的集合视图；字段不存在 / 不是 Set 时返回 null */
    @SuppressWarnings("unchecked")
    private static Set<Object> setOf(Field setField, Object target) {
        Object setObject = setField == null ? null : readFieldObject(setField, target);
        return setObject instanceof Set<?> ? (Set<Object>) setObject : null;
    }

    /** 确保值在集合里（本来就在也算成功） */
    private static boolean ensureInSet(Field setField, Object target, Object value) {
        Set<Object> set = setOf(setField, target);
        if (set == null) return false;
        try {
            set.add(value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Map 字段的当前值快照：{Field, 原值是否存在, 原值}；非 Map 返回 null */
    private static Object[] snapshotMap(Field mapField, Object target, UUID key) {
        Object mapObject = readFieldObject(mapField, target);
        if (!(mapObject instanceof Map<?, ?> map)) return null;
        return new Object[]{mapField, map.containsKey(key), map.get(key)};
    }

    @SuppressWarnings("unchecked")
    private static boolean setMapValue(Field mapField, Object target, UUID key, float value) {
        Object mapObject = readFieldObject(mapField, target);
        if (!(mapObject instanceof Map<?, ?>)) return false;
        try {
            ((Map<Object, Object>) mapObject).put(key, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreMap(Object[] snapshot, Object target, UUID key) {
        if (snapshot == null) return;
        Object mapObject = readFieldObject((Field) snapshot[0], target);
        if (!(mapObject instanceof Map<?, ?>)) return;
        Map<Object, Object> map = (Map<Object, Object>) mapObject;
        try {
            if ((Boolean) snapshot[1]) map.put(key, snapshot[2]);
            else map.remove(key);
        } catch (Throwable ignored) {
            // 还原尽力而为
        }
    }

    /** 类继承链上指定容器类型的实例字段（已 setAccessible），数量有上限 */
    private static List<Field> containerFields(Class<?> clazz, Class<?> kind) {
        List<Field> found = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                if (!kind.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                found.add(field);
                if (found.size() >= SHADOW_MAX_CONTAINERS) return found;
            }
        }
        return found;
    }

    private static Object readFieldObject(Field field, Object target) {
        try {
            return field.get(target);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 加入集合并返回「是否真的新增」——调用方据此决定还原时要不要移除 */
    private static boolean addToSet(Field setField, Object target, Object value) {
        Set<Object> set = setOf(setField, target);
        if (set == null) return false;
        try {
            return set.add(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void removeFromSet(Field setField, Object target, Object value) {
        Set<Object> set = setOf(setField, target);
        if (set == null) return;
        try {
            set.remove(value);
        } catch (Throwable ignored) {
            // 还原尽力而为
        }
    }

    private static void addAllToSets(List<Field> fields, Object target, Object value) {
        for (Field field : fields) {
            addToSet(field, target, value);
        }
    }

    private static void removeAllFromSets(List<Field> fields, Object target, Object value) {
        for (Field field : fields) {
            removeFromSet(field, target, value);
        }
    }

    // ===== 校验(MAC)保护的静态权威记录血量（档 3）：复算 MAC 后整体替换记录 =====

    private static final MacAuthorityPlan NO_MAC_AUTHORITY =
            new MacAuthorityPlan(null, null, null, null, null, null, null, null);
    private static final Map<Class<?>, MacAuthorityPlan> MAC_AUTHORITY_CACHE = new ConcurrentHashMap<>();

    private record MacAuthorityPlan(Field store, Constructor<?> constructor, Method encrypt, Method mac,
                                    Field stringField, Field maxField, Field tickField, Field macField) {
    }

    private static void writeViaMacAuthority(LivingEntity victim, float healthAfter, DamageSource source) {
        MacAuthorityPlan plan = macAuthorityPlan(victim);
        if (plan == null) return;
        Object mapObject;
        Object current;
        try {
            mapObject = plan.store().get(null);
            if (!(mapObject instanceof Map<?, ?> raw)) return;
            current = raw.get(victim);
        } catch (Throwable ignored) {
            return;
        }
        if (current == null) return;
        @SuppressWarnings("unchecked")
        Map<Object, Object> store = (Map<Object, Object>) mapObject;
        try {
            String encoded = (String) plan.encrypt().invoke(null, healthAfter);
            if (encoded == null) return;
            float max = plan.maxField().getFloat(current);
            // 致死时把时间戳写成 -1，跳过模组自己的无敌帧
            long tick = healthAfter <= 0.0F ? -1L : plan.tickField().getLong(current);
            long mac = ((Number) plan.mac().invoke(null, encoded, max, tick)).longValue();
            store.put(victim, plan.constructor().newInstance(encoded, max, tick, mac));
        } catch (Throwable ignored) {
            return;
        }
        float now = healthOf(victim);
        if (!matches(now, healthAfter)) {
            probeLog("MAC 权威记录层未生效：目标={} getHealth={}", healthAfter, now);
            return;
        }
        probeLog("MAC 权威记录层写入生效：目标={} getHealth={}", healthAfter, now);
        // 致死：血量归零后再走一次它自己的 hurt，让模组自己的流程完成授权与清理
        if (healthAfter <= 0.0F && source != null) {
            PENDING_FORCED_DEATH.add(victim);
            try {
                victim.hurt(source, Math.max(1.0F, plan.maxField().getFloat(current)));
            } catch (Throwable ignored) {
                // 模组的 hurt 拒绝时保持现状
            } finally {
                PENDING_FORCED_DEATH.remove(victim);
            }
        }
    }

    private static MacAuthorityPlan macAuthorityPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        MacAuthorityPlan cached = MAC_AUTHORITY_CACHE.get(type);
        if (cached != null) return cached == NO_MAC_AUTHORITY ? null : cached;
        MacAuthorityPlan found = null;
        try {
            found = discoverMacAuthority(victim);
        } catch (Throwable ignored) {
            // 探测失败留待下次
        }
        if (found != null) MAC_AUTHORITY_CACHE.put(type, found);
        return found;
    }

    private static MacAuthorityPlan discoverMacAuthority(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        List<Class<?>> candidates = healthCoreCandidates(victim);
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            for (Field field : clazz.getDeclaredFields()) {
                if (!isStaticMapField(field)) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                Object value;
                try {
                    Object mapObject = field.get(null);
                    if (!(mapObject instanceof Map<?, ?> map)) continue;
                    value = map.get(victim);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) continue;
                MacAuthorityPlan plan = buildMacPlan(field, value, candidates, health);
                if (plan != null) return plan;
            }
        }
        return null;
    }

    private static MacAuthorityPlan buildMacPlan(Field store, Object value, List<Class<?>> candidates, float health) {
        Class<?> valueType = value.getClass();
        Field stringField = null;
        Field maxField = null;
        List<Field> longFields = new ArrayList<>(2);
        for (Field field : valueType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            if (field.getType() == String.class) {
                if (stringField == null) stringField = field;
            } else if (field.getType() == float.class) {
                if (maxField == null) maxField = field;
            } else if (field.getType() == long.class) {
                longFields.add(field);
            }
        }
        if (stringField == null || maxField == null || longFields.size() != 2) return null;

        String encrypted;
        float max;
        long first;
        long second;
        try {
            encrypted = (String) stringField.get(value);
            max = maxField.getFloat(value);
            first = longFields.get(0).getLong(value);
            second = longFields.get(1).getLong(value);
        } catch (Throwable ignored) {
            return null;
        }
        if (encrypted == null || !Float.isFinite(max)) return null;

        // 编码器：编码(当前血量) 应正好等于记录的密文串
        Method encrypt = null;
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                if (method.getParameterCount() != 1 || method.getReturnType() != String.class) continue;
                if (method.getParameterTypes()[0] != float.class) continue;
                try {
                    if (method.invoke(null, health) instanceof String text && text.equals(encrypted)) {
                        encrypt = method;
                        break;
                    }
                } catch (Throwable ignored) {
                    // 该方法不适用，继续
                }
            }
            if (encrypt != null) break;
        }
        if (encrypt == null) return null;

        // 校验器：(密文, float, long) -> long，第三参取哪个 long 能算出另一个 long
        Method mac = null;
        Field tickField = null;
        Field macField = null;
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                if (method.getParameterCount() != 3 || method.getReturnType() != long.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params[0] != String.class || params[1] != float.class || params[2] != long.class) continue;
                try {
                    if (((Number) method.invoke(null, encrypted, max, first)).longValue() == second) {
                        mac = method;
                        tickField = longFields.get(0);
                        macField = longFields.get(1);
                        break;
                    }
                    if (((Number) method.invoke(null, encrypted, max, second)).longValue() == first) {
                        mac = method;
                        tickField = longFields.get(1);
                        macField = longFields.get(0);
                        break;
                    }
                } catch (Throwable ignored) {
                    // 该方法不适用，继续
                }
            }
            if (mac != null) break;
        }
        if (mac == null) return null;

        // 记录类的规范构造器 (String, float, long, long)
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : valueType.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 4 && params[0] == String.class && params[1] == float.class
                    && params[2] == long.class && params[3] == long.class) {
                try {
                    candidate.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) return null;

        return new MacAuthorityPlan(store, constructor, encrypt, mac, stringField, maxField, tickField, macField);
    }

    // ===== 模组权威值「密文互换」直写（档 3，致死专用）=====

    private record AuthorityPlan(Method entryAccessor, Field accField, Field refField, Field lastField,
                                 Method reader, Method decoder, Method setter, Method lastSetter) {
    }

    private static final Map<Class<?>, List<AuthorityPlan>> AUTHORITY_PLAN_CACHE = new ConcurrentHashMap<>();
    /** 已为某个实体类强制尝试过一次权威按量写入 */
    private static final Set<Class<?>> AUTHORITY_PROBED = ConcurrentHashMap.newKeySet();
    private static final int AUTHORITY_MAX_PLANS = 4;
    private static final int AUTHORITY_MAX_READERS = 32;
    /** 权威累计值写入方法单次允许的最大增量 */
    private static final float AUTHORITY_WRITE_STEP = 1000.0F;

    /** 把模组权威值直接压到 0（之后交给模组自己的死亡/移除流程） */
    private static void writeViaModAuthority(LivingEntity victim) {
        for (AuthorityPlan plan : authorityPlans(victim)) {
            Object entry = invokeObject(plan.entryAccessor(), null, new Object[]{victim});
            if (entry == null) continue;
            Object previous = readFieldObject(plan.accField(), entry);
            try {
                plan.accField().set(entry, readFieldObject(plan.refField(), entry));
            } catch (Throwable ignored) {
                continue;
            }
            float now = readEntityNumber(plan.reader(), victim);
            if (!Float.isNaN(now) && Math.abs(now) <= PROBE_EPSILON) {
                probeLog("权威密文互换生效（不可逆）：{} 的权威血量已归零", victim.getClass().getName());
                // 同时把「上一帧累计值」推到同一水平
                if (plan.decoder() != null) {
                    float ceiling = decodeAuthority(plan.decoder(), readFieldObject(plan.accField(), entry));
                    if (!Float.isNaN(ceiling)) pushAuthorityLast(victim, plan, entry, ceiling);
                }
                return;
            }
            try {
                plan.accField().set(entry, previous);
            } catch (Throwable ignored) {
                // 还原尽力而为
            }
        }
    }

    /** 非致死：用模组自己的权威「累计值写入方法」把累计值按真伤量增加 */
    private static void writeViaModAuthorityDelta(LivingEntity victim, float targetHealth) {
        String type = victim.getClass().getName();
        List<AuthorityPlan> plans = authorityPlans(victim);
        if (plans.isEmpty()) {
            probeLog("权威按量写入放弃（{}）：一个权威方案都没探到", type);
            return;
        }
        for (AuthorityPlan plan : plans) {
            if (plan.decoder() == null || plan.setter() == null) {
                probeLog("权威按量写入放弃（{}）：方案缺件（解码方法={}、写入方法={}）",
                        type, plan.decoder() != null, plan.setter() != null);
                continue;
            }
            Object entry = invokeObject(plan.entryAccessor(), null, new Object[]{victim});
            if (entry == null) {
                probeLog("权威按量写入放弃（{}）：取不到权威条目", type);
                continue;
            }
            float max = decodeAuthority(plan.decoder(), readFieldObject(plan.refField(), entry));
            float acc = decodeAuthority(plan.decoder(), readFieldObject(plan.accField(), entry));
            if (Float.isNaN(max) || Float.isNaN(acc) || max <= 0.0F) {
                probeLog("权威按量写入放弃（{}）：解码异常（上限={}、累计={}）", type, max, acc);
                continue;
            }
            // 目标按「权威上限 − 目标血量」现算
            float target = Math.max(0.0F, Math.min(max, max - targetHealth));
            if (target <= acc + PROBE_EPSILON) {
                probeLog("权威按量写入放弃（{}）：当前累计={} 已达目标={}（权威上限={}）", type, acc, target, max);
                continue;
            }
            float current = acc;
            boolean written = true;
            while (current + PROBE_EPSILON < target) {
                float next = Math.min(target, current + AUTHORITY_WRITE_STEP);
                if (!invokeQuietly(plan.setter(), new Object[]{victim, next, 1.0F})) {
                    probeLog("权威按量写入放弃（{}）：写入方法调用失败（累计 {} → {}）", type, current, next);
                    written = false;
                    break;
                }
                current = next;
            }
            if (!written) continue;
            // 同时把「上一帧累计值」推到同一水平
            pushAuthorityLast(victim, plan, entry, current);
            float now = readEntityNumber(plan.reader(), victim);
            if (!Float.isNaN(now) && now <= targetHealth + PROBE_EPSILON) {
                probeLog("权威累计值按量写入生效：{} 权威血量 {} → {}（目标 {}）",
                        type, max - acc, now, targetHealth);
                return;
            }
            probeLog("权威按量写入未达目标（{}）：权威血量 {} → {}（目标 {}，写入值 {}）",
                    type, max - acc, now, targetHealth, current);
        }
    }

    /** 用密文解码方法把密文还原成数值；解码不出来时返回 NaN */
    private static float decodeAuthority(Method decoder, Object cipher) {
        if (cipher == null) return Float.NaN;
        Double value = invokeForNumber(decoder, cipher, new Object[0]);
        return value == null ? Float.NaN : value.floatValue();
    }

    /** 把「上一帧累计值」推到 {@code target} */
    private static void pushAuthorityLast(LivingEntity victim, AuthorityPlan plan, Object entry, float target) {
        if (plan.lastSetter() == null || plan.lastField() == null || plan.decoder() == null) return;
        float current = decodeAuthority(plan.decoder(), readFieldObject(plan.lastField(), entry));
        if (Float.isNaN(current)) return;
        while (current + PROBE_EPSILON < target) {
            float next = Math.min(target, current + AUTHORITY_WRITE_STEP);
            if (!invokeQuietly(plan.lastSetter(), new Object[]{victim, next, 1.0F})) {
                probeLog("权威上一帧累计值写入失败（{}）：{} → {}", victim.getClass().getName(), current, next);
                return;
            }
            float observed = decodeAuthority(plan.decoder(), readFieldObject(plan.lastField(), entry));
            if (Float.isNaN(observed) || observed <= current + PROBE_EPSILON) {
                probeLog("权威上一帧累计值写入未推进（{}）：{} → {}（目标 {}）",
                        victim.getClass().getName(), current, observed, target);
                return;
            }
            current = observed;
        }
    }

    /** 是否值得走权威按量写入：有权威方案的类每击都补，没见过的类首击强制试一次 */
    private static boolean shouldWriteModAuthority(LivingEntity victim, float healthAfter) {
        if (stillAboveTarget(victim, healthAfter)) return true;
        if (AUTHORITY_PLAN_CACHE.containsKey(victim.getClass())) return true;
        return AUTHORITY_PROBED.add(victim.getClass());
    }

    private static List<AuthorityPlan> authorityPlans(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<AuthorityPlan> cached = AUTHORITY_PLAN_CACHE.get(type);
        if (cached != null) return cached;
        List<AuthorityPlan> found;
        try {
            found = discoverAuthorityPlans(victim);
        } catch (Throwable t) {
            return List.of();
        }
        if (!found.isEmpty()) AUTHORITY_PLAN_CACHE.put(type, found);
        return found;
    }

    private static List<AuthorityPlan> discoverAuthorityPlans(LivingEntity victim) {
        float anchor = currentHealth(victim);
        if (Float.isNaN(anchor) || anchor <= 0.0F) return List.of();
        List<Class<?>> candidates = healthCoreCandidates(victim);
        List<AuthorityPlan> plans = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            if (plans.size() >= AUTHORITY_MAX_PLANS) break;
            if (isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            List<Method> readers = entityNumberReaders(clazz);
            if (readers.isEmpty()) continue;
            for (Method accessor : entityObjectAccessors(clazz)) {
                AuthorityPlan plan = probeAuthority(victim, accessor, readers, anchor);
                if (plan != null) {
                    plans.add(plan);
                    break;
                }
            }
        }
        return plans;
    }

    /** 类上「单参 Entity、返回 float/double」的静态读方法 */
    private static List<Method> entityNumberReaders(Class<?> clazz) {
        List<Method> found = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != Entity.class) continue;
            Class<?> returns = method.getReturnType();
            if (returns != float.class && returns != double.class) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            found.add(method);
            if (found.size() >= AUTHORITY_MAX_READERS) break;
        }
        return found;
    }

    /** 类上「单参 Entity、返回对象」的静态方法（用于取权威条目对象） */
    private static List<Method> entityObjectAccessors(Class<?> clazz) {
        List<Method> found = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != Entity.class) continue;
            Class<?> returns = method.getReturnType();
            if (returns.isPrimitive() || returns == void.class) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            found.add(method);
        }
        return found;
    }

    /** 行为验证：同类型下「非 final 密文字段 ← final 密文字段」，若某个读方法随之变成 0 则认下 */
    private static AuthorityPlan probeAuthority(LivingEntity victim, Method accessor,
                                               List<Method> readers, float anchor) {
        Object entry = invokeObject(accessor, null, new Object[]{victim});
        if (entry == null) return null;
        // 只认「原值 > 0 且变为 0」的读方法
        List<Float> baseline = new ArrayList<>(readers.size());
        for (Method reader : readers) {
            baseline.add(readEntityNumber(reader, victim));
        }
        Map<Class<?>, List<Field>> byType = new ConcurrentHashMap<>();
        for (Class<?> current : hierarchy(entry.getClass())) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                String typeName = type.getName();
                if (type.isPrimitive() || typeName.startsWith("java.") || typeName.startsWith("javax.")
                        || Entity.class.isAssignableFrom(type)) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                byType.computeIfAbsent(type, key -> new ArrayList<>()).add(field);
            }
        }
        for (List<Field> group : byType.values()) {
            if (group.size() < 2) continue;
            List<Field> refs = new ArrayList<>();
            List<Field> mutable = new ArrayList<>();
            for (Field field : group) {
                if (Modifier.isFinal(field.getModifiers())) refs.add(field);
                else mutable.add(field);
            }
            for (Field ref : refs) {
                for (Field acc : mutable) {
                    Object reference = readFieldObject(ref, entry);
                    if (reference == null) continue;
                    Object previous = readFieldObject(acc, entry);
                    try {
                        acc.set(entry, reference);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    boolean hit = false;
                    Method matched = null;
                    for (int i = 0; i < readers.size(); i++) {
                        float observed = readEntityNumber(readers.get(i), victim);
                        if (Float.isNaN(observed)) continue;
                        if (baseline.get(i) > PROBE_EPSILON && anchor > 0.0F
                                && Math.abs(observed) <= PROBE_EPSILON) {
                            hit = true;
                            matched = readers.get(i);
                            break;
                        }
                    }
                    try {
                        acc.set(entry, previous);
                    } catch (Throwable ignored) {
                        // 还原尽力而为
                    }
                    if (hit) {
                        // 同类型的另一个非 final 密文字段就是「上一帧累计值」
                        Field counterpart = null;
                        for (Field other : mutable) {
                            if (other != acc) {
                                counterpart = other;
                                break;
                            }
                        }
                        return enrichAuthorityPlan(victim,
                                new AuthorityPlan(accessor, acc, ref, counterpart, matched, null, null, null));
                    }
                }
            }
        }
        return null;
    }

    /** 在已命中的权威方案上补出「密文解码方法」与「权威累计值写入方法」 */
    private static AuthorityPlan enrichAuthorityPlan(LivingEntity victim, AuthorityPlan plan) {
        String type = victim.getClass().getName();
        Object entry = invokeObject(plan.entryAccessor(), null, new Object[]{victim});
        if (entry == null) return plan;
        Object refCipher = readFieldObject(plan.refField(), entry);
        Object accCipher = readFieldObject(plan.accField(), entry);
        float authority = readEntityNumber(plan.reader(), victim);
        if (refCipher == null || accCipher == null || Float.isNaN(authority)) {
            probeLog("权威方案补全失败（{}）：密文缺失或无权威读数", type);
            return plan;
        }
        Method decoder = null;
        int tried = 0;
        for (Class<?> source : new Class<?>[]{plan.accField().getType(), accCipher.getClass()}) {
            if (decoder != null) break;
            for (Method method : source.getMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) continue;
                Class<?> returns = method.getReturnType();
                if (returns != float.class && returns != double.class) continue;
                tried++;
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                float max = decodeAuthority(method, refCipher);
                float acc = decodeAuthority(method, accCipher);
                if (Float.isNaN(max) || Float.isNaN(acc) || max <= 0.0F) continue;
                if (matches(max - acc, authority)) {
                    decoder = method;
                    break;
                }
            }
        }
        if (decoder == null) {
            probeLog("权威方案补全失败（{}）：{} 个无参数值方法里没有一个能对上「上限 − 累计 = 权威读方法 = {}」",
                    type, tried, authority);
            return plan;
        }
        AuthoritySetters setters = authoritySetters(victim, plan, decoder, entry);
        if (setters.acc() == null) {
            probeLog("权威方案补全失败（{}）：权威站上没有通过行为验证的累计值写入方法", type);
        }
        return new AuthorityPlan(plan.entryAccessor(), plan.accField(), plan.refField(), plan.lastField(),
                plan.reader(), decoder, setters.acc(), setters.last());
    }

    /** 权威站上的两个累计值写入方法：{@code acc} 写累计值、{@code last} 写上一帧累计值 */
    private record AuthoritySetters(Method acc, Method last) {
    }

    /** 权威累计值写入方法（形如 {@code (Entity, float, float) -> void}：第二参为累计值、第三参为守卫系数） */
    private static AuthoritySetters authoritySetters(LivingEntity victim, AuthorityPlan plan, Method decoder,
                                                     Object entry) {
        float acc = decodeAuthority(decoder, readFieldObject(plan.accField(), entry));
        float last = plan.lastField() == null ? Float.NaN
                : decodeAuthority(decoder, readFieldObject(plan.lastField(), entry));
        if (Float.isNaN(acc)) return new AuthoritySetters(null, null);
        List<Method> candidates = authorityWriteCandidates(plan);
        Method accSetter = null;
        Method lastSetter = null;
        for (Method method : candidates) {
            Field written = probeWriteField(victim, method, decoder, entry, plan.accField(), acc,
                    plan.lastField(), last);
            if (written == plan.accField()) {
                if (accSetter == null) accSetter = method;
            } else if (written == plan.lastField()) {
                if (lastSetter == null) lastSetter = method;
            }
        }
        if (accSetter == null) {
            probeLog("权威写入方法探测失败（{}）：{} 上同签名的候选 {} 个，写 +1 后累计值（{}）都没变成 {}",
                    victim.getClass().getName(), plan.entryAccessor().getDeclaringClass().getName(),
                    candidates.size(), acc, acc + 1.0F);
        }
        return new AuthoritySetters(accSetter, lastSetter);
    }

    /** 权威站上「(Entity, float, float) -> void」的静态方法（写入方法及其守卫入口） */
    private static List<Method> authorityWriteCandidates(AuthorityPlan plan) {
        List<Method> found = new ArrayList<>();
        for (Method method : plan.entryAccessor().getDeclaringClass().getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3 || params[0] != Entity.class
                    || params[1] != float.class || params[2] != float.class) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            found.add(method);
        }
        return found;
    }

    /** 行为验证一次候选写入方法：调用后看哪个密文字段的解码值恰好变成 {@code acc + 1} */
    private static Field probeWriteField(LivingEntity victim, Method method, Method decoder, Object entry,
                                         Field accField, float acc, Field lastField, float last) {
        if (!invokeQuietly(method, new Object[]{victim, acc + 1.0F, 1.0F})) return null;
        float newAcc = decodeAuthority(decoder, readFieldObject(accField, entry));
        float newLast = lastField == null ? Float.NaN : decodeAuthority(decoder, readFieldObject(lastField, entry));
        if (matches(newAcc, acc + 1.0F)) {
            invokeQuietly(method, new Object[]{victim, acc, 1.0F});
            return accField;
        }
        if (!Float.isNaN(newLast) && !Float.isNaN(last) && matches(newLast, acc + 1.0F)) {
            invokeQuietly(method, new Object[]{victim, last, 1.0F});
            return lastField;
        }
        return null;
    }

    private static float readEntityNumber(Method method, LivingEntity victim) {
        Double value = method == null ? null : invokeForNumber(method, null, new Object[]{victim});
        return value == null ? Float.NaN : value.floatValue();
    }

    /** 条目类继承链上“名为 value 的非 final 引用字段”的偏移（按类缓存一次） */
    private static OptionalLong entryValueOffset(Class<?> entryClass) {
        return ENTRY_VALUE_OFFSET_CACHE.computeIfAbsent(entryClass, type -> {
            Unsafe instance = getUnsafe();
            if (instance == null) return OptionalLong.empty();
            for (Class<?> current : hierarchy(type)) {
                for (Field field : current.getDeclaredFields()) {
                    if (!"value".equals(field.getName())) continue;
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;
                    try {
                        return OptionalLong.of(instance.objectFieldOffset(field));
                    } catch (Throwable ignored) {
                        return OptionalLong.empty();
                    }
                }
            }
            return OptionalLong.empty();
        });
    }

    /** Unsafe 实例（延迟解析一次；取不到则为 null） */
    private static Unsafe getUnsafe() {
        if (!unsafeResolved) {
            unsafeResolved = true;
            try {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = (Unsafe) field.get(null);
            } catch (Throwable ignored) {
                // 取不到 Unsafe 时不影响其它路径
            }
        }
        return unsafe;
    }

    /** 直接写对象字段偏移处（不走反射 set，绕开 final / 访问检查） */
    private static void unsafePutObject(Object holder, long offset, Object value) {
        Unsafe instance = getUnsafe();
        if (instance == null) return;
        try {
            instance.putObject(holder, offset, value);
        } catch (Throwable ignored) {
        }
    }

    private static Object unsafeGetObject(Object holder, long offset) {
        Unsafe instance = getUnsafe();
        if (instance == null) return null;
        try {
            return instance.getObject(holder, offset);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===== 密文串血量层（档 3）：按标定的编解码调用式整串回写同步项 =====

    /** 存着密文的那个同步项（按实体类缓存） */
    private static final Map<Class<?>, EntityDataAccessor<?>> ENCRYPTED_HEALTH_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    /** 类文件常量池里的字符串字面量缓存（算子写键的候选来源） */
    private static final Map<Class<?>, List<String>> STRING_CONSTANT_CACHE = new ConcurrentHashMap<>();
    /** 算子写键候选上限 / 参与扫描的可达模组类上限 */
    private static final int OPERATOR_MAX_KEYS = 256;
    private static final int OPERATOR_REFERENCED_CLASSES = 64;
    /** 健康算子：持有它的字段 + 它的 {@code operate(Object[])} 方法，按实体类缓存 */
    private static final Map<Class<?>, OperatorPlan> OPERATOR_PLAN_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, String> OPERATOR_KEY_CACHE = new ConcurrentHashMap<>();
    /** 诊断用：每个实体类只完整打印一次候选清单 */
    private static final Set<Class<?>> OPERATOR_DIAGNOSED = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> CIPHER_DIAGNOSED = ConcurrentHashMap.newKeySet();

    private record OperatorPlan(Field field, Method operate) {
    }

    /** 密文串血量层入口：先试同步项密文路径，再试算子路径 */
    private static void writeViaHealthOperator(LivingEntity victim, float healthAfter) {
        probeLog("加密同步血量层进入：{} 目标={} 当前={}",
                victim.getClass().getName(), healthAfter, healthOf(victim));
        if (writeViaCipherHealth(victim, healthAfter)) return;
        writeViaOperatorEndpoint(victim, healthAfter);
    }

    /** 算子路径：字段持有 {@code operate(Object[])} 实现，按候选键逐个试写 */
    private static void writeViaOperatorEndpoint(LivingEntity victim, float healthAfter) {
        Class<?> type = victim.getClass();
        diagnoseStaticContainers(victim);
        OperatorPlan plan = OPERATOR_PLAN_CACHE.get(type);
        if (plan == null) {
            OperatorPlan found = discoverOperatorPlan(victim);
            if (found == null) {
                probeLog("第9层-算子：未定位到血量算子");
                return;
            }
            probeLog("健康算子层已定位算子：{}#{}", type.getName(), found.field().getName());
            OPERATOR_PLAN_CACHE.put(type, found);
            plan = found;
        }
        Object operator = readFieldAny(plan.field(), victim);
        if (operator == null) {
            probeLog("第9层-算子：{}#{} 取不到算子对象", type.getName(), plan.field().getName());
            return;
        }
        if (Float.isNaN(readOperatorHealth(plan, operator))) {
            probeLog("第9层-算子：算子无参调用失败（读值为 NaN），字段={}#{}",
                    type.getName(), plan.field().getName());
            return;
        }
        String cached = OPERATOR_KEY_CACHE.get(type);
        if (cached != null && tryOperatorKey(victim, plan, operator, cached, healthAfter)) return;
        for (String key : operatorKeys(type)) {
            if (key.equals(cached)) continue;
            if (tryOperatorKey(victim, plan, operator, key, healthAfter)) {
                OPERATOR_KEY_CACHE.put(type, key);
                return;
            }
        }
    }

    /** 用给定键把血量写为目标值：写后 getHealth 到位才算命中，否则把血量还原回写入前的读数 */
    private static boolean tryOperatorKey(LivingEntity victim, OperatorPlan plan, Object operator,
                                          String key, float healthAfter) {
        float baseline = readOperatorHealth(plan, operator);
        if (Float.isNaN(baseline)) return false;
        int shapeCount = operatorWriteShapes(victim, key, 0F).size();
        for (int shape = 0; shape < shapeCount; shape++) {
            for (Object payload : operatorPayloads(healthAfter)) {
                writeOperatorArgs(plan, operator, operatorWriteShapes(victim, key, payload).get(shape));
                if (!stillAboveTarget(victim, healthAfter)) {
                    probeLog("健康算子层写入生效：{} 键={} 模板={} 目标={} 载体={} getHealth={}",
                            victim.getClass().getName(), key, shape, healthAfter,
                            payload.getClass().getSimpleName(), healthOf(victim));
                    return true;
                }
                // 没生效就把血量还原成写入前的读数
                writeOperatorArgs(plan, operator,
                        operatorWriteShapes(victim, key, boxAs(payload, baseline)).get(shape));
            }
        }
        return false;
    }

    /** 按某个载体形态把数值装箱成同类型（还原与多形态尝试都用它） */
    private static Object boxAs(Object sample, float value) {
        if (sample instanceof String) return plainText(value);
        if (sample instanceof Double) return (double) value;
        if (sample instanceof Integer) return (int) value;
        return value;
    }

    /** 每个实体类只做一次「静态容器」诊断 */
    private static final Set<Class<?>> CONTAINER_DIAGNOSED = ConcurrentHashMap.newKeySet();
    /** 诊断时每张容器最多遍历多少条目 */
    private static final int CONTAINER_DIAGNOSE_LIMIT = 4096;

    /** 诊断用：列出模组可达类里的静态容器字段（Map / Collection）及其规模与指向本实体的条目数 */
    private static void diagnoseStaticContainers(LivingEntity victim) {
        Class<?> type = victim.getClass();
        if (!MAConfig.debugProbes || !CONTAINER_DIAGNOSED.add(type)) return;
        List<Class<?>> candidates = new ArrayList<>(hierarchy(type));
        candidates.addAll(referencedModClassesDeep(type));
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int scanned = 0;
        for (Class<?> clazz : candidates) {
            if (clazz == null || !seen.add(clazz)) continue;
            if (scanned++ >= OPERATOR_REFERENCED_CLASSES) break;
            for (Field field : declaredFieldsOf(clazz)) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                if (!Map.class.isAssignableFrom(fieldType) && !Collection.class.isAssignableFrom(fieldType)) continue;
                Object container = readStaticFieldAny(field);
                if (container == null) continue;
                probeLog("静态容器：{}#{}({}) {}", clazz.getName(), field.getName(),
                        fieldType.getSimpleName(), describeContainer(container, victim));
                describeFieldRegistry(container);
            }
        }
    }

    /** 诊断用：若某张静态 Map 是「字段登记表」（值是 {@code Field}），打印每条登记的目标类与字段名 */
    private static void describeFieldRegistry(Object container) {
        if (!(container instanceof Map<?, ?> map) || map.isEmpty()) return;
        int listed = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Field registered)) continue;
            probeLog("  字段登记：{} → {}#{} ({})", entry.getKey(),
                    registered.getDeclaringClass().getName(), registered.getName(),
                    registered.getType().getSimpleName());
            if (++listed >= 32) break;
        }
    }

    /** 静态容器的一句话描述：规模 + 有多少条目直接指向本实体 + 一个条目的类型样例 */
    private static String describeContainer(Object container, LivingEntity victim) {
        int size;
        int related = 0;
        int visited = 0;
        String sample = null;
        if (container instanceof Map<?, ?> map) {
            size = map.size();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (visited++ >= CONTAINER_DIAGNOSE_LIMIT) break;
                boolean hit = entry.getKey() == victim || entry.getValue() == victim;
                if (hit) related++;
                if (sample == null || hit) sample = typeName(entry.getKey()) + "->" + typeName(entry.getValue());
            }
        } else if (container instanceof Collection<?> collection) {
            size = collection.size();
            for (Object element : collection) {
                if (visited++ >= CONTAINER_DIAGNOSE_LIMIT) break;
                boolean hit = element == victim;
                if (hit) related++;
                if (sample == null || hit) sample = typeName(element);
            }
        } else {
            return "无法识别";
        }
        return "size=" + size + " 指向本实体=" + related + " 样例=" + sample;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** 读实例字段：先常规反射，失败再走 Unsafe 字段偏移 */
    private static Object readFieldAny(Field field, Object target) {
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
            // 公有字段无需 setAccessible
        }
        Object viaReflect = readFieldObject(field, target);
        if (viaReflect != null) return viaReflect;
        if (field.getType().isPrimitive()) return null;
        Unsafe instance = getUnsafe();
        if (instance == null) return null;
        try {
            return instance.getObject(target, instance.objectFieldOffset(field));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读静态字段：先常规反射，失败再走 Unsafe 静态偏移 */
    private static Object readStaticFieldAny(Field field) {
        try {
            field.setAccessible(true);
        } catch (Throwable ignored) {
            // 公有字段无需 setAccessible
        }
        Object viaReflect = readFieldObject(field, null);
        if (viaReflect != null) return viaReflect;
        if (field.getType().isPrimitive()) return null;
        Unsafe instance = getUnsafe();
        if (instance == null) return null;
        try {
            return instance.getObject(instance.staticFieldBase(field), instance.staticFieldOffset(field));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在实体继承链上找血量算子：其方法空参调用读出的值等于当前 {@code getHealth()} */
    private static OperatorPlan discoverOperatorPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        boolean diagnose = MAConfig.debugProbes && OPERATOR_DIAGNOSED.add(type);
        if (diagnose) {
            // 继承链与每层的实例字段一并打出来
            for (Class<?> current : hierarchy(type)) {
                List<String> names = new ArrayList<>();
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) names.add(field.getName());
                }
                probeLog("健康算子层继承链：{} 实例字段={}", current.getName(), names);
            }
        }
        List<String> candidatesSeen = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                OperatorPlan plan = probeOperatorField(victim, field, health, diagnose, candidatesSeen);
                if (plan != null) return plan;
            }
        }
        // 加载期被搬走的实体字段：从静态「字段登记表」里按形状取回并纳入候选
        for (Field field : registeredStateFields(victim)) {
            OperatorPlan plan = probeOperatorField(victim, field, health, diagnose, candidatesSeen);
            if (plan != null) return plan;
        }
        if (diagnose) probeLog("健康算子层未发现算子（{}）：候选字段={}", type.getName(), candidatesSeen);
        return null;
    }

    /** 探查单个字段是否持有血量算子：空参调用读出的值等于 {@code getHealth()} 即命中 */
    private static OperatorPlan probeOperatorField(LivingEntity victim, Field field, float health,
                                                   boolean diagnose, List<String> candidatesSeen) {
        // 设可访问失败也仍可直读或用 Unsafe 兜底
        Object operator = readFieldAny(field, victim);
        List<Method> methods = operatorMethods(field.getType());
        if (operator != null) methods = mergeMethods(methods, operatorMethods(operator.getClass()));
        if (methods.isEmpty()) return null;
        if (candidatesSeen != null) {
            candidatesSeen.add(field.getName() + ":" + field.getType().getSimpleName() + "×" + methods.size());
        }
        if (operator == null) return null;
        for (Method operate : methods) {
            float read = operatorNumber(invokeObject(operate, operator, new Object[]{ NO_ARGS }));
            if (diagnose) {
                probeLog("健康算子层候选：{}#{}({}) 读值={} 期望={}",
                        victim.getClass().getName(), field.getName(), field.getType().getSimpleName(), read, health);
            }
            if (matches(read, health)) return new OperatorPlan(field, operate);
        }
        return null;
    }

    /** 静态「字段登记表」里的实体状态字段（只保留声明类是实体祖先的） */
    private static List<Field> registeredStateFields(LivingEntity victim) {
        List<Field> fields = new ArrayList<>();
        Class<?> type = victim.getClass();
        List<Class<?>> sources = new ArrayList<>(hierarchy(type));
        sources.addAll(referencedModClassesDeep(type));
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int scanned = 0;
        for (Class<?> clazz : sources) {
            if (clazz == null || !seen.add(clazz)) continue;
            if (scanned++ >= OPERATOR_REFERENCED_CLASSES) break;
            for (Field holder : declaredFieldsOf(clazz)) {
                if (!Modifier.isStatic(holder.getModifiers()) || !Map.class.isAssignableFrom(holder.getType())) {
                    continue;
                }
                if (!(readStaticFieldAny(holder) instanceof Map<?, ?> registry)) continue;
                for (Object value : registry.values()) {
                    if (!(value instanceof Field registered)) continue;
                    Class<?> declaring = registered.getDeclaringClass();
                    if (!declaring.isInstance(victim)) continue;
                    if (!fields.contains(registered)) fields.add(registered);
                }
            }
        }
        return fields;
    }

    /** 收集类型自身、父类与接口上「{@code (Object[])} 形参、返回非 void」的实例方法 */
    private static List<Method> operatorMethods(Class<?> type) {
        List<Method> found = new ArrayList<>();
        collectOperatorMethods(type, found, Collections.newSetFromMap(new IdentityHashMap<>()));
        return found;
    }

    @SuppressWarnings("CatchMayIgnoreException")
    private static void collectOperatorMethods(Class<?> type, List<Method> out, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) return;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != Object[].class) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable t) {
                // 接口方法本就 public，不因设可访问失败丢弃
                rethrowIfFatal(t);
            }
            if (!out.contains(method)) out.add(method);
        }
        collectOperatorMethods(type.getSuperclass(), out, visited);
        for (Class<?> iface : type.getInterfaces()) collectOperatorMethods(iface, out, visited);
    }

    private static List<Method> mergeMethods(List<Method> base, List<Method> extra) {
        if (extra.isEmpty()) return base;
        List<Method> merged = new ArrayList<>(base);
        for (Method method : extra) {
            if (!merged.contains(method)) merged.add(method);
        }
        return merged;
    }

    private static float readOperatorHealth(OperatorPlan plan, Object operator) {
        Object result = invokeObject(plan.operate(), operator, new Object[]{ NO_ARGS });
        return operatorNumber(result);
    }

    /** 把算子读出的值当数字读：数值或数值字符串 */
    private static float operatorNumber(Object result) {
        if (result instanceof Number number) return number.floatValue();
        if (result instanceof String text) {
            try {
                return Float.parseFloat(text.trim());
            } catch (Throwable ignored) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    /** 算子写入的实参模板：逐个尝试，由「写后回读 getHealth 是否到位」决定哪个生效 */
    private static List<Object[]> operatorWriteShapes(LivingEntity victim, String key, Object value) {
        return List.of(
                new Object[]{ victim.getClass(), value },
                new Object[]{ key, value },
                new Object[]{ value, victim.getClass() },
                new Object[]{victim.getUUID().hashCode(), value },
                new Object[]{ victim.getUUID(), value },
                new Object[]{ victim, value },
                new Object[]{ value });
    }

    /** 单次调用算子（实参就是模板里的那串） */
    private static void writeOperatorArgs(OperatorPlan plan, Object operator, Object[] args) {
        invokeObject(plan.operate(), operator, new Object[]{ args });
    }

    /** 写入值的候选承载形态（数值本体优先，其次数值字符串） */
    private static List<Object> operatorPayloads(float value) {
        List<Object> payloads = new ArrayList<>(4);
        payloads.add(value);
        payloads.add((double) value);
        payloads.add(plainText(value));
        payloads.add((int) value);
        return payloads;
    }

    /** 候选写键：实体继承链与少量可达模组类常量池里的字符串字面量 */
    private static List<String> operatorKeys(Class<?> type) {
        List<String> keys = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (String value : stringConstants(current)) addOperatorKey(keys, value);
        }
        int scanned = 0;
        for (Class<?> clazz : referencedModClasses(type)) {
            if (scanned++ >= OPERATOR_REFERENCED_CLASSES) break;
            for (String value : stringConstants(clazz)) addOperatorKey(keys, value);
        }
        return keys;
    }

    private static void addOperatorKey(List<String> keys, String value) {
        if (keys.size() >= OPERATOR_MAX_KEYS || value == null) return;
        if (value.isEmpty() || value.length() > 64 || keys.contains(value)) return;
        keys.add(value);
    }

    /** 密文路径：血量以编码字符串存在同步数据里，按标定的编解码调用式整串回写 */
    private static boolean writeViaCipherHealth(LivingEntity victim, float healthAfter) {
        Class<?> type = victim.getClass();
        CipherCodec codec = cipherCodec(victim);
        if (codec == null) {
            probeLog("加密同步血量层未标定出编解码调用式：{}", type.getName());
            return false;
        }
        SynchedEntityData entityData = victim.getEntityData();
        EntityDataAccessor<?> accessor = encryptedHealthAccessor(victim, entityData, codec);
        if (accessor == null) {
            probeLog("加密同步血量层未找到可解码的密文同步项：{}", type.getName());
            return false;
        }
        Object raw;
        try {
            raw = entityData.get(accessor);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
        if (!(raw instanceof String stored) || stored.isEmpty()) {
            probeLog("加密同步血量层同步项非字符串：{} → {}", type.getName(), raw);
            return false;
        }
        List<String> nbt = cipherNbtStrings(victim);
        String previous = cipherText(codec, victim, healthOf(victim), nbt);
        String updated = cipherText(codec, victim, healthAfter, nbt);
        if (previous == null || updated == null) {
            probeLog("加密同步血量层编码失败：{} 存储={}", type.getName(), stored);
            return false;
        }
        // 现场串可能带固定前缀，只替换「旧编码段」那一段
        String next = codec.suffixOnly() && stored.endsWith(previous)
                ? stored.substring(0, stored.length() - previous.length()) + updated
                : updated;
        try {
            entityData.set(rawAccessor(accessor), next);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            probeLog("加密同步血量层写入失败：{} → {}", type.getName(), t);
            return false;
        }
        if (!stillAboveTarget(victim, healthAfter)) {
            probeLog("加密同步血量层写入生效：{} 目标={} getHealth={}", type.getName(), healthAfter, healthOf(victim));
            return true;
        }
        // 没到位就还原
        try {
            entityData.set(rawAccessor(accessor), stored);
        } catch (Throwable ignored) {
            // 还原失败则维持写入结果
        }
        probeLog("加密同步血量层未生效，已还原：{} 目标={} getHealth={}",
                type.getName(), healthAfter, healthOf(victim));
        return false;
    }

    /** 用已标定的编码口把血量编成密文段（主实参按该形参的声明类型装箱） */
    private static String cipherText(CipherCodec codec, LivingEntity victim, float health, List<String> nbt) {
        CipherCall call = codec.encode();
        Object primary = boxPrimary(call.method().getParameterTypes()[call.primaryParam()], health);
        return cipherEncodeWith(call, victim, primary, nbt);
    }

    /** 在实体的同步数据里找出「装着血量密文」的那一项（解码值等于 {@code getHealth()}） */
    private static EntityDataAccessor<?> encryptedHealthAccessor(LivingEntity victim, SynchedEntityData entityData,
                                                                 CipherCodec codec) {
        Class<?> type = victim.getClass();
        EntityDataAccessor<?> cached = ENCRYPTED_HEALTH_ACCESSOR_CACHE.get(type);
        if (cached != null) return cached;
        float health = healthOf(victim);
        List<String> nbt = cipherNbtStrings(victim);
        boolean diagnose = MAConfig.debugProbes && CIPHER_DIAGNOSED.add(type);
        if (diagnose) {
            List<String> names = new ArrayList<>();
            for (Class<?> current : hierarchy(type)) names.add(current.getName());
            probeLog("加密同步血量层类层次：{}", names);
        }
        for (EntityDataAccessor<?> accessor : registeredAccessors(entityData)) {
            Object value;
            try {
                value = entityData.get(accessor);
            } catch (Throwable t) {
                if (diagnose) probeLog("加密同步血量层候选不可读：{} → {}", accessor, t);
                continue;
            }
            Double decoded = value instanceof String text ? cipherDecodeWith(codec.decode(), victim, text, nbt) : null;
            if (diagnose) probeLog("加密同步血量层候选：{} 值={} 解码={}", accessor, value, decoded);
            if (decoded == null || !matches(decoded.floatValue(), health)) continue;
            ENCRYPTED_HEALTH_ACCESSOR_CACHE.put(type, accessor);
            probeLog("加密同步血量层已定位密文项：{} {}", type.getName(), accessor);
            return accessor;
        }
        return null;
    }

    /** 同步表里已注册的 accessor */
    private static List<EntityDataAccessor<?>> registeredAccessors(SynchedEntityData entityData) {
        List<EntityDataAccessor<?>> accessors = new ArrayList<>();
        for (Field field : SynchedEntityData.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            if (!(readFieldAny(field, entityData) instanceof Map<?, ?> items)) continue;
            for (Object item : items.values()) {
                if (item == null) continue;
                if (dataItemAccessor(item) instanceof EntityDataAccessor<?> accessor
                        && !accessors.contains(accessor)) {
                    accessors.add(accessor);
                }
            }
        }
        return accessors;
    }

    /** {@code SynchedEntityData.DataItem} 上持有的 accessor */
    private static Object dataItemAccessor(Object dataItem) {
        for (Field field : dataItem.getClass().getDeclaredFields()) {
            if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
            return readFieldAny(field, dataItem);
        }
        return null;
    }

    // ===== 密文串血量的「调用式」现场标定 =====

    /** 上下文实参的取值来源种类 */
    private enum CipherArgKind {
        ENTITY, UUID, UUID_HASH, UUID_HIGH, UUID_LOW, ENTITY_ID, ENTITY_HASH, NBT_STRING, ZERO, ONE
    }

    private record CipherArgSource(CipherArgKind kind, int index) {
    }

    /** 一次已标定的调用：主实参（密文 / 明文）落在 {@code primaryParam}，其余形参位按 {@code args} 取值 */
    private record CipherCall(Method method, int primaryParam, CipherArgSource[] args) {
    }

    /** 已标定的密文串血量编解码方案（按实体类缓存） */
    private record CipherCodec(CipherCall decode, CipherCall encode, boolean suffixOnly) {
    }

    /** 已标定方案，按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, CipherCodec> CIPHER_CODEC_CACHE = new ConcurrentHashMap<>();
    /** 单次标定的调用次数上限 */
    private static final int CIPHER_MAX_TRIALS = 4096;
    /** 上下文形参位上限（多于此位的候选直接跳过） */
    private static final int CIPHER_MAX_CONTEXT_PARAMS = 2;
    /** 实体 NBT 字符串候选上限 */
    private static final int CIPHER_MAX_NBT_STRINGS = 16;

    private static CipherCodec cipherCodec(LivingEntity victim) {
        CipherCodec cached = CIPHER_CODEC_CACHE.get(victim.getClass());
        if (cached != null) return cached;
        CipherCodec found = discoverCipherCodec(victim);
        if (found != null) CIPHER_CODEC_CACHE.put(victim.getClass(), found);
        return found;
    }

    /** 现场标定：以「现场密文 ↔ 当前 getHealth()」反推解码口与编码口的调用形状 */
    private static CipherCodec discoverCipherCodec(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        List<Method> candidates = cipherMethods(victim.getClass());
        List<String> storedValues = cipherStoredStrings(victim);
        List<String> nbt = cipherNbtStrings(victim);
        if (MAConfig.debugProbes) {
            probeLog("密文串血量：候选方法={} 字符串位点={} NBT串={}",
                    candidates.size(), storedValues.size(), nbt.size());
        }
        if (candidates.isEmpty() || storedValues.isEmpty()) return null;
        int trials = 0;
        for (String stored : storedValues) {
            for (Method method : candidates) {
                for (int primary = 0; primary < method.getParameterCount(); primary++) {
                    if (!cipherPrimary(method.getParameterTypes()[primary], true)) continue;
                    for (CipherCall decode : cipherCalls(method, primary, victim, nbt)) {
                        if (trials++ >= CIPHER_MAX_TRIALS) return null;
                        Double plain = cipherDecodeWith(decode, victim, stored, nbt);
                        if (plain == null || !matches(plain.floatValue(), health)) continue;
                        CipherCall encode = findCipherEncoder(victim, plausible(plain, health), stored, decode, candidates, nbt);
                        if (encode == null) continue;
                        String again = cipherEncodeWith(encode, victim,
                                boxPrimary(encode.method().getParameterTypes()[encode.primaryParam()],
                                        plausible(plain, health)), nbt);
                        return new CipherCodec(decode, encode, again != null && !stored.equals(again));
                    }
                }
            }
        }
        return null;
    }

    /** 可能是血量密文的字符串：实体 String 字段 + 同步数据里当前值为 String 的项 */
    private static List<String> cipherStoredStrings(LivingEntity victim) {
        List<String> values = new ArrayList<>();
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw != null && !raw.isEmpty() && !values.contains(raw)) values.add(raw);
        }
        return values;
    }

    /** 解码结果可能带浮点噪声，取更可信的那个作为明文血量 */
    private static double plausible(Double decoded, float observed) {
        return Math.abs(decoded - observed) <= 0.01 ? observed : decoded;
    }

    /** 候选编解码方法：静态或实例；1~3 个形参且类型都在可提供的取值类型里；返回字符串或数值 */
    private static List<Method> cipherMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (Method method : current.getDeclaredMethods()) {
                if (cipherMethodUsable(method, type)) methods.add(method);
            }
        }
        int scanned = 0;
        for (Class<?> clazz : referencedModClassesDeep(type)) {
            if (scanned++ >= OPERATOR_REFERENCED_CLASSES) break;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (cipherMethodUsable(method, type)) methods.add(method);
            }
        }
        return methods;
    }

    private static boolean cipherMethodUsable(Method method, Class<?> entityType) {
        // 只排除桥接方法，不排除 synthetic
        if (method.isBridge()) return false;
        int count = method.getParameterCount();
        if (count < 1 || count > 1 + CIPHER_MAX_CONTEXT_PARAMS) return false;
        Class<?> returns = method.getReturnType();
        if (returns != String.class && !isCipherNumber(returns)) return false;
        for (Class<?> param : method.getParameterTypes()) {
            if (!isCipherContextType(param, entityType)) return false;
        }
        return !cannotAccess(method);
    }

    /** 该类型能承载上下文取值（字符串 / 数值 / UUID / 实体本身 / Object） */
    private static boolean isCipherContextType(Class<?> param, Class<?> entityType) {
        if (param == String.class || param == CharSequence.class || param == Object.class) return true;
        if (param == UUID.class || isCipherNumber(param)) return true;
        return param.isAssignableFrom(entityType);
    }

    private static boolean isCipherNumber(Class<?> type) {
        return type == int.class || type == long.class || type == float.class || type == double.class
                || type == short.class || type == byte.class;
    }

    /** 主实参位：解码时收密文的位（字符串，或 Object 这种什么都能收的位）；编码时可以再收数值 */
    private static boolean cipherPrimary(Class<?> param, boolean decode) {
        if (param == String.class || param == Object.class || CharSequence.class.isAssignableFrom(param)) {
            return true;
        }
        return !decode && isCipherNumber(param);
    }

    /** 该位上的实参取值来源候选（按类型给） */
    private static List<CipherArgSource> cipherSources(Class<?> param, LivingEntity victim, List<String> nbt) {
        List<CipherArgSource> sources = new ArrayList<>();
        if (param == int.class || param == Integer.class) {
            sources.add(new CipherArgSource(CipherArgKind.ENTITY_ID, 0));
            sources.add(new CipherArgSource(CipherArgKind.UUID_HASH, 0));
            sources.add(new CipherArgSource(CipherArgKind.ENTITY_HASH, 0));
            sources.add(new CipherArgSource(CipherArgKind.ZERO, 0));
            sources.add(new CipherArgSource(CipherArgKind.ONE, 0));
        } else if (param == long.class || param == Long.class) {
            sources.add(new CipherArgSource(CipherArgKind.UUID_HIGH, 0));
            sources.add(new CipherArgSource(CipherArgKind.UUID_LOW, 0));
            sources.add(new CipherArgSource(CipherArgKind.ZERO, 0));
            sources.add(new CipherArgSource(CipherArgKind.ONE, 0));
        } else if (param == UUID.class) {
            sources.add(new CipherArgSource(CipherArgKind.UUID, 0));
        } else if (param == String.class || CharSequence.class.isAssignableFrom(param)) {
            for (int i = 0; i < nbt.size(); i++) sources.add(new CipherArgSource(CipherArgKind.NBT_STRING, i));
        } else if (param == Object.class || param.isAssignableFrom(victim.getClass())) {
            sources.add(new CipherArgSource(CipherArgKind.ENTITY, 0));
        }
        return sources;
    }

    /** 枚举「主实参位固定、其余位各取一种来源」的全部组合（带上限） */
    private static List<CipherCall> cipherCalls(Method method, int primary, LivingEntity victim, List<String> nbt) {
        List<CipherCall> calls = new ArrayList<>();
        fillCipherCalls(method, primary, victim, nbt, 0, new CipherArgSource[method.getParameterCount()], calls);
        return calls;
    }

    private static void fillCipherCalls(Method method, int primary, LivingEntity victim, List<String> nbt,
                                        int index, CipherArgSource[] args, List<CipherCall> calls) {
        if (calls.size() >= CIPHER_MAX_TRIALS) return;
        if (index == args.length) {
            calls.add(new CipherCall(method, primary, args.clone()));
            return;
        }
        if (index == primary) {
            fillCipherCalls(method, primary, victim, nbt, index + 1, args, calls);
            return;
        }
        for (CipherArgSource source : cipherSources(method.getParameterTypes()[index], victim, nbt)) {
            args[index] = source;
            fillCipherCalls(method, primary, victim, nbt, index + 1, args, calls);
        }
    }

    /** 按已标定的调用形状实际调用；任一位取不到值即返回 null */
    private static Object[] fillCipherArgs(CipherCall call, LivingEntity victim, Object primaryValue, List<String> nbt) {
        Class<?>[] params = call.method().getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (i == call.primaryParam()) {
                args[i] = primaryValue;
                continue;
            }
            args[i] = cipherArgValue(call.args()[i], victim, nbt, params[i]);
            if (args[i] == null) return null;
        }
        return args;
    }

    private static Object cipherArgValue(CipherArgSource source, LivingEntity victim, List<String> nbt, Class<?> param) {
        return switch (source.kind()) {
            case ENTITY -> victim;
            case UUID -> victim.getUUID();
            case UUID_HASH -> victim.getUUID().hashCode();
            case UUID_HIGH -> victim.getUUID().getMostSignificantBits();
            case UUID_LOW -> victim.getUUID().getLeastSignificantBits();
            case ENTITY_ID -> victim.getId();
            case ENTITY_HASH -> victim.hashCode();
            case NBT_STRING -> source.index() < nbt.size() ? nbt.get(source.index()) : null;
            case ZERO -> boxNumber(param, 0.0);
            case ONE -> boxNumber(param, 1.0);
        };
    }

    private static Object cipherTarget(CipherCall call, LivingEntity victim) {
        return Modifier.isStatic(call.method().getModifiers()) ? null : victim;
    }

    /** 用已标定的解码口解出数值（返回 String 的按十进制解析；解不出返回 null） */
    private static Double cipherDecodeWith(CipherCall call, LivingEntity victim, String stored, List<String> nbt) {
        Object[] args = fillCipherArgs(call, victim, stored, nbt);
        if (args == null) return null;
        return toNumber(invokeObject(call.method(), cipherTarget(call, victim), args));
    }

    /** 用已标定的编码口编出字符串（主实参类型由形参声明决定） */
    private static String cipherEncodeWith(CipherCall call, LivingEntity victim, Object primary, List<String> nbt) {
        Object[] args = fillCipherArgs(call, victim, primary, nbt);
        if (args == null) return null;
        return invokeObject(call.method(), cipherTarget(call, victim), args) instanceof String text ? text : null;
    }

    private static Double toNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object boxPrimary(Class<?> param, double value) {
        if (param == String.class || CharSequence.class.isAssignableFrom(param)) return plainText(value);
        return boxNumber(param, value);
    }

    /** 找编码口：把明文编回去要能重现现场那串（或其后缀），且再过解码口仍能还原 */
    private static CipherCall findCipherEncoder(LivingEntity victim, double plain, String stored, CipherCall decode,
                                                List<Method> candidates, List<String> nbt) {
        for (Method method : candidates) {
            for (int primary = 0; primary < method.getParameterCount(); primary++) {
                if (!cipherPrimary(method.getParameterTypes()[primary], false)) continue;
                for (CipherCall call : cipherCalls(method, primary, victim, nbt)) {
                    String encoded = cipherEncodeWith(call, victim,
                            boxPrimary(method.getParameterTypes()[primary], plain), nbt);
                    if (encoded == null || encoded.isEmpty()) continue;
                    if (!stored.equals(encoded) && !(encoded.length() >= 2 && stored.endsWith(encoded))) continue;
                    Double back = cipherDecodeWith(decode, victim, encoded, nbt);
                    if (back == null || !matches(back.floatValue(), (float) plain)) continue;
                    return call;
                }
            }
        }
        return null;
    }

    /** 实体身上所有字符串型 NBT 值（持久数据 + 序列化快照），键名排序保证来源下标稳定 */
    @SuppressWarnings("CatchMayIgnoreException")
    private static List<String> cipherNbtStrings(LivingEntity victim) {
        Set<String> values = new TreeSet<>();
        collectNbtStrings(safePersistentData(victim), values, 0);
        try {
            CompoundTag snapshot = new CompoundTag();
            victim.saveWithoutId(snapshot);
            collectNbtStrings(snapshot, values, 0);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            result.add(value);
            if (result.size() >= CIPHER_MAX_NBT_STRINGS) break;
        }
        return result;
    }

    private static CompoundTag safePersistentData(LivingEntity victim) {
        try {
            return victim.getPersistentData();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 递归收集 CompoundTag / ListTag 里的字符串值（深度受限） */
    private static void collectNbtStrings(Tag tag, Set<String> out, int depth) {
        if (tag == null || depth > 2 || out.size() >= CIPHER_MAX_NBT_STRINGS * 4) return;
        if (tag instanceof StringTag text) {
            out.add(text.getAsString());
            return;
        }
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) collectNbtStrings(compound.get(key), out, depth + 1);
            return;
        }
        if (tag instanceof ListTag list) {
            for (Tag element : list) collectNbtStrings(element, out, depth + 1);
        }
    }

    /** 类文件常量池里的字符串字面量（{@code CONSTANT_String_info} → Utf8）；解析失败返回空列表 */
    private static List<String> stringConstants(Class<?> type) {
        return STRING_CONSTANT_CACHE.computeIfAbsent(type, target -> {
            byte[] bytes = readClassBytes(target);
            return bytes == null ? List.of() : parseStringConstants(bytes);
        });
    }

    private static List<String> parseStringConstants(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int poolSize = in.readUnsignedShort();
            String[] utf8 = new String[poolSize];
            int[] refs = new int[poolSize];
            for (int i = 1; i < poolSize; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 8 -> refs[i] = in.readUnsignedShort();
                    case 7, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++;
                    }
                    default -> {
                        return List.of();
                    }
                }
            }
            List<String> values = new ArrayList<>();
            for (int index : refs) {
                if (index > 0 && index < poolSize && utf8[index] != null) values.add(utf8[index]);
            }
            return values;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // ===== 隐藏状态帧直写（档 3，级联最后一层）：造自洽状态帧写进隐藏中继 =====

    /** 标定造帧入口用的哨兵基数（只要求互不相等） */
    private static final int STATE_SENTINEL_INT_BASE = 1_000_003;
    private static final long STATE_SENTINEL_LONG_BASE = 1_000_000_007L;
    private static final String STATE_SENTINEL_STRING = "ma$sentinel$string";
    /** 标定「值位 → 观察血量」映射时的采样步长与次数 */
    private static final int STATE_VALUE_PROBE_STEPS = 6;
    /** 转换码的兜底探测上限 */
    private static final int STATE_TRANSITION_PROBE_MAX = 15;
    /** 单次结算里最多尝试几种「造帧方案」（转换码 × 次序，已确认过的组合优先） */
    private static final int STATE_FRAME_MAX_ATTEMPTS = 72;
    /** 状态帧机制的形状句柄，按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, StateFrameHandle> STATE_FRAME_CACHE = new ConcurrentHashMap<>();
    /** 已确认可用的「转换类型 + 次序」组合，按实体类缓存 */
    private static final Map<Class<?>, int[]> STATE_TRANSITION_CACHE = new ConcurrentHashMap<>();
    /** {@code AccessibleObject.override} 是否已打开 */
    private static boolean accessOverrideOpened;

    /** 辅助方法形参 / 帧内位点的语义（标定产物） */
    private enum FrameSemantic {
        /** 辅助调用的实参语义 */
        ENTITY, VALUE, PREVIOUS, TRANSITION, STRING, VIEW, ISSUED,
        /** 仅用于帧内位点：模拟签名 / 死亡许可 / 毫微秒 */
        SIMULATION_STAMP, DEATH_PERMIT
    }

    /** 帧里的一个位点：{@code array != null} 时是「该 int[] 字段的第 index 格」，否则是字段 */
    private record FrameSlot(Field array, int index, Field owner, Field field) {
        static FrameSlot of(Field arrayField, int index) {
            return new FrameSlot(arrayField, index, null, null);
        }

        static FrameSlot of(Field field) {
            return new FrameSlot(null, -1, null, field);
        }

        static FrameSlot nested(Field owner, Field field) {
            return new FrameSlot(null, -1, owner, field);
        }

        Object read(Object frame) {
            try {
                if (array != null) {
                    return array.get(frame) instanceof int[] values && index < values.length ? values[index] : null;
                }
                if (field == null) return null;
                Object target = owner == null ? frame : owner.get(frame);
                return target == null ? null : field.get(target);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                return null;
            }
        }
    }

    /** 帧里的一个字段引用：位点 + 声明类型 + 当前值（供按类型 / 按值定位） */
    private record FrameFieldRef(FrameSlot slot, Class<?> type, Object value) {
    }

    /** 已经标定好「每个形参该填什么语义」的辅助调用 */
    private record CalibratedCall(Method method, FrameSemantic[] slots) {
    }

    /** 一次「造帧 + 写回」所需的全部取值（按 {@link FrameSemantic} 取用） */
    private record FrameValues(Object entity, Object value, Object previous, Object transition,
                               Object issued, String string, Object view) {
    }

    /** 「帧值位 ↔ 观察血量」的仿射映射：{@code 观察血量 = scale × 帧值 + offset} */
    private record ValueMap(double scale, double offset) {
        static final ValueMap DEFAULT = new ValueMap(-1.0, 0.0);

        /** 目标血量对应的帧值位（float 的真实位模式） */
        int bitsFor(float health) {
            double raw = (health - offset) / scale;
            float value = Double.isFinite(raw) ? (float) raw : -health;
            // −0.0 与 +0.0 位模式不同，负零须归一化为正零
            if (value == 0.0F) value = Float.intBitsToFloat(0);
            return Float.floatToRawIntBits(value);
        }
    }

    /**
     * 状态帧机制的形状句柄；所有形参顺序与位点语义均来自现场标定。
     *
     * @param frameType 帧类
     * @param create 造帧入口
     * @param createSlots 造帧形参对应的帧内位点
     * @param slots 已标定的「语义 → 帧内位点」表
     * @param ambiguous 语义尚未定位的时间线位点
     * @param read 中继读口
     * @param writes 中继写口
     * @param verifies 帧校验口
     * @param encodeView / encodeString / stampSim / stampPermit 已标定实参语义的辅助调用
     * @param valueMap 帧值位 ↔ 观察血量
     */
    private record StateFrameHandle(Class<?> frameType, Method create, FrameSlot[] createSlots,
                                    List<FrameSlot> equalSlots, Map<FrameSemantic, FrameSlot> slots,
                                    List<FrameSlot> ambiguous, Method read, List<Method> writes,
                                    List<Method> verifies, CalibratedCall encodeView, CalibratedCall encodeString,
                                    CalibratedCall stampSim, CalibratedCall stampPermit, ValueMap valueMap) {
    }

    /** 用实体的「权威状态帧」把血写到位（只在扣血时使用；写不到就还原） */
    private static void writeViaStateFrame(LivingEntity victim, float healthAfter) {
        StateFrameHandle handle = stateFrameHandle(victim);
        if (handle == null) {
            probeLog("状态帧直写：未发现状态帧机制（{}）", victim.getClass().getName());
            return;
        }
        float current = healthOf(victim);
        if (Float.isNaN(current) || healthAfter >= current) return;
        Object reference = invokeObject(handle.read(), null, new Object[]{victim});
        if (reference != null && reference.getClass() != handle.frameType()) reference = null;
        int wantBits = handle.valueMap().bitsFor(healthAfter);
        int previousBits = reference == null ? handle.valueMap().bitsFor(current)
                : intOf(handle.equalSlots().get(0).read(reference), wantBits);
        int attempts = 0;
        for (int[] plan : attemptPlans(victim.getClass(), ambiguousValues(handle, reference))) {
            if (attempts++ >= STATE_FRAME_MAX_ATTEMPTS) {
                restoreStateFrame(handle, victim, reference);
                probeLog("状态帧直写：方案用尽仍未生效（{} 当前={} 目标={}）",
                        victim.getClass().getName(), healthOf(victim), healthAfter);
                return;
            }
            int transition = plan[0];
            int order = plan[1];
            Object frame = buildStateFrame(handle, victim, wantBits, previousBits, transition, order);
            if (frame == null) continue;
            if (writeAndVerify(handle, victim, frame, healthAfter)) {
                STATE_TRANSITION_CACHE.put(victim.getClass(), plan);
                probeLog("状态帧直写：{} 血量 {} => {}（转换={} 次序={}）",
                        victim.getClass().getName(), current, healthAfter, transition, order);
                return;
            }
            // 每一次失败都退回参考帧，避免留下半改状态或让它读到非法帧
            restoreStateFrame(handle, victim, reference);
        }
        probeLog("状态帧直写：写入未生效并已还原（{} 当前={} 目标={}）",
                victim.getClass().getName(), healthOf(victim), healthAfter);
    }

    /** 造帧方案（转换码 × 次序）的有序尝试表：已确认组合优先，再逐个兜底探测 */
    private static List<int[]> attemptPlans(Class<?> type, int[] referenceValues) {
        List<int[]> plans = new ArrayList<>();
        int[] cached = STATE_TRANSITION_CACHE.get(type);
        if (cached != null) plans.add(cached);
        LinkedHashSet<Integer> transitions = new LinkedHashSet<>();
        for (int value : referenceValues) transitions.add(value);
        for (int value = 0; value <= STATE_TRANSITION_PROBE_MAX; value++) transitions.add(value);
        for (int transition : transitions) {
            for (int order = 0; order < 4; order++) {
                if (cached != null && cached[0] == transition && cached[1] == order) continue;
                plans.add(new int[]{transition, order});
            }
        }
        return plans;
    }

    /** 参考帧里那两个「含义待定」位点的当前取值（其中一个本来就是模组自己的转换码） */
    private static int[] ambiguousValues(StateFrameHandle handle, Object reference) {
        List<FrameSlot> ambiguous = handle.ambiguous();
        int[] values = new int[ambiguous.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = reference == null ? 0 : intOf(ambiguous.get(i).read(reference), 0);
        }
        return values;
    }

    /**
     * 用已标定的「语义 → 位点」表造一帧。
     * @param order 两个「含义待定」位点与实参语义的次序组合
     */
    private static Object buildStateFrame(StateFrameHandle handle, LivingEntity victim, int wantBits,
                                          int previousBits, int transition, int order) {
        // 含义待定的时间线位点按次序位分派「上一值 / 转换类型」
        List<FrameSlot> ambiguous = handle.ambiguous();
        boolean swapValues = ambiguous.size() >= 2 && (order & 2) != 0;
        Object previousArg = ambiguous.size() >= 2 ? (swapValues ? transition : previousBits) : wantBits;
        Object transitionArg = swapValues ? previousBits : transition;
        FrameValues base = new FrameValues(victim, wantBits, previousArg, transitionArg, null, null, null);
        Object view = invokeCalibrated(handle.encodeView(), base);
        if (view == null) return null;
        FrameValues withView = new FrameValues(victim, wantBits, previousArg, transitionArg, null, null, view);
        Object rawString = invokeCalibrated(handle.encodeString(), withView);
        if (!(rawString instanceof String text)) return null;
        long issuedAtNanos = System.nanoTime();
        FrameValues payload = new FrameValues(victim, wantBits, previousArg, transitionArg, issuedAtNanos, text, view);
        long simulation = asLong(invokeCalibrated(handle.stampSim(), payload));
        long permit = asLong(invokeCalibrated(handle.stampPermit(), payload));

        Map<FrameSemantic, FrameSlot> slots = handle.slots();
        Map<FrameSlot, Object> values = new HashMap<>();
        for (FrameSlot slot : handle.equalSlots()) values.put(slot, wantBits);
        boolean swapSlots = (order & 1) != 0;
        if (ambiguous.size() >= 2) {
            values.put(ambiguous.get(swapSlots ? 1 : 0), previousBits);
            values.put(ambiguous.get(swapSlots ? 0 : 1), transition);
        } else {
            values.put(ambiguous.get(0), transition);
        }
        values.put(slots.get(FrameSemantic.SIMULATION_STAMP), simulation);
        values.put(slots.get(FrameSemantic.DEATH_PERMIT), permit);
        values.put(slots.get(FrameSemantic.ISSUED), issuedAtNanos);
        values.put(slots.get(FrameSemantic.STRING), text);
        values.put(slots.get(FrameSemantic.VIEW), view);

        FrameSlot[] createSlots = handle.createSlots();
        Object[] args = new Object[createSlots.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = createSlots[i] == null ? null : values.get(createSlots[i]);
            if (args[i] == null) return null;
        }
        return invokeObject(handle.create(), null, args);
    }

    /** 按标定结果把语义取值铺进实参表后调用（实体位 / 值位 / 字符串 / NBT / 毫微秒 全部来自标定） */
    private static Object invokeCalibrated(CalibratedCall call, FrameValues values) {
        FrameSemantic[] slots = call.slots();
        Object[] args = new Object[slots.length];
        for (int i = 0; i < args.length; i++) {
            if (slots[i] == null) return null;   // 标定漏了形参位，放弃本次
            args[i] = switch (slots[i]) {
                case ENTITY -> values.entity();
                case VALUE -> values.value();
                case PREVIOUS -> values.previous();
                case TRANSITION -> values.transition();
                case STRING -> values.string();
                case VIEW -> values.view();
                case ISSUED -> values.issued();
                default -> null;
            };
        }
        return invokeObject(call.method(), null, args);
    }

    /** 写回并回读校验：达标即成功；校验口会顺带刷新视图位 */
    private static boolean writeAndVerify(StateFrameHandle handle, LivingEntity victim,
                                          Object frame, float healthAfter) {
        for (Method write : handle.writes()) {
            invokeObject(write, null, new Object[]{victim, frame});
        }
        for (Method verify : handle.verifies()) {
            if (matches(healthOf(victim), healthAfter)) return true;
            invokeObject(verify, null, new Object[]{victim, frame});
        }
        return matches(healthOf(victim), healthAfter);
    }

    private static void restoreStateFrame(StateFrameHandle handle, LivingEntity victim, Object reference) {
        if (reference == null) return;
        for (Method write : handle.writes()) {
            invokeObject(write, null, new Object[]{victim, reference});
        }
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static StateFrameHandle stateFrameHandle(LivingEntity victim) {
        StateFrameHandle cached = STATE_FRAME_CACHE.get(victim.getClass());
        if (cached != null) return cached;
        StateFrameHandle found = discoverStateFrameHandle(victim);
        if (found != null) STATE_FRAME_CACHE.put(victim.getClass(), found);
        return found;
    }

    /** 按结构（嵌套帧类 + int[] 时间线）发现状态帧机制，并现场标定各形参与帧内位点的语义 */
    private static StateFrameHandle discoverStateFrameHandle(LivingEntity victim) {
        for (Class<?> frameType : referencedNestedClasses(victim.getClass())) {
            if (frameType == null || frameType.isInterface() || frameType.isEnum()) continue;
            Field timeline = intArrayField(frameType);
            if (timeline == null) continue;
            Method create = frameFactory(frameType);
            Class<?> manager = frameType.getEnclosingClass();
            if (create == null || manager == null || manager == frameType) continue;
            if (cannotAccess(create) || cannotAccess(timeline)) continue;

            Method read = null;
            List<Method> writes = new ArrayList<>();
            List<Method> verifies = new ArrayList<>();
            List<Method> encodeViews = new ArrayList<>();
            List<Method> encodeStrings = new ArrayList<>();
            List<Method> stampSims = new ArrayList<>();
            List<Method> stampPermits = new ArrayList<>();
            for (Method method : declaredStaticMethods(manager)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || paramAccepting(params, victim) < 0) continue;
                Class<?> returns = method.getReturnType();
                if (params.length == 1 && returns == frameType && paramsMatch(params, victim, 0, 0, false, false)) {
                    read = method;
                } else if (params.length == 2 && paramOfType(params, frameType, -1) >= 0 && returns == void.class) {
                    writes.add(method);
                } else if (params.length == 2 && paramOfType(params, frameType, -1) >= 0 && returns == boolean.class) {
                    verifies.add(method);
                } else if (returns == long.class && paramsMatch(params, victim, 3, 1, true, true)) {
                    stampPermits.add(method);
                } else if (returns == long.class && paramsMatch(params, victim, 3, 0, true, true)) {
                    stampSims.add(method);
                } else if (returns == CompoundTag.class && paramsMatch(params, victim, 1, 0, false, false)) {
                    encodeViews.add(method);
                } else if (returns == String.class && paramsMatch(params, victim, 1, 0, false, true)) {
                    encodeStrings.add(method);
                }
            }
            if (read == null || writes.isEmpty() || verifies.isEmpty() || encodeViews.isEmpty()
                    || encodeStrings.isEmpty() || stampSims.isEmpty() || stampPermits.isEmpty()) {
                continue;
            }
            if (cannotAccess(read) || cannotAccessAll(writes) || cannotAccessAll(verifies)
                    || cannotAccessAll(encodeViews) || cannotAccessAll(encodeStrings)
                    || cannotAccessAll(stampSims) || cannotAccessAll(stampPermits)) {
                continue;
            }
            FrameSlot[] createSlots = calibrateFactorySlots(create, frameType, timeline);
            if (createSlots == null) continue;
            StateFrameHandle handle = calibrateAgainstReference(victim, frameType, create, createSlots, timeline,
                    read, writes, verifies, encodeViews, encodeStrings, stampSims, stampPermits);
            if (handle != null) {
                probeLog("状态帧直写：命中并标定完成（帧类={} 管理器={} 写口={} 校验口={} 值映射={}/{})",
                        frameType.getName(), manager.getName(), writes.size(), verifies.size(),
                        handle.valueMap().scale(), handle.valueMap().offset());
                return handle;
            }
        }
        return null;
    }

    /** 帧类的造帧入口：静态、返回该帧类型、形参只由 int/long/String/CompoundTag 组成（顺序不参与判定） */
    private static Method frameFactory(Class<?> frameType) {
        for (Method method : declaredStaticMethods(frameType)) {
            if (method.getReturnType() != frameType) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) continue;
            boolean accepted = true;
            for (Class<?> param : params) {
                if (param != int.class && param != long.class
                        && param != String.class && param != CompoundTag.class) {
                    accepted = false;
                    break;
                }
            }
            if (accepted) return method;
        }
        return null;
    }

    /** 哨兵法：用互不相等的哨兵调一次造帧入口，再回读产出帧，得到「第 k 个形参 → 帧里哪个位点」 */
    private static FrameSlot[] calibrateFactorySlots(Method create, Class<?> frameType, Field timeline) {
        Class<?>[] params = create.getParameterTypes();
        Object[] args = new Object[params.length];
        int intSeq = 0;
        int longSeq = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i] == int.class) args[i] = STATE_SENTINEL_INT_BASE + intSeq++;
            else if (params[i] == long.class) args[i] = STATE_SENTINEL_LONG_BASE + longSeq++;
            else if (params[i] == String.class) args[i] = STATE_SENTINEL_STRING;
            else args[i] = sentinelTag();
        }
        Object frame = invokeObject(create, null, args);
        if (frame == null || frame.getClass() != frameType) return null;
        List<FrameFieldRef> refs = frameFieldRefs(frame);
        List<FrameFieldRef> longs = longFields(refs);
        FrameFieldRef stringRef = firstOfType(refs, String.class);
        FrameFieldRef tagRef = firstOfType(refs, CompoundTag.class);
        FrameSlot[] slots = new FrameSlot[params.length];
        int intSeq2 = 0;
        int longSeq2 = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i] == int.class) {
                slots[i] = locateTimelineValue(timeline, frame, STATE_SENTINEL_INT_BASE + intSeq2++);
            } else if (params[i] == long.class) {
                FrameFieldRef ref = refOfValue(longs, STATE_SENTINEL_LONG_BASE + longSeq2++);
                slots[i] = ref == null ? null : ref.slot();
            } else if (params[i] == String.class) {
                slots[i] = stringRef != null && STATE_SENTINEL_STRING.equals(stringRef.value()) ? stringRef.slot() : null;
            } else {
                slots[i] = tagRef == null ? null : tagRef.slot();
            }
            if (slots[i] == null) return null;
        }
        return slots;
    }

    /** 参考帧重现：反推时间线各格语义与各辅助方法的实参语义；任一步失败即返回 null（本层跳过） */
    private static StateFrameHandle calibrateAgainstReference(LivingEntity victim, Class<?> frameType,
                                                              Method create, FrameSlot[] createSlots, Field timeline,
                                                              Method read, List<Method> writes, List<Method> verifies,
                                                              List<Method> encodeViews, List<Method> encodeStrings,
                                                              List<Method> stampSims, List<Method> stampPermits) {
        Object reference = invokeObject(read, null, new Object[]{victim});
        if (reference == null || reference.getClass() != frameType) return null;
        Object rawTimeline = readFieldQuietly(timeline, reference);
        if (!(rawTimeline instanceof int[] tl) || tl.length < 4) return null;
        float health = healthOf(victim);
        if (Float.isNaN(health)) return null;
        List<FrameFieldRef> refs = frameFieldRefs(reference);
        FrameFieldRef stringRef = firstOfType(refs, String.class);
        FrameFieldRef tagRef = firstOfType(refs, CompoundTag.class);
        List<FrameFieldRef> longs = longFields(refs);
        if (stringRef == null || tagRef == null || longs.size() < 3) return null;
        String stringValue = (String) stringRef.value();
        Object tagValue = tagRef.value();

        // 值位点：用「值位 → 显示位」纯函数反推
        Method display = displayMap(victim, frameType.getEnclosingClass(), tl, health);
        if (display == null) return null;
        Integer valueIndex = null;
        for (int i = 0; i < tl.length; i++) {
            Object out = invokeObject(display, null, new Object[]{victim, tl[i]});
            if (out instanceof Number number && number.intValue() == Float.floatToRawIntBits(health)) {
                valueIndex = i;
                break;
            }
        }
        if (valueIndex == null) return null;
        int valueBits = tl[valueIndex];

        // 两个编码器的实参语义按类型固定
        CalibratedCall encodeView = null;
        for (Method method : encodeViews) {
            Class<?>[] params = method.getParameterTypes();
            int entityParam = paramAccepting(params, victim);
            int intParam = paramOfType(params, int.class, entityParam);
            if (entityParam < 0 || intParam < 0) continue;
            FrameSemantic[] slots = new FrameSemantic[params.length];
            slots[entityParam] = FrameSemantic.ENTITY;
            slots[intParam] = FrameSemantic.VALUE;
            encodeView = new CalibratedCall(method, slots);
            break;
        }
        if (encodeView == null) return null;
        CalibratedCall encodeString = null;
        for (Method method : encodeStrings) {
            Class<?>[] params = method.getParameterTypes();
            int entityParam = paramAccepting(params, victim);
            int intParam = paramOfType(params, int.class, entityParam);
            int tagParam = paramOfType(params, CompoundTag.class, entityParam);
            if (entityParam < 0 || intParam < 0 || tagParam < 0) continue;
            FrameSemantic[] slots = new FrameSemantic[params.length];
            slots[entityParam] = FrameSemantic.ENTITY;
            slots[intParam] = FrameSemantic.VALUE;
            slots[tagParam] = FrameSemantic.VIEW;
            encodeString = new CalibratedCall(method, slots);
            break;
        }
        if (encodeString == null) return null;

        // 模拟签名器：3 个 int 形参 ← 时间线上 3 个互不相同的下标；String/NBT 位按类型固定
        CalibratedCall stampSim = null;
        FrameFieldRef simStampRef = null;
        for (Method method : stampSims) {
            Class<?>[] params = method.getParameterTypes();
            int entityParam = paramAccepting(params, victim);
            int strParam = paramOfType(params, String.class, entityParam);
            int tagParam = paramOfType(params, CompoundTag.class, entityParam);
            int[] intParams = paramsOfType(params, int.class, entityParam);
            if (entityParam < 0 || strParam < 0 || tagParam < 0 || intParams.length != 3) continue;
            search:
            for (int a = 0; a < tl.length; a++) {
                for (int b = 0; b < tl.length; b++) {
                    if (b == a) continue;
                    for (int c = 0; c < tl.length; c++) {
                        if (c == a || c == b) continue;
                        Object[] args = emptyArgs(params.length);
                        args[entityParam] = victim;
                        args[strParam] = stringValue;
                        args[tagParam] = tagValue;
                        args[intParams[0]] = tl[a];
                        args[intParams[1]] = tl[b];
                        args[intParams[2]] = tl[c];
                        FrameFieldRef matched = refOfValue(longs, asLong(invokeObject(method, null, args)));
                        if (matched == null) continue;
                        stampSim = new CalibratedCall(method,
                                intLayout(params.length, entityParam, intParams, strParam, tagParam,
                                        new int[]{a, b, c}, tl, valueBits));
                        simStampRef = matched;
                        break search;
                    }
                }
            }
            if (stampSim != null) break;
        }
        if (stampSim == null) return null;

        // 死亡许可签名器：只需定位方法本身；许可位按「非死亡态恒为 0」定位
        CalibratedCall stampPermit = null;
        for (Method method : stampPermits) {
            Class<?>[] params = method.getParameterTypes();
            int entityParam = paramAccepting(params, victim);
            int strParam = paramOfType(params, String.class, entityParam);
            int tagParam = paramOfType(params, CompoundTag.class, entityParam);
            int longParam = paramOfType(params, long.class, entityParam);
            int[] intParams = paramsOfType(params, int.class, entityParam);
            if (entityParam < 0 || strParam < 0 || tagParam < 0 || longParam < 0 || intParams.length != 3) continue;
            FrameSemantic[] slots = new FrameSemantic[params.length];
            slots[entityParam] = FrameSemantic.ENTITY;
            slots[strParam] = FrameSemantic.STRING;
            slots[tagParam] = FrameSemantic.VIEW;
            slots[longParam] = FrameSemantic.ISSUED;
            copyIntSemantics(slots, intParams, stampSim.slots());
            stampPermit = new CalibratedCall(method, slots);
            break;
        }
        if (stampPermit == null) return null;
        FrameFieldRef permitRef = null;
        FrameFieldRef issuedRef = null;
        for (FrameFieldRef ref : longs) {
            if (ref.slot().equals(simStampRef.slot())) continue;
            if (permitRef == null && ref.value() instanceof Number number && number.longValue() == 0L) {
                permitRef = ref;
            } else if (issuedRef == null) {
                issuedRef = ref;
            }
        }
        if (permitRef == null || issuedRef == null) {
            probeLog("状态帧直写：参考帧不是「非死亡态」，无法定位毫微秒 / 死亡许可位");
            return null;
        }

        // 收拢「语义 → 帧内位点」表 + 与值位同值的位点 + 含义待定的位点
        Map<FrameSemantic, FrameSlot> slots = new HashMap<>();
        slots.put(FrameSemantic.STRING, stringRef.slot());
        slots.put(FrameSemantic.VIEW, tagRef.slot());
        slots.put(FrameSemantic.SIMULATION_STAMP, simStampRef.slot());
        slots.put(FrameSemantic.DEATH_PERMIT, permitRef.slot());
        slots.put(FrameSemantic.ISSUED, issuedRef.slot());
        // 与值位同值的时间线位点：校验要求时刻相等，构建时一起更新
        List<FrameSlot> equalSlots = new ArrayList<>(4);
        List<FrameSlot> ambiguous = new ArrayList<>(4);
        for (int i = 0; i < tl.length; i++) {
            FrameSlot slot = FrameSlot.of(timeline, i);
            if (tl[i] == valueBits) equalSlots.add(slot);
            else ambiguous.add(slot);
        }
        if (ambiguous.isEmpty()) return null;

        return new StateFrameHandle(frameType, create, createSlots, equalSlots, slots, ambiguous, read, writes, verifies,
                encodeView, encodeString, stampSim, stampPermit,
                fitValueMap(victim, display, valueBits, health));
    }

    /** 按「int 形参位置 ← 时间线下标」生成语义表；每个形参位都必须填上语义 */
    private static FrameSemantic[] intLayout(int length, int entityParam, int[] intParams,
                                             int strParam, int tagParam, int[] indices, int[] tl, int valueBits) {
        FrameSemantic[] slots = new FrameSemantic[length];
        slots[entityParam] = FrameSemantic.ENTITY;
        slots[strParam] = FrameSemantic.STRING;
        slots[tagParam] = FrameSemantic.VIEW;
        int firstOther = -1;
        int secondOther = -1;
        for (int k = 0; k < 3; k++) {
            if (tl[indices[k]] == valueBits) {
                slots[intParams[k]] = FrameSemantic.VALUE;
            } else if (firstOther < 0) {
                firstOther = k;
            } else {
                secondOther = k;
            }
        }
        if (firstOther >= 0 && secondOther < 0) {
            slots[intParams[firstOther]] = FrameSemantic.TRANSITION;
        } else if (secondOther >= 0) {
            boolean firstIsTransition = Math.abs(tl[indices[firstOther]]) <= Math.abs(tl[indices[secondOther]]);
            slots[intParams[firstOther]] = firstIsTransition ? FrameSemantic.TRANSITION : FrameSemantic.PREVIOUS;
            slots[intParams[secondOther]] = firstIsTransition ? FrameSemantic.PREVIOUS : FrameSemantic.TRANSITION;
        }
        return slots;
    }

    /** 把 stampSim 的 int 位语义原样搬到 stampPermit 的 int 位上（两者取值相同） */
    private static void copyIntSemantics(FrameSemantic[] target, int[] targetParams, FrameSemantic[] source) {
        int cursor = 0;
        for (int i = 0; i < source.length && cursor < targetParams.length; i++) {
            FrameSemantic semantic = source[i];
            if (semantic == null || semantic == FrameSemantic.ENTITY
                    || semantic == FrameSemantic.STRING || semantic == FrameSemantic.VIEW) {
                continue;
            }
            target[targetParams[cursor++]] = semantic;
        }
    }

    /** 找「值位 → 显示位」纯函数：静态 {@code (实体,int) -> int}，且对时间线里某个值正好返回当前血量的位 */
    private static Method displayMap(LivingEntity victim, Class<?> manager, int[] timeline, float health) {
        if (manager == null) return null;
        int expected = Float.floatToRawIntBits(health);
        for (Method method : declaredStaticMethods(manager)) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[1] != int.class || method.getReturnType() != int.class) continue;
            if (paramAccepting(params, victim) < 0) continue;
            for (int bits : timeline) {
                Object out = invokeObject(method, null, new Object[]{victim, bits});
                if (out instanceof Number number && number.intValue() == expected) return method;
            }
        }
        return null;
    }

    /** 值位映射：采样拟合「帧值 ↔ 观察血量」的仿射关系，拟合不出时取默认约定 */
    private static ValueMap fitValueMap(LivingEntity victim, Method display, int valueBits, float health) {
        if (display == null) return ValueMap.DEFAULT;
        double base = Float.intBitsToFloat(valueBits);
        double delta = Math.max(1.0, Math.abs(base) * 0.01);
        for (int step = 0; step < STATE_VALUE_PROBE_STEPS; step++, delta *= 10.0) {
            double probe = base + delta;
            if (!Double.isFinite(probe)) break;
            Object out = invokeObject(display, null, new Object[]{victim, Float.floatToRawIntBits((float) probe)});
            if (!(out instanceof Number number)) continue;
            double displayed = Float.intBitsToFloat(number.intValue());
            if (!Double.isFinite(displayed) || Math.abs(displayed - health) < PROBE_EPSILON) continue;
            double scale = (displayed - health) / (probe - base);
            if (!Double.isFinite(scale) || Math.abs(scale) < 1.0E-6) continue;
            return new ValueMap(scale, health - scale * base);
        }
        return ValueMap.DEFAULT;
    }

    /** 帧对象自身上、以及其「模组类」字段（深度 1，覆盖 payload.stringPayload 这类嵌套）里的全部字段 */
    private static List<FrameFieldRef> frameFieldRefs(Object frame) {
        List<FrameFieldRef> refs = new ArrayList<>();
        for (Field field : declaredFieldsOf(frame.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || cannotAccess(field)) continue;
            Object value = readFieldQuietly(field, frame);
            refs.add(new FrameFieldRef(FrameSlot.of(field), field.getType(), value));
            if (value == null || !isModOwnedClass(value.getClass())) continue;
            for (Field inner : declaredFieldsOf(value.getClass())) {
                if (Modifier.isStatic(inner.getModifiers()) || cannotAccess(inner)) continue;
                refs.add(new FrameFieldRef(FrameSlot.nested(field, inner), inner.getType(),
                        readFieldQuietly(inner, value)));
            }
        }
        return refs;
    }

    /** 模组自己的类（不是 JDK / 原版 / Forge）—— 用于决定“要不要往这层对象里再看一层字段” */
    private static boolean isModOwnedClass(Class<?> type) {
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
                && !name.startsWith("sun.") && !isVanillaClass(type);
    }

    private static Field[] declaredFieldsOf(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private static Object readFieldQuietly(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    private static FrameSlot locateTimelineValue(Field timeline, Object frame, int wanted) {
        if (readFieldQuietly(timeline, frame) instanceof int[] values) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == wanted) return FrameSlot.of(timeline, i);
            }
        }
        return null;
    }

    private static FrameFieldRef firstOfType(List<FrameFieldRef> refs, Class<?> type) {
        for (FrameFieldRef ref : refs) {
            if (ref.type() == type) return ref;
        }
        return null;
    }

    /** 帧里的 {@code long} 字段（模拟签名 / 死亡许可 / 毫微秒 的定位全靠它们的取值） */
    private static List<FrameFieldRef> longFields(List<FrameFieldRef> refs) {
        List<FrameFieldRef> found = new ArrayList<>();
        for (FrameFieldRef ref : refs) {
            if (ref.type() == long.class) found.add(ref);
        }
        return found;
    }

    private static FrameFieldRef refOfValue(List<FrameFieldRef> refs, long value) {
        for (FrameFieldRef ref : refs) {
            if (ref.value() instanceof Number number && number.longValue() == value) return ref;
        }
        return null;
    }

    private static Object[] emptyArgs(int length) {
        return new Object[length];
    }

    private static int paramAccepting(Class<?>[] params, Object instance) {
        for (int i = 0; i < params.length; i++) {
            if (!params[i].isPrimitive() && params[i].isInstance(instance)) return i;
        }
        return -1;
    }

    /** 形参构成是否正好是「一个实体 + 指定数量 int/long + 可选 String/CompoundTag」 */
    private static boolean paramsMatch(Class<?>[] params, LivingEntity victim,
                                       int intCount, int longCount, boolean withString, boolean withTag) {
        int ints = 0;
        int longs = 0;
        int strings = 0;
        int tags = 0;
        int entities = 0;
        for (Class<?> param : params) {
            if (param == int.class) ints++;
            else if (param == long.class) longs++;
            else if (param == String.class) strings++;
            else if (param == CompoundTag.class) tags++;
            else if (param.isInstance(victim)) entities++;
            else return false;
        }
        return ints == intCount && longs == longCount && strings == (withString ? 1 : 0)
                && tags == (withTag ? 1 : 0) && entities == 1;
    }

    private static int paramOfType(Class<?>[] params, Class<?> type, int skip) {
        for (int i = 0; i < params.length; i++) {
            if (i != skip && params[i] == type) return i;
        }
        return -1;
    }

    private static int[] paramsOfType(Class<?>[] params, Class<?> type, int skip) {
        int count = 0;
        for (int i = 0; i < params.length; i++) {
            if (i != skip && params[i] == type) count++;
        }
        int[] found = new int[count];
        int cursor = 0;
        for (int i = 0; i < params.length; i++) {
            if (i != skip && params[i] == type) found[cursor++] = i;
        }
        return found;
    }

    private static CompoundTag sentinelTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ma$sentinel", STATE_SENTINEL_STRING);
        return tag;
    }

    /** 帧对象上的 {@code int[]} 实例字段（时间线）；字段名不参与判定 */
    private static Field intArrayField(Class<?> frameType) {
        Field[] declared;
        try {
            declared = frameType.getDeclaredFields();
        } catch (Throwable ignored) {
            return null;
        }
        for (Field field : declared) {
            if (field.getType() != int[].class || Modifier.isStatic(field.getModifiers())) continue;
            return field;
        }
        return null;
    }

    /** 打开成员访问权限，打不开时返回 true */
    private static boolean cannotAccess(AccessibleObject member) {
        try {
            member.setAccessible(true);
            return false;
        } catch (Throwable ignored) {
            // 命名模块未开放该包，走兜底
        }
        if (!openAccessOverride()) return true;
        try {
            member.setAccessible(true);
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 给定方法里有任何一个打不开就返回 true */
    private static boolean cannotAccessAll(List<Method> methods) {
        for (Method method : methods) {
            if (cannotAccess(method)) return true;
        }
        return false;
    }

    private static boolean openAccessOverride() {
        if (accessOverrideOpened) return true;
        Unsafe instance = getUnsafe();
        if (instance == null) return false;
        try {
            Field override = AccessibleObject.class.getDeclaredField("override");
            instance.putBoolean(instance.staticFieldBase(override), instance.staticFieldOffset(override), true);
            accessOverrideOpened = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

}

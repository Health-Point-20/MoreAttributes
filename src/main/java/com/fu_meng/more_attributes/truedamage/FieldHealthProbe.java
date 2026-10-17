package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/**
 * 以字段与表条目为载体的写入层：类字段探针、深度对象图探针、计数器型血量、引用类静态表；探针以行为验证为准。
 */
final class FieldHealthProbe {

    record DeepHealthSlots(List<HealthSlot> direct, List<HealthSlot> damageTaken) {
    }

    /** 深度探针结果缓存（同 tick 内复用，tick 结束清空） */
    static final Map<LivingEntity, DeepHealthSlots> DEEP_HEALTH_CACHE = new ConcurrentHashMap<>();

    private static final int DEEP_MAX_DEPTH = 6;

    private static final int DEEP_MAX_NODES = 2000;

    private static final int DEEP_MAX_COLLECTION_SIZE = 64;

    private static final int DEEP_MAX_BYTE_ARRAY = 256;

    /** 深度探针写入血量：直接位点写目标值，累计伤害位点加上扣血量 */
    static void deepWriteHealth(LivingEntity victim, float healthBefore, float healthAfter) {
        DeepHealthSlots slots = computeOutsideLock(
                DEEP_HEALTH_CACHE, victim, () -> deepFindHealthSlots(victim));
        // 「总量 - 已消耗」型派生血量：只写消耗位点
        if (writeDerivedHealth(victim, slots.direct(), slots.damageTaken(), healthAfter)) return;
        writeHealthSlots(slots.direct(), slots.damageTaken(), healthAfter, healthBefore - healthAfter);
    }

    /** 「总量 − 已消耗」型派生血量：只写消耗位点，未达成则还原 */
    static boolean writeDerivedHealth(LivingEntity victim, List<HealthSlot> direct,
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

    /** 找出「总量 − 已消耗」结构里的总量读数；不适用时返回 NaN */
    static double derivedTotal(LivingEntity victim, List<HealthSlot> direct, List<HealthSlot> taken) {
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

    static void writeHealthSlots(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                         float healthAfter, float damage) {
        for (HealthSlot slot : direct) {
            try {
                slot.write(healthAfter);
            } catch (Throwable ignored) {
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
    static DeepHealthSlots deepFindHealthSlots(LivingEntity victim) {
        List<HealthSlot> direct = new ArrayList<>();
        List<HealthSlot> damageTaken = new ArrayList<>();
        // 实体自身数值字段已由类字段探针识别，这里复用结论
        HealthBackingFields backing = identifyHealthBackingFields(victim);
        for (Field field : backing.directHealth()) {
            direct.add(fieldSlot(victim, field));
        }
        for (Field field : backing.damageTaken()) {
            damageTaken.add(fieldSlot(victim, field));
        }
        float baseHealth = healthOf(victim);
        if (Float.isNaN(baseHealth)) return new DeepHealthSlots(new ArrayList<>(), new ArrayList<>());
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
                            collectHealthSlot(direct, damageTaken, fieldSlot(node, field), victim, baseHealth);
                        }
                    } else if (isHealthCapableType(type) && isHealthLikeName(field.getName())) {
                        // 名字像血量的整型/包装字段（血量被存成 int/long 的模组）
                        collectHealthSlot(direct, damageTaken, fieldSlot(node, field), victim, baseHealth);
                    } else if (!type.isPrimitive()) {
                        Object value = field.get(node);
                        if (value instanceof Number) {
                            // 只有声明类型本身是具体数值类型的字段才可写：声明 Object/Number 的字段（如 SynchedEntityData$DataItem.value，
                            // 运行时 Byte/Integer）按声明类型装箱会写进 Float/Double，与 BYTE/INT 序列化器冲突，模组强转时崩。
                            if (!isWritableFieldType(type)) continue;
                            collectHealthSlot(direct, damageTaken, fieldSlot(node, field), victim, baseHealth);
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

    /** 静态作用域位点收集：静态镜像字段、静态容器内部、以及“以实体为键”的表条目 */
    static void collectStaticHealthSlot(List<HealthSlot> direct, List<HealthSlot> damageTaken,
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
                collectHealthSlot(direct, damageTaken, fieldSlot(null, field), victim, baseHealth);
                return;
            }
            // 名字像血量的静态整型/包装数值字段（例如 static int HEALTS 这类血量镜像）
            if (isHealthCapableType(type) && isHealthLikeName(field.getName())) {
                collectHealthSlot(direct, damageTaken, fieldSlot(null, field), victim, baseHealth);
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
                return;
            }
            // 模组自有容器类静态字段（非 java.util.Map/Iterable，如自带槽位表的 OpaqueStore）交给深度遍历下钻，探其内部血量载体。
            if (depth < DEEP_MAX_DEPTH && isModClass(type)) {
                Object value = field.get(null);
                if (value != null && visited.add(value)) queue.add(new Object[]{value, depth + 1});
            }
        } catch (Throwable ignored) {
        }
    }

    /** 表里“以该实体（或其 UUID）为键”的位点：逐键做行为验证，命中才保留 */
    static void collectMapEntitySlots(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                              Map<?, ?> map, LivingEntity victim, float baseHealth) {
        for (Object key : new Object[]{victim, victim.getUUID()}) {
            // 与静态表层共用 TableEntrySlot：优先走 Map.Entry.setValue 改写
            HealthSlot slot = tableEntrySlot(map, key);
            if (slot == null) continue;
            collectHealthSlot(direct, damageTaken, slot, victim, baseHealth);
        }
    }

    static void collectHealthSlot(List<HealthSlot> direct, List<HealthSlot> damageTaken,
                                          HealthSlot slot, LivingEntity victim, float baseHealth) {
        int direction = probeHealthSlot(victim, slot, baseHealth);
        if (direction == PROBE_DIRECT) {
            direct.add(slot);
        } else if (direction == PROBE_TAKEN) {
            damageTaken.add(slot);
        }
    }

    /** 行为验证：扰动位点观察 {@code getHealth()} 走向后还原，无变化再反向补探 */
    static int probeHealthSlot(LivingEntity victim, HealthSlot slot, float baseHealth) {
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

    /**
     * 写入 {@code original + delta} 观察血量走向后还原；优先看 {@code getHealth()}，假血量实体再补看原版同步血量镜像。
     */
    static int perturbAndClassify(LivingEntity victim, HealthSlot slot, double original, double delta,
                                          float baseHealth) {
        double perturbed = original + delta;
        if (!Double.isFinite(perturbed)) return PROBE_MISS;
        // 扰动值夹在 (0, 最大生命]，且只在不会反转扰动方向时上钳
        double max = maxHealthOf(victim);
        if (delta > 0.0 && Double.isFinite(max) && max > original && perturbed > max) {
            perturbed = max;
        }
        if (perturbed <= 0.0) {
            // 这是探针热路径（一次类字段探测可达数千次），先判开关再组装实参
            if (MAConfig.debugProbes) {
                probeLog("扰动跳过（会落到 <= 0）：位点原值={} delta={}", original, delta);
            }
            return PROBE_MISS;
        }
        float mirrorBefore = readVanillaHealthData(victim);
        boolean watchMirror = !Float.isNaN(mirrorBefore)
                && healthGetterDetached(victim, healthOf(victim), mirrorBefore);
        try {
            slot.write(perturbed);
        } catch (Throwable ignored) {
            return PROBE_MISS;
        }
        try {
            float probed = healthOf(victim);
            if (!Float.isNaN(probed)) {
                if (probed > baseHealth + PROBE_EPSILON) return PROBE_DIRECT;
                if (probed < baseHealth - PROBE_EPSILON) return PROBE_TAKEN;
            }
            if (watchMirror) {
                float mirrorAfter = readVanillaHealthData(victim);
                if (!Float.isNaN(mirrorAfter)) {
                    if (mirrorAfter > mirrorBefore + PROBE_EPSILON) return PROBE_DIRECT;
                    if (mirrorAfter < mirrorBefore - PROBE_EPSILON) return PROBE_TAKEN;
                }
            }
            return PROBE_MISS;
        } finally {
            try {
                slot.write(original);
            } catch (Throwable ignored) {
                // 还原失败则保持扰动值
            }
        }
    }

    /** 深度遍历的候选字段（含父类链、非合成，实例字段已 setAccessible），按类缓存 */
    static List<Field> deepCandidateFields(Class<?> clazz) {
        return computeOutsideLock(DEEP_FIELD_CACHE, clazz, () -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current : hierarchy(clazz)) {
                for (Field field : declaredFieldsOf(current)) {
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

    /** 每个类只探测一次：血量的承载字段（直接血量 / 累计伤害 / 同步数据） */
    private static final Map<Class<?>, HealthBackingFields> HEALTH_FIELD_CACHE = new ConcurrentHashMap<>();

    record HealthBackingFields(List<Field> directHealth, List<Field> damageTaken,
                                       List<EntityDataAccessor<?>> directData,
                                       List<EntityDataAccessor<?>> takenData) {
    }

    /**
     * 收集实体类（含父类）中“非原版/非 Forge、非 static/final”的数值字段；浮点无条件收，整型/包装字段仅当名字像血量时才收，避免扰动计时器与冷却。
     */
    static List<Field> collectCandidateFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (isVanillaClass(current)) continue;
            for (Field field : declaredFieldsOf(current)) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic()) continue;
                Class<?> fieldType = field.getType();
                boolean floating = fieldType == float.class || fieldType == double.class;
                if (!floating && !(isHealthCapableType(fieldType) && isHealthLikeName(field.getName()))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        // 把实体继承链常量池里出现过的字段名前置；只排序不删除，池差集看不到方法内部的字段引用。
        if (MAConfig.trueDamageMode.usesPoolIntel() && fields.size() > 1) {
            Set<String> poolFields = poolReferencedFieldNames(clazz);
            if (!poolFields.isEmpty()) {
                fields.sort(Comparator.comparingInt((Field field) -> poolFields.contains(field.getName()) ? 0 : 1));
            }
        }
        return fields;
    }

    /** L0.3：实体继承链的常量池里出现过的字段名集合（供候选前置） */
    private static Set<String> poolReferencedFieldNames(Class<?> clazz) {
        Set<String> names = new HashSet<>();
        for (Class<?> current : hierarchy(clazz)) {
            for (String ref : PoolFingerprint.of(current).fieldRefs()) {
                int dot = ref.indexOf('.');
                int colon = dot < 0 ? -1 : ref.indexOf(':', dot + 1);
                if (dot > 0 && colon > dot) {
                    names.add(ref.substring(dot + 1, colon));
                }
            }
        }
        return names;
    }

    /** 探针不可用（血量读不出来）时的占位结果：语义是「探不了」而不是「没有」，因此不进缓存 */
    private static final HealthBackingFields EMPTY_HEALTH_BACKING =
            new HealthBackingFields(List.of(), List.of(), List.of(), List.of());

    /** 用行为探针识别承载血量的字段，按类缓存 */
    static HealthBackingFields identifyHealthBackingFields(LivingEntity victim) {
        Class<?> type = victim.getClass();
        HealthBackingFields cached = HEALTH_FIELD_CACHE.get(type);
        if (cached != null) return cached;
        float healthBeforeProbe = healthOf(victim);
        // 血量读不出时探针全落 PROBE_MISS：该空结论绝不能进按类缓存，否则该类永久失去字段层写入能力。
        if (Float.isNaN(healthBeforeProbe)) return EMPTY_HEALTH_BACKING;
        HealthBackingFields computed = probeHealthBackingFields(victim, healthBeforeProbe);
        HealthBackingFields raced = HEALTH_FIELD_CACHE.putIfAbsent(type, computed);
        return raced != null ? raced : computed;
    }

    static HealthBackingFields probeHealthBackingFields(LivingEntity victim, float healthBeforeProbe) {
        List<Field> direct = new ArrayList<>();
        List<Field> taken = new ArrayList<>();
        List<EntityDataAccessor<?>> directData = new ArrayList<>();
        List<EntityDataAccessor<?>> takenData = new ArrayList<>();
        List<Field> candidates = collectCandidateFields(victim.getClass());

        for (Field field : candidates) {
            int direction = probeHealthSlot(victim, fieldSlot(victim, field), healthBeforeProbe);
            if (direction == PROBE_DIRECT) {
                direct.add(field);
            } else if (direction == PROBE_TAKEN) {
                taken.add(field);
            }
        }

        try {
            SynchedEntityData entityData = victim.getEntityData();
            for (EntityDataAccessor<?> accessor : collectDataAccessors(victim.getClass())) {
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
    }

    /** 收集实体类（含父类）中“非原版/非 Forge 类声明的静态 EntityDataAccessor”，作为同步数据候选 */
    static List<EntityDataAccessor<?>> collectDataAccessors(Class<?> clazz) {
        List<EntityDataAccessor<?>> accessors = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (isVanillaClass(current)) continue;
            for (Field field : declaredFieldsOf(current)) {
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
    static void forceSetHealthFields(LivingEntity victim, float healthBefore, float healthAfter) {
        HealthBackingFields backing = identifyHealthBackingFields(victim);
        float damage = healthBefore - healthAfter;
        List<HealthSlot> direct = new ArrayList<>(backing.directHealth().size() + backing.directData().size());
        List<HealthSlot> taken = new ArrayList<>(backing.damageTaken().size() + backing.takenData().size());
        for (Field field : backing.directHealth()) {
            direct.add(fieldSlot(victim, field));
        }
        for (Field field : backing.damageTaken()) {
            taken.add(fieldSlot(victim, field));
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
                CounterWriter writer = MAConfig.trueDamageMode.invokesCounterWriters()
                        ? counterWriter(victim, slot) : null;
                taken.add(writer == null ? slot : new CounterWriterSlot(slot, writer, victim));
            }
        } catch (Throwable ignored) {
            // 取同步数据失败时只处理字段位点
        }
        if (writeDerivedHealth(victim, direct, taken, healthAfter)) return;
        writeHealthSlots(direct, taken, healthAfter, damage);
    }

    private static final CounterWriter NO_COUNTER_WRITER = new CounterWriter(null, 0, 0, null);

    private static final Map<Class<?>, CounterWriter> COUNTER_WRITER_CACHE = new ConcurrentHashMap<>();

    record CounterWriter(Method method, int entityIndex, int numberIndex, Class<?> numberType) {
    }

    /** 计数器位点写入口：优先走“设定计数”入口，保持私有字段与同步数据一致 */
    record CounterWriterSlot(HealthSlot backing, CounterWriter writer, LivingEntity victim)
            implements HealthSlot {
        @Override
        public double read() {
            return backing.read();
        }

        @Override
        public void write(double value) {
            Object[] args = new Object[2];
            args[writer.entityIndex()] = victim;
            // 模组写入口数值形参被擦除时按目标位点真实类型装箱：猜成 Double 会在模组强转时抛 ClassCastException。
            Class<?> boxType = writer.numberType();
            if (boxType == null || boxType == Object.class || boxType == Number.class
                    || boxType == Comparable.class || boxType == java.io.Serializable.class) {
                boxType = slotValueType(backing);
            }
            if (!isNumericBoxingType(boxType)) {
                // 数值形参被擦除且目标位点类型判定不出：不猜，直接放弃写入
                return;
            }
            args[writer.numberIndex()] = boxNumber(boxType, value);
            try {
                writer.method().invoke(null, args);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 找“绝对设定计数”的静态入口，按行为验证确认写入与还原都成立 */
    static CounterWriter counterWriter(LivingEntity victim, HealthSlot counter) {
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

    static CounterWriter findCounterWriter(Class<?> clazz, LivingEntity victim,
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

    /** 表位点的行为结论缓存：实体类 →（表字段 + 键类型）→ 探针结论 */
    private static final Map<Class<?>, Map<TableSlotKey, TableSlotProbe>> TABLE_SLOT_PROBE_CACHE =
            new ConcurrentHashMap<>();

    /** 键类型遍历顺序：false = 实体对象，true = 实体 UUID */
    private static final boolean[] KEY_IS_UUID = {false, true};

    /** 表位点标识：同一张表的不同键类型分开分类 */
    record TableSlotKey(Field table, boolean uuidKey) {
    }

    /** 表位点的探针结论（只含类级稳定数据） */
    record TableSlotProbe(int direction, boolean perturbsMaxHealth) {
    }

    /** 在可达模组类的静态表里找“以该实体（或其 UUID）为键”的条目并写入全部命中表 */
    static void writeReferencedStaticTables(LivingEntity victim, float healthAfter) {
        List<Field> tables = computeOutsideLock(REFERENCED_TABLE_CACHE, victim.getClass(),
                () -> findReferencedTables(victim.getClass()));
        if (tables.isEmpty()) return;

        float baseHealth = healthOf(victim);
        // 读不出血量就无法做行为验证，本层直接放弃
        if (Float.isNaN(baseHealth)) return;
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
            }
        }
        // 没有任何表被确认是血量存储时不动手
        if (direct.isEmpty() && taken.isEmpty()) return;
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
            }
        }
    }

    /** 本次结算用到的“未确认位点 + 其探针结论”（只活在单次调用里） */
    record TableSlotEntry(HealthSlot slot, TableSlotProbe probe) {
    }

    /** 取（或首次探测）某表位点的行为结论，按「实体类 + 表字段 + 键类型」缓存 */
    static TableSlotProbe tableSlotProbe(LivingEntity victim, Field table, boolean uuidKey,
                                                 HealthSlot slot, float baseHealth) {
        Map<TableSlotKey, TableSlotProbe> perClass = computeOutsideLock(
                TABLE_SLOT_PROBE_CACHE, victim.getClass(), ConcurrentHashMap::new);
        TableSlotKey slotKey = new TableSlotKey(table, uuidKey);
        TableSlotProbe cached = perClass.get(slotKey);
        if (cached != null) return cached;
        int direction = probeHealthSlot(victim, slot, baseHealth);
        MaxHealthProbe maxProbe = perturbsMaxHealth(victim, slot);
        if (maxProbe == null) {
            // 探针写入/还原失败：结论不可信，本次按「疑似最大生命表」处理且不写该位点，也不进缓存。
            return new TableSlotProbe(PROBE_MISS, true);
        }
        TableSlotProbe computed = new TableSlotProbe(direction, maxProbe.perturbs());
        // 满血锚点下的结论照常缓存：探针不可信已由返回 null 保护，满血时跳过缓存会导致每击重探。
        TableSlotProbe raced = perClass.putIfAbsent(slotKey, computed);
        return raced != null ? raced : computed;
    }

    /** “以该实体（或其 UUID）为键”的表条目位点；表里没有该键则返回 null */
    @SuppressWarnings("unchecked")
    static HealthSlot tableEntrySlot(Map<?, ?> map, Object key) {
        try {
            if (!map.containsKey(key)) return null;
            Object value = map.get(key);
            return new TableEntrySlot((Map<Object, Object>) map, key,
                    value instanceof Number number ? number.getClass() : Double.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 「扰动该位点会不会改动最大生命」的探针结论；null 表示结论不可信（写入/还原失败），不得缓存 */
    record MaxHealthProbe(boolean perturbs) {
        static final MaxHealthProbe PERTURBS = new MaxHealthProbe(true);
        static final MaxHealthProbe STABLE = new MaxHealthProbe(false);
    }

    /** 扰动位点后观察 {@code getMaxHealth()} 是否变化；返回 {@code null} 表示探针不可信 */
    static MaxHealthProbe perturbsMaxHealth(LivingEntity victim, HealthSlot slot) {
        double original;
        try {
            original = slot.read();
        } catch (Throwable ignored) {
            return null;
        }
        if (Double.isNaN(original)) return null;
        double delta = Math.max(1.0, Math.abs(original) * 0.01);
        float maxBefore = maxHealthOf(victim);
        if (Float.isNaN(maxBefore)) return null;
        boolean perturbs = false;
        boolean reliable = true;
        try {
            slot.write(original + delta);
            if (!matches(maxHealthOf(victim), maxBefore)) {
                perturbs = true;
            } else {
                // 负向扰动可能把位点写到 <= 0，而写入真实发生（会触发模组死亡判定）：
                // 放弃该次扰动，宁可漏判不可误杀。
                double down = original - delta;
                if (down > 0.0) {
                    slot.write(down);
                    if (!matches(maxHealthOf(victim), maxBefore)) perturbs = true;
                }
            }
        } catch (Throwable ignored) {
            // 扰动本身失败：结论不可信
            reliable = false;
        } finally {
            try {
                slot.write(original);
            } catch (Throwable ignored) {
                // 还原失败：位点可能停在扰动值，结论不得进入缓存
                reliable = false;
            }
        }
        if (!reliable) return null;
        return perturbs ? MaxHealthProbe.PERTURBS : MaxHealthProbe.STABLE;
    }

    /** 静态表条目位点：优先走条目自身的 {@code setValue}，依次退回 Unsafe 与常规 put */
    static final class TableEntrySlot implements HealthSlot {
        private final Map<Object, Object> map;
        private final Object key;
        private final Class<?> valueType;
        /** 匹配条目（单次结算内会反复读写，避免每次写都全表扫描 + 重新分配） */
        private List<Map.Entry<?, ?>> entries;

        TableEntrySlot(Map<Object, Object> map, Object key, Class<?> valueType) {
            this.map = map;
            this.key = key;
            this.valueType = valueType;
        }

        @Override
        public double read() {
            try {
                Object value = map.get(key);
                if (value instanceof Number number) return number.doubleValue();
            } catch (Throwable ignored) {
                // 读取被守卫拦截时退回直接读条目字段
            }
            List<Map.Entry<?, ?>> matched = matchingEntries();
            if (matched.isEmpty()) return Double.NaN;
            OptionalLong offset = entryValueOffset(matched.get(0).getClass());
            if (offset.isEmpty()) return Double.NaN;
            Object stored = unsafeGetObject(matched.get(0), offset.getAsLong());
            return stored instanceof Number number ? number.doubleValue() : Double.NaN;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void write(double value) {
            Object boxed;
            // 硬约束：不能改变条目里值的运行时类型。表常是擦除泛型（{@code Map<UUID, Object>} 实际放 Float），
            // 写进 Double 后模组一强转就崩；按当前实际值类型重新装箱，类型对不上就放弃写入。
            Object current = safeGetStored();
            if (current instanceof Number) {
                boxed = boxNumber(current.getClass(), value);
            } else {
                // 条目无当前数值（或类型未知）：猜 Float/Double 会写坏强类型表条目的运行时类型，宁可不写。
                probeLog("静态表条目类型无法判定，跳过写入（防崩）：{} 现={}",
                        key, current == null ? "null" : current.getClass().getSimpleName());
                return;
            }
            List<Map.Entry<?, ?>> matched = matchingEntries();
            if (!matched.isEmpty()) {
                boolean wrote = false;
                for (Map.Entry<?, ?> entry : matched) {
                    try {
                        ((Map.Entry<Object, Object>) entry).setValue(boxed);
                        wrote = true;
                    } catch (Throwable ignored) {
                    }
                }
                if (wrote) return;
                // 偏移必须按每个条目自己的类计算：异构 entrySet 用首个条目的偏移写别的类属内存破坏级错误。
                Map<Class<?>, OptionalLong> offsets = new HashMap<>(2);
                for (Map.Entry<?, ?> entry : matched) {
                    OptionalLong offset = offsets.get(entry.getClass());
                    if (offset == null) {
                        offset = entryValueOffset(entry.getClass());
                        offsets.put(entry.getClass(), offset);
                    }
                    if (offset.isPresent()) {
                        unsafePutObject(entry, offset.getAsLong(), boxed);
                        wrote = true;
                    }
                }
                if (wrote) return;
            }
            try {
                map.put(key, boxed);
            } catch (Throwable ignored) {
            }
        }

        /** 读条目里当前实际存的对象（读不到返回 null），用于按真实类型装箱 */
        private Object safeGetStored() {
            try {
                Object value = map.get(key);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
            List<Map.Entry<?, ?>> matched = matchingEntries();
            if (matched.isEmpty()) return null;
            OptionalLong offset = entryValueOffset(matched.get(0).getClass());
            if (offset.isEmpty()) return null;
            return unsafeGetObject(matched.get(0), offset.getAsLong());
        }

        private List<Map.Entry<?, ?>> matchingEntries() {
            List<Map.Entry<?, ?>> cached = entries;
            if (cached != null) return cached;
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
            entries = matched;
            return matched;
        }
    }
}

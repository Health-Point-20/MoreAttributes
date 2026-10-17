package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.EncryptedHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageWarmup.*;

/**
 * 密文串血量层（档 3）：血量被一段现场标定出来的调用式保护时使用。先用已知明文/密文对反推编解码方法、主参数位置、实参来源与算子键表，再复算整串密文写回同步项；标定结果按实体类缓存。
 */
final class CipherHealthLayer {

    /** 存着密文的那个同步项（按实体类缓存） */
    private static final Map<Class<?>, EntityDataAccessor<?>> ENCRYPTED_HEALTH_ACCESSOR_CACHE = new ConcurrentHashMap<>();

    /** 密文同步项「没定位到」时的退避缓存 */
    static final NegativeCache<Class<?>> ENCRYPTED_HEALTH_ACCESSOR_NEGATIVE =
            new NegativeCache<>("密文同步项定位");

    /** 算子写键候选上限 / 参与扫描的可达模组类上限 */
    private static final int OPERATOR_MAX_KEYS = 256;

    private static final int OPERATOR_REFERENCED_CLASSES = 64;

    /** 密文候选池里最多额外扫多少个「预热已收割的本模组类」（见 {@link #cipherMethods}） */
    private static final int CIPHER_MAX_HARVESTED_CLASSES = 1024;

    /** 健康算子：持有它的字段 + 它的 {@code operate(Object[])} 方法，按实体类缓存 */
    private static final Map<Class<?>, OperatorPlan> OPERATOR_PLAN_CACHE = new ConcurrentHashMap<>();

    /** 算子发现 / 键表落空时的退避缓存 */
    static final NegativeCache<Class<?>> OPERATOR_PLAN_NEGATIVE =
            new NegativeCache<>("血量算子定位");

    static final NegativeCache<Class<?>> OPERATOR_KEY_NEGATIVE =
            new NegativeCache<>("血量算子键表");

    private static final Map<Class<?>, String> OPERATOR_KEY_CACHE = new ConcurrentHashMap<>();

    /** 诊断用：每个实体类只完整打印一次候选清单 */
    private static final Set<Class<?>> OPERATOR_DIAGNOSED = ConcurrentHashMap.newKeySet();

    private static final Set<Class<?>> CIPHER_DIAGNOSED = ConcurrentHashMap.newKeySet();

    record OperatorPlan(Field field, Method operate) {
    }

    /** 密文串血量层入口：先试同步项密文路径，再试算子路径 */
    static void writeViaHealthOperator(LivingEntity victim, float healthAfter) {
        probeLog("加密同步血量层进入：{} 目标={} 当前={}",
                victim.getClass().getName(), healthAfter, healthOf(victim));
        if (writeViaCipherHealth(victim, healthAfter)) return;
        writeViaOperatorEndpoint(victim, healthAfter);
    }

    /** 算子路径：字段持有 {@code operate(Object[])} 实现，按候选键逐个试写 */
    static void writeViaOperatorEndpoint(LivingEntity victim, float healthAfter) {
        Class<?> type = victim.getClass();
        diagnoseStaticContainers(victim);
        OperatorPlan plan = OPERATOR_PLAN_CACHE.get(type);
        if (plan == null) {
            if (OPERATOR_PLAN_NEGATIVE.blocked(type)) return;
            OperatorPlan found = discoverOperatorPlan(victim);
            if (found == null) {
                // 负结果退避：算子发现要对继承链上每个实例字段做无参调用验证
                OPERATOR_PLAN_NEGATIVE.mark(type);
                probeLog("第9层-算子：未定位到血量算子");
                return;
            }
            probeLog("健康算子层已定位算子：{}#{}", type.getName(), found.field().getName());
            OPERATOR_PLAN_CACHE.put(type, found);
            OPERATOR_PLAN_NEGATIVE.clear(type);
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
        if (OPERATOR_KEY_NEGATIVE.blocked(type)) return;
        for (String key : operatorKeys(type)) {
            if (key.equals(cached)) continue;
            if (tryOperatorKey(victim, plan, operator, key, healthAfter)) {
                OPERATOR_KEY_CACHE.put(type, key);
                OPERATOR_KEY_NEGATIVE.clear(type);
                return;
            }
        }
        // 键表整体没命中：退避，避免每一次命中都重跑「键 × 形状 × 载体」三重循环
        OPERATOR_KEY_NEGATIVE.mark(type);
    }

    /**
     * 用给定键把血量写为目标值：写后 getHealth 到位才算命中，否则还原回写入前的读数。类型安全是硬约束：读/写口背后常是强类型同步项
     * （如 {@code EntityDataAccessor<Integer> DATA_HURTCOOLDOWN}），写进不同运行时类型的值会永久改坏槽位类型，模组 {@code (Integer) get(...)} 强转时抛 ClassCastException，攻击与存档都崩；故只按当前读数的运行时类型构造唯一载体，还原时写回原始对象本身，写入后回读、类型漂移立刻原样还原并对该类拉黑。
     */
    static boolean tryOperatorKey(LivingEntity victim, OperatorPlan plan, Object operator,
                                          String key, float healthAfter) {
        Object rawSample = invokeObject(plan.operate(), operator, new Object[]{ NO_ARGS });
        float baseline = operatorNumber(rawSample);
        if (Float.isNaN(baseline)) return false;
        List<Object> payloads = operatorPayloads(healthAfter, rawSample);
        if (payloads.isEmpty()) {
            // 读数的类型不可知（null / 非数值非字符串）：不写，避免写坏模组的强类型槽位
            probeLog("健康算子层：读数类型不可知，跳过本层（{}）", victim.getClass().getName());
            return false;
        }
        // 形状表按当前载体形态计算；同一 (形状, 载体) 组合只走一次
        int shapeCount = operatorWriteShapes(victim, key, payloads.get(0)).size();
        for (int shape = 0; shape < shapeCount; shape++) {
            for (Object payload : payloads) {
                List<Object[]> shapes = operatorWriteShapes(victim, key, payload);
                if (shape >= shapes.size()) continue;
                writeOperatorArgs(plan, operator, shapes.get(shape));
                // 类型漂移检查：槽位本来是什么类型，回读就必须还是什么类型
                Object afterRaw = invokeObject(plan.operate(), operator, new Object[]{ NO_ARGS });
                if (typeDrifted(rawSample, afterRaw)) {
                    probeLog("健康算子层：写入导致槽位类型漂移（{} → {}），已原样还原并放弃该层：{}",
                            rawSample.getClass().getSimpleName(),
                            afterRaw.getClass().getSimpleName(),
                            victim.getClass().getName());
                    restoreOperatorShape(victim, plan, operator, key, rawSample, shape);
                    return false;
                }
                if (!stillAboveTarget(victim, healthAfter)) {
                    probeLog("健康算子层写入生效：{} 键={} 模板={} 目标={} 载体={} getHealth={}",
                            victim.getClass().getName(), key, shape, healthAfter,
                            payload.getClass().getSimpleName(), healthOf(victim));
                    return true;
                }
                // 没生效就把血量还原成写入前的读数（写回原始对象，不做任何重新装箱）
                restoreOperatorShape(victim, plan, operator, key, rawSample, shape);
            }
        }
        return false;
    }

    /** 用同一个「形状」把原始对象写回去；拿不到原始对象时退回按样本同型装箱 */
    private static void restoreOperatorShape(LivingEntity victim, OperatorPlan plan, Object operator,
                                             String key, Object rawSample, int shape) {
        if (rawSample == null) return;      // 拿不到原始对象就不动（避免用猜测类型写入）
        List<Object[]> restores = operatorWriteShapes(victim, key, rawSample);
        if (shape < restores.size()) {
            writeOperatorArgs(plan, operator, restores.get(shape));
        }
    }

    /**
     * 槽位运行时类型是否被写漂移了。只比数值包装类型是否同类：null 与出现/消失不算漂移
     * （模组自己也可能清空值），但 Double→Integer、Double→Float 这类强转必崩的变化必须拦住。
     */
    private static boolean typeDrifted(Object before, Object after) {
        if (before == null || after == null) return false;
        Class<?> a = before.getClass();
        Class<?> b = after.getClass();
        if (a == b) return false;
        // 数值包装之间的漂移一律视为危险（模组按声明类型强转）
        return isBoxedNumber(a) || isBoxedNumber(b) || a == String.class || b == String.class;
    }

    private static boolean isBoxedNumber(Class<?> type) {
        return type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                || type == Float.class || type == Double.class;
    }

    /** 按某个载体形态把数值装箱成同类型（还原与多形态尝试都用它） */
    static Object boxAs(Object sample, float value) {
        if (sample instanceof String) return plainText(value);
        if (sample instanceof Double) return (double) value;
        if (sample instanceof Float) return value;
        if (sample instanceof Integer) return (int) value;
        if (sample instanceof Long) return Math.round(value);
        if (sample instanceof Short) return (short) value;
        if (sample instanceof Byte) return (byte) value;
        // 样本类型不可知（null / 数组 / 自定义对象）→ 返回 null 表示不写。
        // 猜 Float/Double 会写坏模组的强类型槽位，之后强转必崩，宁可不写。
        return null;
    }

    /** 每个实体类只做一次「静态容器」诊断 */
    private static final Set<Class<?>> CONTAINER_DIAGNOSED = ConcurrentHashMap.newKeySet();

    private static final int CONTAINER_DIAGNOSE_LIMIT = 4096;

    /** 诊断用：列出模组可达类里的静态容器字段（Map / Collection）及其规模与指向本实体的条目数 */
    static void diagnoseStaticContainers(LivingEntity victim) {
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
    static void describeFieldRegistry(Object container) {
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
    static String describeContainer(Object container, LivingEntity victim) {
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

    static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** 在实体继承链上找血量算子：其方法空参调用读出的值等于当前 {@code getHealth()} */
    static OperatorPlan discoverOperatorPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        boolean diagnose = MAConfig.debugProbes && OPERATOR_DIAGNOSED.add(type);
        if (diagnose) {
            // 继承链与每层的实例字段一并打出来
            for (Class<?> current : hierarchy(type)) {
                List<String> names = new ArrayList<>();
                for (Field field : declaredFieldsOf(current)) {
                    if (!Modifier.isStatic(field.getModifiers())) names.add(field.getName());
                }
                probeLog("健康算子层继承链：{} 实例字段={}", current.getName(), briefList(names));
            }
        }
        List<String> candidatesSeen = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            for (Field field : declaredFieldsOf(current)) {
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
    static OperatorPlan probeOperatorField(LivingEntity victim, Field field, float health,
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
                // 带上方法名：同一字段上常挂着多个（继承来的）同名重载，只打字段名会看到重复行
                probeLog("健康算子层候选：{}#{}.{}({}) 读值={} 期望={}",
                        victim.getClass().getName(), field.getName(), operate.getName(),
                        field.getType().getSimpleName(), read, health);
            }
            if (matches(read, health)) return new OperatorPlan(field, operate);
        }
        return null;
    }

    /** 静态「字段登记表」里的实体状态字段（只保留声明类是实体祖先的） */
    static List<Field> registeredStateFields(LivingEntity victim) {
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
    static void collectOperatorMethods(Class<?> type, List<Method> out, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) return;
        for (Method method : declaredMethodsOf(type)) {
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

    static float readOperatorHealth(OperatorPlan plan, Object operator) {
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

    static void writeOperatorArgs(OperatorPlan plan, Object operator, Object[] args) {
        invokeObject(plan.operate(), operator, new Object[]{ args });
    }

    /**
     * 写入值的候选承载形态：只按算子当前读数的运行时类型构造唯一一个载体。读数类型就是标准答案，不能猜：
     * 写口背后往往是强类型同步项（如 {@code EntityDataAccessor<Integer> DATA_HURTCOOLDOWN}），写入 Double / Float
     * 会永久改坏槽位运行期类型，模组强转时抛 ClassCastException，且还原写回的也是错误类型；读数拿不到类型时宁可不写。
     */
    static List<Object> operatorPayloads(float value, Object rawSample) {
        Object sameType = boxAs(rawSample, value);
        if (sameType == null) return List.of();
        return List.of(sameType);
    }

    /** 候选写键：实体继承链与少量可达模组类常量池里的字符串字面量（按类缓存：常量池是结构性数据） */
    static List<String> operatorKeys(Class<?> type) {
        return computeOutsideLock(OPERATOR_KEYS_CACHE, type, () -> {
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
        });
    }

    private static final Map<Class<?>, List<String>> OPERATOR_KEYS_CACHE = new ConcurrentHashMap<>();

    private static void addOperatorKey(List<String> keys, String value) {
        if (keys.size() >= OPERATOR_MAX_KEYS || value == null) return;
        if (value.isEmpty() || value.length() > 64 || keys.contains(value)) return;
        keys.add(value);
    }

    /** 密文路径：血量以编码字符串存在同步数据里，按标定的编解码调用式整串回写
     * 「该类没有可用的编解码口」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> CODEC_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    static boolean writeViaCipherHealth(LivingEntity victim, float healthAfter) {
        return writeViaCipherHealth(victim, healthAfter, Float.NaN);
    }

    /**
     * @param damage 本次真伤量；计数型槽位（存的是累计伤害）会用它做「当前值 + 伤害」的增量写入，从而不依赖任何「最大值」基准（模组的剩余血量用配置值，与实体属性上限可能不同）。
     */
    static boolean writeViaCipherHealth(LivingEntity victim, float healthAfter, float damage) {
        if (cipherCodec(victim) == null) {
            // 负缓存命中后这里每击都会走到：只打一次（D4 日志降噪）
            if (oncePer(CODEC_MISS_LOGGED, victim.getClass(), "密文编解码口未标定")) {
                probeLog("加密同步血量层：该类没有可用的编解码口（未标定/负缓存），跳过：{}",
                        victim.getClass().getName());
            }
            return false;
        }
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
        // NBT 来源池恒为空池：标定只用空池跑（见 discoverCipherCodec），实参来源里不可能有
        // NBT_STRING，再算一次全实体 NBT 序列化只会白花 1.5–1.8 秒。
        List<String> nbt = List.of();
        double storedTarget = cipherStoredValue(codec, victim, healthAfter);
        if (codec.countsDamage() && !Float.isNaN(damage)) {
            // 计数型槽位：直接在当前累计值上加本次伤害。模组的剩余血量是「配置的最大血量 − 累计值」，
            // 与实体属性上限可能不同，用实体上限反推会算出与显示不一致的累计值 → 显示跳变；
            // 增量写入不受此影响。尺度目前恒为 1；若遇到配置上限 ≠ 属性上限的目标，应取对方自己的
            // 两个读数（配置最大血量 vs 实体属性最大血量）算比值，不能拿实体上限直接推。
            Double counterNow = cipherDecodeWith(codec.decode(), victim, stored, nbt);
            if (counterNow != null && !counterNow.isNaN()) {
                double scale = 1.0D;
                storedTarget = counterNow + damage * scale;
                // 计数型存储的物理含义就是「累计伤害」，必须夹在 [0, 上限] 内：写超上限会让剩余血量
                // 变负，写负数会让客户端反算出异常显示。上限处正好是「剩余 0」，足以触发它自己的
                // 死亡判定，无需写超；读数由 counterNow 现取，夹取只影响这一次，不会丢覆盖。
                float cap = maxHealthOf(victim);
                if (!Float.isNaN(cap) && cap > 0.0F) {
                    if (storedTarget > cap) storedTarget = cap;
                }
                if (storedTarget < 0.0D) storedTarget = 0.0D;
                probeLog("密文同步血量层（计数型）：当前累计={} + 本次伤害={} × 尺度={} → {}（上限 {}）",
                        counterNow, damage, scale, storedTarget, cap);
            }
        }
        String previous = cipherText(codec, victim, cipherStoredValue(codec, victim, healthOf(victim)), nbt);
        String updated = cipherText(codec, victim, storedTarget, nbt);
        if (previous == null || updated == null) {
            probeLog("加密同步血量层编码失败：{} 存储={}", type.getName(), stored);
            return false;
        }
        // 现场串可能带固定前缀，只替换「旧编码段」那一段
        String next = codec.suffixOnly() && stored.endsWith(previous)
                ? stored.substring(0, stored.length() - previous.length()) + updated
                : updated;
        // 写入前自校验：编出来的串必须能被同一个解码口解回目标血量，否则等于往模组状态里
        // 写一段垃圾密文（还会因「写一次 + 还原一次」产生两次 dirty 广播）。
        Double roundTrip = cipherDecodeWith(codec.decode(), victim, next, nbt);
        if (roundTrip == null || !matches((float) (double) roundTrip, (float) storedTarget)) {
            probeLog("加密同步血量层编码自校验未通过，放弃本次写入：{} 目标={} 解回={}",
                    type.getName(), healthAfter, roundTrip);
            return false;
        }
        try {
            entityData.set(rawAccessor(accessor), next);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            probeLog("加密同步血量层写入失败：{} → {}", type.getName(), t);
            return false;
        }
        // 写后判定：模组 getHealth() 是假值时它恒不变，用它判定会把已写成功的密文又还原掉
        // （无名术士就是这种情况）。唯一可信的判据是复读槽位、用同一解码口解回目标明文。
        float storedNow = storedHealth(victim);
        if (matches(storedNow, healthAfter) || !stillAboveTarget(victim, healthAfter, true)) {
            probeLog("加密同步血量层写入生效：{} 目标={} 槽位血量={} getHealth={}",
                    type.getName(), healthAfter, storedNow, healthOf(victim));
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

    /**
     * 模组自有密文存储里的权威血量（编解码口与槽位都标定出来后才可用，否则 {@code NaN}）。
     * iss_csw 无名术士就是这种实体：{@code getHealth()} 恒等于最大血量（假值），真正血量只存在
     * {@code DATA_EXISTENCE} 里（{@code getExistenceNum() = maxhealth - getExistence()}）。标定成功后
     * 这里的读数才是当前血量的唯一真值，基线、结算、致死判定都必须用它，否则每次命中都从满血开始扣。
     */
    static float storedHealth(LivingEntity victim) {
        Class<?> type = victim.getClass();
        CipherCodec codec = CIPHER_CODEC_CACHE.get(type);
        EntityDataAccessor<?> accessor = ENCRYPTED_HEALTH_ACCESSOR_CACHE.get(type);
        if (codec == null || accessor == null) return Float.NaN;
        try {
            List<String> nbt = List.of();
            Object raw = victim.getEntityData().get(rawAccessor(accessor));
            Double decoded = raw instanceof String text ? cipherDecodeWith(codec.decode(), victim, text, nbt) : null;
            if (decoded == null) return Float.NaN;
            if (!codec.countsDamage()) return decoded.floatValue();
            float maxHealth = maxHealthOf(victim);
            return Float.isNaN(maxHealth) ? decoded.floatValue() : maxHealth - decoded.floatValue();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Float.NaN;
        }
    }

    /** 槽位里应该存的明文值：直接语义就是血量；计数语义是「累计伤害 = 最大血量 − 血量」。 */
    private static double cipherStoredValue(CipherCodec codec, LivingEntity victim, float health) {
        if (!codec.countsDamage()) return health;
        float maxHealth = maxHealthOf(victim);
        return Float.isNaN(maxHealth) ? health : maxHealth - health;
    }

    /** 用已标定的编码口把「槽位明文值」编成密文段（主实参按该形参的声明类型装箱） */
    private static String cipherText(CipherCodec codec, LivingEntity victim, double storedValue, List<String> nbt) {
        CipherCall call = codec.encode();
        Object primary = boxPrimary(call.method().getParameterTypes()[call.primaryParam()], storedValue);
        return cipherEncodeWith(call, victim, primary, nbt);
    }

    /** 在实体的同步数据里找出「装着血量密文」的那一项（解码值等于 {@code getHealth()}） */
    static EntityDataAccessor<?> encryptedHealthAccessor(LivingEntity victim, SynchedEntityData entityData,
                                                                 CipherCodec codec) {
        Class<?> type = victim.getClass();
        EntityDataAccessor<?> cached = ENCRYPTED_HEALTH_ACCESSOR_CACHE.get(type);
        if (cached != null) return cached;
        if (ENCRYPTED_HEALTH_ACCESSOR_NEGATIVE.blocked(type)) return null;
        float health = healthOf(victim);
        float maxHealth = maxHealthOf(victim);
        // NBT 来源池恒为空池：标定阶段从空池标出（见 discoverCipherCodec），已标定调用式里
        // 不可能有 NBT_STRING 实参位，再取一次全实体 NBT 序列化没有作用，且一次要 1.5–1.8 秒。
        List<String> nbt = List.of();
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
            if (decoded == null) continue;
            boolean matchesHealth = matches(decoded.floatValue(), health)
                    || (!Float.isNaN(maxHealth) && matches((float) (maxHealth - decoded), health));
            if (!matchesHealth) continue;
            ENCRYPTED_HEALTH_ACCESSOR_CACHE.put(type, accessor);
            ENCRYPTED_HEALTH_ACCESSOR_NEGATIVE.clear(type);
            probeLog("加密同步血量层已定位密文项：{} {}", type.getName(), accessor);
            return accessor;
        }
        // 没找到：退避，避免每一次命中都重扫一遍同步数据全表
        ENCRYPTED_HEALTH_ACCESSOR_NEGATIVE.mark(type);
        return null;
    }

    /** {@code SynchedEntityData} 上承载条目的 Map 字段（类型布局固定，只解析一次） */
    private static final List<Field> SYNCED_DATA_MAP_FIELDS = syncedDataMapFields();

    private static List<Field> syncedDataMapFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : declaredFieldsOf(SynchedEntityData.class)) {
            if (Map.class.isAssignableFrom(field.getType())) fields.add(field);
        }
        return List.copyOf(fields);
    }

    static List<EntityDataAccessor<?>> registeredAccessors(SynchedEntityData entityData) {
        List<EntityDataAccessor<?>> accessors = new ArrayList<>();
        // 身份集合去重，避免 O(n²)
        Set<EntityDataAccessor<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field field : SYNCED_DATA_MAP_FIELDS) {
            if (!(readFieldAny(field, entityData) instanceof Map<?, ?> items)) continue;
            for (Object item : items.values()) {
                if (item == null) continue;
                if (dataItemAccessor(item) instanceof EntityDataAccessor<?> accessor && seen.add(accessor)) {
                    accessors.add(accessor);
                }
            }
        }
        return accessors;
    }

    /** {@code SynchedEntityData.DataItem} 上持有的 accessor */
    static Object dataItemAccessor(Object dataItem) {
        for (Field field : declaredFieldsOf(dataItem.getClass())) {
            if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
            return readFieldAny(field, dataItem);
        }
        return null;
    }

    enum CipherArgKind {
        ENTITY, UUID, UUID_HASH, UUID_HIGH, UUID_LOW, ENTITY_ID, ENTITY_HASH, NBT_STRING, ZERO, ONE
    }

    record CipherArgSource(CipherArgKind kind, int index) {
    }

    /** 一次已标定的调用：主实参（密文 / 明文）落在 {@code primaryParam}，其余形参位按 {@code args} 取值 */
    record CipherCall(Method method, int primaryParam, CipherArgSource[] args) {
    }

    /** 已标定的密文串血量编解码方案（按实体类缓存） */
    record CipherCodec(CipherCall decode, CipherCall encode, boolean suffixOnly, boolean countsDamage) {
        /** 该方案是否用到 NBT 来源（用不到时不必为每次命中做一次全实体 NBT 序列化） */
        boolean usesNbtSource() {
            return usesNbtSource(decode) || usesNbtSource(encode);
        }

        private static boolean usesNbtSource(CipherCall call) {
            if (call == null || call.args() == null) return false;
            for (CipherArgSource source : call.args()) {
                if (source != null && source.kind() == CipherArgKind.NBT_STRING) return true;
            }
            return false;
        }
    }

    /** 已标定方案，按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, CipherCodec> CIPHER_CODEC_CACHE = new ConcurrentHashMap<>();

    /** 密文编解码标定「没标出来」时的退避缓存 */
    static final NegativeCache<Class<?>> CIPHER_CODEC_NEGATIVE =
            new NegativeCache<>("密文编解码标定");

    private static final int CIPHER_MAX_TRIALS = 4096;

    /** 上下文形参位上限（多于此位的候选直接跳过） */
    private static final int CIPHER_MAX_CONTEXT_PARAMS = 2;

    /**
     * 标定结果 + 本次枚举是否真的跑完了，它决定负结果怎么缓存：跑完了说明该类确实标不出来（类结构使然，进程内不会变），
     * 标定一次要扫 512 个可达类、约 7.7 秒，必须永久缓存；没跑完（候选还少、撞上 4096 次上限）则结论不可信，只能短退避，等预热完成后再重试。
     */
    private record CipherCalibration(CipherCodec codec, boolean exhausted) {
    }

    private static CipherCodec cipherCodec(LivingEntity victim) {
        Class<?> type = victim.getClass();
        CipherCodec cached = CIPHER_CODEC_CACHE.get(type);
        if (cached != null) return cached;
        if (CIPHER_CODEC_NEGATIVE.blocked(type)) return null;
        CipherCalibration result = discoverCipherCodec(victim);
        if (result.codec() != null) {
            CIPHER_CODEC_CACHE.put(type, result.codec());
            CIPHER_CODEC_NEGATIVE.clear(type);
            return result.codec();
        }
        if (result.exhausted()) {
            // 枚举真的跑完却一无所获：结论由类结构决定，重算多少次都一样，故永久缓存。
            CIPHER_CODEC_NEGATIVE.markPermanent(type);
        } else {
            // 结论不完整（候选不足 / 撞上试验上限）：只短退避，等预热与模组收割完成后再探。
            CIPHER_CODEC_NEGATIVE.markShort(type);
        }
        return null;
    }

    /**
     * 提前发现入口（T12）：走与级联完全相同的标定缓存与负缓存路径，只做只读标定、不写血，把标定成本从「首次命中」提前到「实体刚出现」。
     */
    static void preCalibrateCipher(LivingEntity victim) {
        cipherCodec(victim);
    }

    /** 现场标定：以「现场密文 ↔ 当前 getHealth()」反推解码口与编码口的调用形状 */
    static CipherCalibration discoverCipherCodec(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return new CipherCalibration(null, false);
        List<Method> candidates = cipherMethods(victim);
        List<String> storedValues = cipherStoredStrings(victim);
        // 不要在这里做实体 NBT 序列化：boss 级实体的 addAdditionalSaveData 一次要几百毫秒，
        // 而绝大多数模组的密文口不引用 NBT 来源。先用空池标定，需要 NBT 来源的调用式留待后续按需尝试。
        List<String> nbt = List.of();
        if (MAConfig.debugProbes) {
            probeLog("密文串血量：候选方法={} 字符串位点={}（NBT 来源按需，暂不序列化）",
                    candidates.size(), storedValues.size());
        }
        if (candidates.isEmpty() || storedValues.isEmpty()) {
            if (MAConfig.debugProbes) {
                probeLog("密文标定：候选不足，放弃（candidates={} storedValues={}）",
                        candidates.size(), storedValues.size());
            }
            return new CipherCalibration(null, false);
        }
        int trials = 0;
        int plainHits = 0;
        int damageHits = 0;
        float maxHealth = maxHealthOf(victim);
        // 诊断：逐串记录现场串与「简单形态候选解出的值」。多数标定失败是现场串解不出数，
        // 而不是解出来了但两个口径都不等于 getHealth()。
        if (MAConfig.debugProbes) {
            for (String stored : storedValues) {
                String preview = stored.length() > 32 ? stored.substring(0, 32) + "…" : stored;
                List<String> decodedSummary = new ArrayList<>();
                for (Method method : candidates) {
                    if (!Modifier.isStatic(method.getModifiers())) continue;
                    if (method.getParameterCount() != 1) continue;
                    Class<?> param = method.getParameterTypes()[0];
                    if (param != String.class && param != Object.class && !CharSequence.class.isAssignableFrom(param)) {
                        continue;
                    }
                    for (CipherCall call : cipherCalls(method, 0, victim, nbt)) {
                        Double plain = cipherDecodeWith(call, victim, stored, nbt);
                        if (plain != null) {
                            decodedSummary.add(method.getDeclaringClass().getSimpleName()
                                    + "#" + method.getName() + "(" + plain + ")");
                        }
                    }
                }
                probeLog("密文标定：现场串=\"{}\"（长度 {}）解出值={}",
                        preview, stored.length(), decodedSummary.isEmpty() ? "无" : decodedSummary);
            }
        }
        // 优先尝试「简单形态」：静态 + 恰 1 个 String/CharSequence/Object 形参（加解密工具类的
        // 常见形态）。判定逻辑与穷举完全相同，只是候选子集更小；未命中则执行下面的穷举。
        CipherCodec simple = discoverSimpleCodec(victim, candidates, storedValues, health, maxHealth, nbt);
        if (simple != null) return new CipherCalibration(simple, true);
        // 跑完为止：不设时间预算，只有 CIPHER_MAX_TRIALS 这个纯计数保护，防止病态组合无限增长。
        List<List<String>> pools = List.of(List.of());
        for (String stored : storedValues) {
            for (Method method : candidates) {
                for (int primary = 0; primary < method.getParameterCount(); primary++) {
                    if (isNotCipherPrimary(method.getParameterTypes()[primary], true)) continue;
                    for (List<String> pool : pools) {
                    for (CipherCall decode : cipherCalls(method, primary, victim, pool)) {
                        if (trials++ >= CIPHER_MAX_TRIALS) {
                            if (MAConfig.debugProbes) {
                                probeLog("密文标定：撞上试验上限 {}，结论不完整（候选={}）",
                                        CIPHER_MAX_TRIALS, candidates.size());
                            }
                            return new CipherCalibration(null, false);
                        }
                        Double plain = cipherDecodeWith(decode, victim, stored, nbt);
                        if (plain == null) continue;
                        // 三种语义：1) 解出的就是当前血量；2) 解出的是累计伤害，剩余血量 = 最大血量 − 解出值；3) 实体是
                        //    假血量（getHealth() 恒等于最大血量，iss_csw 无名术士就是）：此时 2) 只在累计值 ≈ 0（满血）
                        //    时成立，有过伤害后门槛永远不成立，所以改用取值域证据：累计值落在 [0, 上限 × 1024] 内即可。
                        //    配对方向另有硬门槛（findCipherEncoder 的合成明文回环，decrypt 冒充编码口会被刷掉），放宽不会认错编解码口。
                        boolean countsDamage = false;
                        boolean fakeHealth = !Float.isNaN(maxHealth) && matches(health, maxHealth);
                        if (!matches(plain.floatValue(), health)) {
                            if (!Float.isNaN(maxHealth) && matches((float) (maxHealth - plain), health)) {
                                countsDamage = true;
                            } else if (fakeHealth && plain >= -PROBE_EPSILON
                                    && plain <= (double) maxHealth * 1024.0D + 1.0D) {
                                // 上界刻意放宽到「上限 × 1024」：槽位可能已被上一次越界写入污染，按窄范围判会
                                // 「标定失败 → 永久负缓存 → 再也写不进去」，而正要靠下一次写入把它夹回 [0, 上限]
                                // 修好。正确性由 findCipherEncoder 的合成明文回环（不依赖槽位当前值）保证。
                                countsDamage = true;
                            } else {
                                continue;
                            }
                        }
                        if (countsDamage) damageHits++; else plainHits++;
                        CipherCall encode = findCipherEncoder(victim, plain, stored, decode, candidates, nbt);
                        if (encode == null) continue;
                        String again = cipherEncodeWith(encode, victim,
                                boxPrimary(encode.method().getParameterTypes()[encode.primaryParam()], plain), nbt);
                        return new CipherCalibration(
                                new CipherCodec(decode, encode, again != null && !stored.equals(again), countsDamage),
                                true);
                    }
                    }
                }
            }
        }
        if (MAConfig.debugProbes) {
            probeLog("密文标定：未标出（trials={}/{} 明文命中={} 累计伤害命中={}）",
                    trials, CIPHER_MAX_TRIALS, plainHits, damageHits);
        }
        // 没撞上试验上限 ⇒ 候选集合的每个组合都真的试过了，只要候选集合不再变大，再跑一次结果
        // 相同，而单次开销约 7.7 秒（512 个可达类），所以报「结论可信」由调用方永久缓存。
        return new CipherCalibration(null, true);
    }

    /**
     * 「简单形态」标定：只用静态 + 恰 1 个 String/CharSequence/Object 形参的方法先试一遍。这类方法（如
     * {@code HealthCalculation.decrypt/encrypt}）每个方向只需一次反射调用，能在个位数调用内标定出来，避免首击把几百个复杂调用形状都跑一遍。
     * 采信前额外要求：编码口与解码口在同一个类（真正的加解密对总是成对出现），且 {@code decode(encode(明文))} 能回环；
     * 不满足返回 {@code null}，交由完整穷举处理。
     */
    private static CipherCodec discoverSimpleCodec(LivingEntity victim, List<Method> candidates,
                                                   List<String> storedValues, float health, float maxHealth,
                                                   List<String> nbt) {
        List<Method> simple = new ArrayList<>();
        for (Method method : candidates) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;
            Class<?> param = params[0];
            if (param == String.class || param == Object.class || CharSequence.class.isAssignableFrom(param)) {
                simple.add(method);
            }
        }
        if (simple.isEmpty()) return null;
        for (String stored : storedValues) {
            for (Method method : simple) {
                for (CipherCall decode : cipherCalls(method, 0, victim, nbt)) {
                    Double plain = cipherDecodeWith(decode, victim, stored, nbt);
                    if (plain == null) continue;
                    boolean countsDamage = false;
                    if (!matches(plain.floatValue(), health)) {
                        if (Float.isNaN(maxHealth) || !matches((float) (maxHealth - plain), health)) continue;
                        countsDamage = true;
                    }
                    CipherCall encode = findCipherEncoder(victim, plain, stored, decode, simple, nbt);
                    if (encode == null) continue;
                    // 编码口必须与解码口不是同一个方法：槽位是退化值（例如 "0"）时，decrypt 自己也能
                    // 通过回环检查（解析失败一律返回 "0"），写进去的还是 "0"，血量纹丝不动。
                    if (encode.method() == decode.method()) continue;
                    if (encode.method().getDeclaringClass() != method.getDeclaringClass()) continue;
                    String again = cipherEncodeWith(encode, victim,
                            boxPrimary(encode.method().getParameterTypes()[encode.primaryParam()], plain), nbt);
                    if (again == null || again.isEmpty()) continue;
                    // 文本形态（精确/后缀/同长度）只是加分项，不能做门槛：退化槽位下正确编码口的
                    // 输出长度天然与现场串不同。真正的门槛是下面的回环校验。
                    Double roundTrip = cipherDecodeWith(decode, victim, again, nbt);
                    if (roundTrip == null || !matches(roundTrip.floatValue(), (float) (double) plain)) continue;
                    if (!stored.equals(again) && again.length() != stored.length()) {
                        // 形态不同但语义正确：记录一行，便于回溯（正常密文串时这里不会触发）
                        probeLog("密文编解码标定（简单形态）：编码结果与现场串形态不同但语义正确：现场={} 新={}",
                                stored.length() > 24 ? stored.substring(0, 24) + "…" : stored,
                                again.length() > 24 ? again.substring(0, 24) + "…" : again);
                    }
                    probeLog("密文编解码标定（简单形态）：解={}#{} 编={}#{}",
                            method.getDeclaringClass().getSimpleName(), method.getName(),
                            encode.method().getDeclaringClass().getSimpleName(), encode.method().getName());
                    return new CipherCodec(decode, encode, !stored.equals(again), countsDamage);
                }
            }
        }
        return null;
    }

    /** 可能是血量密文的字符串：实体 String 字段 + 同步数据里当前值为 String 的项 */
    static List<String> cipherStoredStrings(LivingEntity victim) {
        List<String> values = new ArrayList<>();
        for (StringCandidate candidate : encryptedStringCandidates(victim)) {
            String raw = candidate.slot().read();
            if (raw != null && !raw.isEmpty() && !values.contains(raw)) values.add(raw);
        }
        return values;
    }

    /**
     * 候选编解码方法：继承链 + 常量池可达类 + 预热已收割的本模组类。第三个来源必需：有些模组会把实例的 klass 指针换成 ASM 生成的子类。
     * cipherMethods 结果缓存键：实体类 × 「模组文件收割是否已就绪」 */
    private record CipherMethodsKey(Class<?> type, boolean harvestReady) {
    }

    /**
     * cipherMethods 结果按 (实体类, 收割就绪) 缓存，含空结果。重探要重扫「继承链 + 最多 {@code OPERATOR_REFERENCED_CLASSES}
     * 个可达类 + {@link #CIPHER_MAX_HARVESTED_CLASSES} 个收割类」的方法表；结果只由类结构决定，但第三个来源随收割完成变大，所以键里带 harvestReady，避免把「收割未完成」的空结论固化。
     */
    private static final Map<CipherMethodsKey, List<Method>> CIPHER_METHODS_CACHE = new ConcurrentHashMap<>();

    static List<Method> cipherMethods(LivingEntity victim) {
        Class<?> type = victim.getClass();
        boolean harvestReady = healthCoreCandidates(victim).complete();
        CipherMethodsKey key = new CipherMethodsKey(type, harvestReady);
        List<Method> cached = CIPHER_METHODS_CACHE.get(key);
        if (cached != null) return cached;
        List<Method> methods = new ArrayList<>();
        for (Class<?> current : hierarchy(type)) {
            // 逐类隔离：候选类可能引用本整合包没装的模组，getDeclaredMethods() 会抛 LinkageError；
            // 少了它一个坏类就会让整层拿不到候选，密文标定永远失败。
            for (Method method : declaredMethodsOf(current)) {
                if (cipherMethodUsable(method, type) && !methods.contains(method)) methods.add(method);
            }
        }
        int scanned = 0;
        for (Class<?> clazz : referencedModClassesDeep(type)) {
            if (scanned++ >= OPERATOR_REFERENCED_CLASSES) break;
            for (Method method : declaredMethodsOf(clazz)) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (cipherMethodUsable(method, type) && !methods.contains(method)) methods.add(method);
            }
        }
        int harvested = 0;
        for (Class<?> clazz : healthCoreCandidates(victim).classes()) {
            if (harvested++ >= CIPHER_MAX_HARVESTED_CLASSES) break;
            if (clazz == null || clazz.isInterface() || clazz.isPrimitive() || clazz.isArray()) continue;
            for (Method method : declaredMethodsOf(clazz)) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (cipherMethodUsable(method, type) && !methods.contains(method)) methods.add(method);
            }
        }
        List<Method> stored = List.copyOf(methods);
        List<Method> raced = CIPHER_METHODS_CACHE.putIfAbsent(key, stored);
        return raced != null ? raced : stored;
    }

    static boolean cipherMethodUsable(Method method, Class<?> entityType) {
        // 只排除桥接方法，不排除 synthetic
        if (method.isBridge() || isNativeMember(method)) return false;
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

    /**
     * 该形参是否不能承载密文/明文（解码时只认字符串族，编码时还接受数值），即「主实参位」判定：
     * 解码时该位收密文（字符串，或 {@code Object} 这种什么都能收的位）；编码时除字符串外还可收数值。
     */
    private static boolean isNotCipherPrimary(Class<?> param, boolean decode) {
        if (param == String.class || param == Object.class || CharSequence.class.isAssignableFrom(param)) {
            return false;
        }
        return decode || !isCipherNumber(param);
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
        // 完整枚举调用形状（受 CIPHER_MAX_TRIALS 计数上限约束）：为了「跑完为止」不再裁剪。
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

    static Object cipherArgValue(CipherArgSource source, LivingEntity victim, List<String> nbt, Class<?> param) {
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
    static Double cipherDecodeWith(CipherCall call, LivingEntity victim, String stored, List<String> nbt) {
        Object[] args = fillCipherArgs(call, victim, stored, nbt);
        if (args == null) return null;
        return toNumber(invokeObject(call.method(), cipherTarget(call, victim), args));
    }

    /** 用已标定的编码口编出字符串（主实参类型由形参声明决定） */
    static String cipherEncodeWith(CipherCall call, LivingEntity victim, Object primary, List<String> nbt) {
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

    static Object boxPrimary(Class<?> param, double value) {
        if (param == String.class || CharSequence.class.isAssignableFrom(param)) return plainText(value);
        // 形参被擦除（Object/Number）时本层语义就是「密文/明文串」，给字符串比猜数值安全：
        // 猜成 Float/Double 会被模组原样存进强类型槽位，之后强转必崩。
        if (ModWriterProbe.isErasedNumericParam(param)) return plainText(value);
        return boxNumber(param, value);
    }

    /** 找编码口：把明文编回去要能重现现场那串（或其后缀），且再过解码口仍能还原 */
    static CipherCall findCipherEncoder(LivingEntity victim, double plain, String stored, CipherCall decode,
                                                List<Method> candidates, List<String> nbt) {
        for (Method method : candidates) {
            // 编码口必须与解码口是不同的方法：槽位为退化值时会出错——stored="0"、plain=0.0 时
            // decrypt("0") 得到 "0"，stored.equals(encoded) 与解回 0.0 == plain 都成立，于是 decrypt
            // 被当成编码口认下，写入的还是 "0"，血量纹丝不动，表现为「标定成功但永远写不进去」。
            if (method == decode.method()) continue;
            for (int primary = 0; primary < method.getParameterCount(); primary++) {
                if (isNotCipherPrimary(method.getParameterTypes()[primary], false)) continue;
                for (CipherCall call : cipherCalls(method, primary, victim, nbt)) {
                    String encoded = cipherEncodeWith(call, victim,
                            boxPrimary(method.getParameterTypes()[primary], plain), nbt);
                    if (encoded == null || encoded.isEmpty()) continue;
                    // 文本/长度只作同一性参考，绝不能当必要条件：现场串常是模组的占位值（iss_csw：
                    // 默认 "HEC0"，或写坏后的 "0"），长度与真实密文天然不同，卡长度会把唯一正确的
                    // 编码口刷掉。方向正确性由下面这条与现场串无关的合成明文回环保证，它才是硬门槛。
                    if (!encoderRoundTrips(decode, call, victim, nbt)) continue;
                    return call;
                }
            }
        }
        return null;
    }

    /** 合成明文的往返探针（必须都是 float 可精确表示的值，避免容差掩盖方向错误） */
    private static final double[] ENCODER_PROBES = {0.0, 7.0, 123.5};

    /** 方向自证：编码口对若干合成明文编码后，必须能被解码口原值解回（与现场串无关） */
    private static boolean encoderRoundTrips(CipherCall decode, CipherCall encode, LivingEntity victim,
                                             List<String> nbt) {
        for (double probe : ENCODER_PROBES) {
            Object arg = boxPrimary(encode.method().getParameterTypes()[encode.primaryParam()], probe);
            String text = cipherEncodeWith(encode, victim, arg, nbt);
            if (text == null || text.isEmpty()) return false;
            Double back = cipherDecodeWith(decode, victim, text, nbt);
            if (back == null || !matches(back.floatValue(), (float) probe)) return false;
        }
        return true;
    }
}

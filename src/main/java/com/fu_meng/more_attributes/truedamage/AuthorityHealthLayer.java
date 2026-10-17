package com.fu_meng.more_attributes.truedamage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageWarmup.*;

/**
 * 「代管血量」（权威存储）写入层（档 3）：影子血量单例（把 uuid 写进单例的 Map/Set 字段）、
 * MAC 保护的静态权威记录（复算 MAC 后整体替换）、模组权威值「密文互换」直写（致死专用，非致死按累计值增量写入）。
 */
final class AuthorityHealthLayer {

    /** 影子单例方案（只存类级：单例访问器 + 容器字段） */
    record ShadowPlan(Field singletonField, Method singletonFactory, Field trackedSet,
                              Field healthMap, List<Field> dieGates, List<Field> removeGates) {
    }

    private static final Map<Class<?>, List<ShadowPlan>> SHADOW_PLAN_CACHE = new ConcurrentHashMap<>();

    /** 影子血量单例层「没探到」时的退避缓存 */
    static final NegativeCache<Class<?>> SHADOW_NEGATIVE =
            new NegativeCache<>("影子血量单例");

    private static final int SHADOW_MAX_PLANS = 4;

    private static final int SHADOW_MAX_CONTAINERS = 32;

    /** 「影子层未探到方案」每 (实体类, 层) 只打一次（见 D4 日志降噪） */
    private static final Set<String> SHADOW_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    /** 用影子单例的容器字段把血量改成目标值；未写到位则整体还原 */
    static void writeViaShadowStore(LivingEntity victim, float healthAfter) {
        List<ShadowPlan> plans = shadowPlans(victim);
        if (plans.isEmpty()) {
            if (oncePer(SHADOW_MISS_LOGGED, victim.getClass(), "影子层无方案")) {
                probeLog("影子层：未探到方案，跳过（{}）", victim.getClass().getName());
            }
            return;
        }
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
            // 只记录「本次新增」的死门控：还原时不能删掉探测/模组自己放进去的既有成员
            Set<Field> addedGates = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                healthMap.put(uuid, healthAfter);
            } catch (Throwable ignored) {
                if (tracked) removeFromSet(plan.trackedSet(), shadow, uuid);
                continue;
            }

            if (healthAfter <= 0.0F) {
                addAllToSets(plan.dieGates(), shadow, uuid, addedGates);
            }
            // getHealth() 是本层唯一的校验依据
            float now = healthOf(victim);
            if (matches(now, healthAfter)) {
                probeLog("影子层写入生效：目标={} getHealth={}", healthAfter, now);
                return;
            }
            probeLog("影子层未生效，已还原：目标={} getHealth={}", healthAfter, now);
            try {
                if (had) healthMap.put(uuid, previous);
                else healthMap.remove(uuid);
            } catch (Throwable ignored) {
                // 还原尽力而为
            }
            if (tracked) removeFromSet(plan.trackedSet(), shadow, uuid);
            for (Field gate : addedGates) {
                removeFromSet(gate, shadow, uuid);
            }
        }
    }

    /** A：在「满血退化锚点」下建出的方案（锚点一旦离开满血就丢弃重探一次，见 StaticAuthorityLayer 的说明） */
    private static final Set<Class<?>> SHADOW_FULL_ANCHOR_PLANS = ConcurrentHashMap.newKeySet();

    static List<ShadowPlan> shadowPlans(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<ShadowPlan> cached = SHADOW_PLAN_CACHE.get(type);
        if (cached != null && SHADOW_FULL_ANCHOR_PLANS.contains(type)
                && !fullHealthAnchor(victim, currentHealth(victim))) {
            SHADOW_PLAN_CACHE.remove(type, cached);
            SHADOW_FULL_ANCHOR_PLANS.remove(type);
            cached = null;
        }
        if (cached != null) return cached;
        if (SHADOW_NEGATIVE.blocked(type)) return List.of();
        // 绝不能因为「模组文件收割尚未就绪」就跳过本层：收割只是补充来源，候选集始终含实体常量池可达的类；
        // 跳过会把只能靠它命中的目标永久挡在门外（GreatSage 曾因此三层全失效）。未就绪只影响「要不要退避」。
        boolean complete = seedHarvestReady(victim);
        List<ShadowPlan> found;
        try {
            found = discoverShadowPlans(victim);
        } catch (Throwable t) {
            markDiscoveryMiss(SHADOW_NEGATIVE, type, complete);
            return List.of();
        }
        if (!found.isEmpty()) {
            SHADOW_PLAN_CACHE.put(type, found);
            if (fullHealthAnchor(victim, currentHealth(victim))) SHADOW_FULL_ANCHOR_PLANS.add(type);
            else SHADOW_FULL_ANCHOR_PLANS.remove(type);
            SHADOW_NEGATIVE.clear(type);
        } else {
            markDiscoveryMiss(SHADOW_NEGATIVE, type, complete);
        }
        return found;
    }

    static List<ShadowPlan> discoverShadowPlans(LivingEntity victim) {
        // 锚点用真实血量
        float anchor = currentHealth(victim);
        if (Float.isNaN(anchor) || anchor <= 0.0F) return List.of();
        List<Class<?>> candidates = healthCoreCandidates(victim).classes();
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

    /** 行为验证某个单例是不是「影子血量表」：找 (Set, Map) 组合与生死门控 Set */
    static ShadowPlan probeShadow(LivingEntity victim, Object[] accessor) {
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
        // 只清理「本次探测新加进去」的成员：探测前就存在的必须原样保留。整个探测包在 try/finally 里，
        // 任何异常都不会把 uuid 残留在模组的 Set / 死门控里（残留会让实体被当成已死/已移除，即「幽灵实体」）。
        Set<Field> addedByProbe = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Field setField : sets) {
                if (!ensureInSet(setField, shadow, uuid, addedByProbe)) continue;
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
                    releaseSet(setField, shadow, uuid, addedByProbe);
                    continue;
                }
                // 找「生死门控」：加入 uuid 后 isDeadOrDying / isRemoved 是否翻转
                boolean deadBefore = victim.isDeadOrDying();
                boolean removedBefore = victim.isRemoved();
                List<Field> dieGates = new ArrayList<>();
                List<Field> removeGates = new ArrayList<>();
                for (Field gateField : sets) {
                    if (gateField == setField) continue;
                    if (ensureInSet(gateField, shadow, uuid, addedByProbe)) {
                        try {
                            if (!deadBefore && victim.isDeadOrDying()) dieGates.add(gateField);
                            else if (!removedBefore && victim.isRemoved()) removeGates.add(gateField);
                        } catch (Throwable ignored) {
                        }
                        releaseSet(gateField, shadow, uuid, addedByProbe);
                    }
                }
                releaseSet(setField, shadow, uuid, addedByProbe);
                return new ShadowPlan((Field) accessor[0], (Method) accessor[1],
                        setField, healthMap, dieGates, removeGates);
            }
            return null;
        } finally {
            for (Field added : addedByProbe) {
                removeFromSet(added, shadow, uuid);
            }
        }
    }

    /** 取 Set 字段的集合视图；字段不存在 / 不是 Set 时返回 null */
    @SuppressWarnings("unchecked")
    static Set<Object> setOf(Field setField, Object target) {
        Object setObject = setField == null ? null : readFieldObject(setField, target);
        return setObject instanceof Set<?> ? (Set<Object>) setObject : null;
    }

    /** 确保值在集合里（本来就在也算成功）；新加入的字段记进 {@code addedByProbe} 以便探测结束后清理 */
    private static boolean ensureInSet(Field setField, Object target, Object value, Set<Field> addedByProbe) {
        Set<Object> set = setOf(setField, target);
        if (set == null) return false;
        try {
            if (set.add(value)) addedByProbe.add(setField);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 释放某个集合里「本次探测新加入」的成员；探测前就存在的成员不动 */
    private static void releaseSet(Field setField, Object target, Object value, Set<Field> addedByProbe) {
        if (addedByProbe.remove(setField)) {
            removeFromSet(setField, target, value);
        }
    }

    /** Map 字段的当前值快照：{Field, 原值是否存在, 原值}；非 Map 返回 null */
    static Object[] snapshotMap(Field mapField, Object target, UUID key) {
        Object mapObject = readFieldObject(mapField, target);
        if (!(mapObject instanceof Map<?, ?> map)) return null;
        return new Object[]{mapField, map.containsKey(key), map.get(key)};
    }

    @SuppressWarnings("unchecked")
    static boolean setMapValue(Field mapField, Object target, UUID key, float value) {
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
    static void restoreMap(Object[] snapshot, Object target, UUID key) {
        if (snapshot == null) return;
        Object mapObject = readFieldObject((Field) snapshot[0], target);
        if (!(mapObject instanceof Map<?, ?>)) return;
        Map<Object, Object> map = (Map<Object, Object>) mapObject;
        try {
            if ((Boolean) snapshot[1]) map.put(key, snapshot[2]);
            else map.remove(key);
        } catch (Throwable ignored) {
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
        }
    }

    /** 把 value 加进全部给定集合；「本次新加入」的字段记进 {@code added}，供失败时精确还原 */
    private static void addAllToSets(List<Field> fields, Object target, Object value, Set<Field> added) {
        for (Field field : fields) {
            if (addToSet(field, target, value)) added.add(field);
        }
    }

    private static final MacAuthorityPlan NO_MAC_AUTHORITY =
            new MacAuthorityPlan(null, null, null, null, null, null, null, null, null, null, null, null);

    private static final Map<Class<?>, MacAuthorityPlan> MAC_AUTHORITY_CACHE = new ConcurrentHashMap<>();

    /** MAC 权威层「没探到」时的退避缓存 */
    static final NegativeCache<Class<?>> MAC_AUTHORITY_NEGATIVE =
            new NegativeCache<>("MAC 权威记录");

    /**
     * MAC 保护的权威记录方案。记录形状不写死：{@code fields} 是记录类按声明顺序的全部实例字段（与规范构造器
     * 一一对应），其余字段是被认领的角色；构造新记录时未认领字段沿用原记录的值，版本号、世界种子等原样保留。
     * {@code verify} 是可选自校验方法（探到就调用，新记录不通过而旧记录通过时整体回滚）。
     */
    record MacAuthorityPlan(Field store, Constructor<?> constructor, Method encrypt, Method mac,
                            Field[] fields, Field stringField, Field maxField, Field tickField, Field macField,
                            Object[] macExtra, Field[] macExtraFields, Method verify) {
    }

    /** 「MAC 权威层未探到方案」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> MAC_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    static void writeViaMacAuthority(LivingEntity victim, float healthAfter, DamageSource source) {
        MacAuthorityPlan plan = macAuthorityPlan(victim);
        if (plan == null) {
            // 这一层在 GreatSage 一类实体上是唯一能一击致死的路径，缺方案必须能在日志里看见；结论按类稳定，只打一次（D4）
            if (oncePer(MAC_MISS_LOGGED, victim.getClass(), "MAC 权威层无方案")) {
                probeLog("MAC 权威记录层：未探到方案，跳过（{}）", victim.getClass().getName());
            }
            return;
        }
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
        // 探针前快照：校验不通过时必须整体还原，否则会把 tick=-1 之类的错值永久留在模组权威表里
        Object[] previousArgs = readMacArgs(plan, current);
        if (previousArgs == null) return;
        try {
            String encoded = (String) plan.encrypt().invoke(null, healthAfter);
            if (encoded == null) return;
            // 时间戳一律沿用记录当前值。不能改写成 -1 之类的哨兵去跳过无敌帧：那会改动被 MAC 覆盖的字段，
            // 届时非致死写入生效、每次致死写入都被拒收，表现为「能掉血但永远打不死」。要跳无敌帧得另找门路。
            long tick = plan.tickField() == null ? 0L : plan.tickField().getLong(current);
            Long computedMac = computeMac(plan, current, encoded, tick);
            if (computedMac == null) {
                probeLog("MAC 权威记录层：MAC 计算失败（记录字段/方法形参类型不可判定），放弃本层（{}）",
                        victim.getClass().getName());
                return;
            }
            long mac = computedMac;

            // 按「声明顺序字段 ↔ 规范构造器参数」逐个填参：认领的角色写新值，其余沿用原值
            Object[] args = previousArgs.clone();
            boolean typeUnsafe = false;
            for (int i = 0; i < plan.fields().length; i++) {
                Field field = plan.fields()[i];
                if (field == plan.stringField()) {
                    args[i] = encoded;
                } else if (field == plan.macField()) {
                    // 类型取自原记录同位置的实参（字段可能声明成 Object）
                    args[i] = boxNumericLike(previousArgs[i], mac);
                    if (args[i] == null) typeUnsafe = true;
                } else if (field == plan.tickField()) {
                    args[i] = boxNumericLike(previousArgs[i], tick);
                    if (args[i] == null) typeUnsafe = true;
                }
            }
            if (typeUnsafe) {
                probeLog("MAC 权威记录层：记录字段类型不可判定（声明类型被擦除且原值为空），放弃本层（{}）",
                        victim.getClass().getName());
                return;
            }
            Object rebuilt = plan.constructor().newInstance(args);
            Object previous = plan.constructor().newInstance(previousArgs);

            // 自校验首选 MAC 自洽：用模组自己的 mac() 按记录实际字段值重算并比对记录里存的 MAC，
            // 这是唯一可信的判据（记录本就是同一个 mac() 造的）。verify 只是猜出来的「静态 boolean(记录)」方法，
            // 语义可能是别的——实测 GreatSage 上它把每次致死写入都拒掉（非致死全放行），目标卡在最后一点血；
            // 因此 MAC 自洽时不让 verify 否决，只有自洽也过不了才回退到 verify。
            boolean selfConsistent = macSelfConsistent(plan, rebuilt);
            if (!selfConsistent && plan.verify() != null) {
                if (macVerifyAcceptable(plan.verify(), store, victim, rebuilt, previous)) {
                    selfConsistent = true;
                } else {
                    probeLog("MAC 权威记录层：新记录未通过模组自校验（且 MAC 自洽校验也不通过），放弃本层（{}）："
                                    + "密文={} tick={} 记录MAC={} 重算MAC={} 上限={}",
                            victim.getClass().getName(), encoded, tick, rawMac(plan, rebuilt),
                            recomputedMac(plan, rebuilt), plan.maxField() == null ? "-" : safeFloat(plan.maxField(), rebuilt));
                    return;
                }
            }
            if (selfConsistent && plan.verify() != null
                    && !macVerifyAcceptable(plan.verify(), store, victim, rebuilt, previous)
                    && oncePer(MAC_VERIFY_OVERRIDDEN_LOGGED, victim.getClass(), "模组自校验非 MAC 校验")) {
                probeLog("MAC 权威记录层：模组自校验方法疑似不是 MAC 校验（我们的记录与 mac() 自洽却仍被它否决），已以 MAC 自洽为准（{}）",
                        victim.getClass().getName());
            }
            store.put(victim, rebuilt);
        } catch (Throwable t) {
            // 原来这里静默吞异常，导致「整轮没有任何一层日志」时无法定位
            rethrowIfFatal(t);
            probeLog("MAC 权威记录层异常已拦截：{}（{}）", victim.getClass().getName(), t);
            return;
        }
        float now = healthOf(victim);
        if (!matches(now, healthAfter)) {
            probeLog("MAC 权威记录层未生效：目标={} getHealth={}", healthAfter, now);
            // 整体还原原记录（含原 tick / 原 MAC）
            try {
                store.put(victim, plan.constructor().newInstance(previousArgs));
            } catch (Throwable ignored) {
                probeLog("MAC 权威记录层还原失败：{}（记录可能停在探测值）", victim.getClass().getName());
            }
            return;
        }
        probeLog("MAC 权威记录层写入生效：目标={} getHealth={}", healthAfter, now);
        // 致死：血量归零后再走一次它自己的 hurt，让模组自己的流程完成授权与清理
        if (healthAfter <= 0.0F && source != null) {
            PENDING_FORCED_DEATH.add(victim);
            try {
                victim.hurt(source, Math.max(1.0F, plan.maxField() == null
                        ? healthOf(victim) : plan.maxField().getFloat(current)));
            } catch (Throwable ignored) {
                // 模组的 hurt 拒绝时保持现状
            } finally {
                PENDING_FORCED_DEATH.remove(victim);
            }
        }
    }

    /** 读出一条记录的全部字段值（按声明顺序），供构造新记录时沿用未认领字段 */
    private static Object[] readMacArgs(MacAuthorityPlan plan, Object record) {
        Field[] fields = plan.fields();
        Object[] args = new Object[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) {
                args[i] = fields[i].get(record);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return args;
    }

    /**
     * 按声明类型装箱长整型（long/int/short/byte）；声明类型被擦除（Object/Number/Comparable）时返回 {@code null}（拒绝写入）。
     * 兜底绝不能直接返回 {@code Long}：字段声明成 {@code Object} 而实际存 Integer/Byte 时，模组强转会抛 ClassCastException。
     */
    private static Object boxLong(Class<?> type, long value) {
        if (type == int.class || type == Integer.class) return (int) value;
        if (type == short.class || type == Short.class) return (short) value;
        if (type == byte.class || type == Byte.class) return (byte) value;
        if (type == long.class || type == Long.class) return value;
        return null;
    }

    /**
     * 按「样本（同一位置原来的实际值）」的运行时类型装箱长整型；样本类型不可判定时返回 {@code null}。记录/容器字段常声明成 {@code Object}，此时唯一可靠的类型来源就是它当前存的对象。
     */
    private static Object boxNumericLike(Object sample, long value) {
        if (sample instanceof Integer) return (int) value;
        if (sample instanceof Short) return (short) value;
        if (sample instanceof Byte) return (byte) value;
        if (sample instanceof Long) return value;
        if (sample instanceof Float) return (float) value;
        if (sample instanceof Double) return (double) value;
        return null;
    }

    /** 同上，但兼容浮点参数（校验方法的签名可能是 float/double） */
    private static Object boxNumeric(Class<?> type, long value) {
        if (type == float.class || type == Float.class) return (float) value;
        if (type == double.class || type == Double.class) return (double) value;
        return boxLong(type, value);
    }

    /**
     * 调用模组自己的校验方法判断新记录是否可接受。只有「新记录不通过、而旧记录通过」时才算不可接受；否则说明它本来就不是用来校验这条记录的（或参数凑不齐），不能因此否掉整层。
     */
    private static boolean macVerifyAcceptable(Method verify, Object store, LivingEntity victim,
                                               Object rebuilt, Object previous) {
        try {
            Object[] args = macVerifyArgs(verify, store, victim, rebuilt);
            if (args == null) return true;
            if (!(verify.invoke(null, args) instanceof Boolean ok) || ok) return true;
            Object[] oldArgs = macVerifyArgs(verify, store, victim, previous);
            if (oldArgs == null) return true;
            return !(verify.invoke(null, oldArgs) instanceof Boolean oldOk) || !oldOk;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 为校验方法凑实参；凑不齐（类型不确定）时返回 null，表示不做判定 */
    private static Object[] macVerifyArgs(Method verify, Object store, LivingEntity victim, Object record) {
        Class<?>[] params = verify.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (p.isInstance(record)) {
                args[i] = record;
            } else if (p == int.class || p == Integer.class) {
                args[i] = victim.getId();
            } else if (p == long.class || p == Long.class) {
                args[i] = 0L;
            } else if (p == float.class || p == Float.class) {
                args[i] = healthOf(victim);
            } else if (p == double.class || p == Double.class) {
                args[i] = (double) healthOf(victim);
            } else if (p.isInstance(store)) {
                args[i] = store;
            } else if (p.isInstance(victim)) {
                args[i] = victim;
            } else if (p == UUID.class) {
                args[i] = victim.getUUID();
            } else {
                return null;
            }
        }
        return args;
    }

    /** A：在「满血退化锚点」下建出的方案（锚点一旦离开满血就丢弃重探一次） */
    private static final Set<Class<?>> MAC_FULL_ANCHOR_PLANS = ConcurrentHashMap.newKeySet();

    static MacAuthorityPlan macAuthorityPlan(LivingEntity victim) {
        Class<?> type = victim.getClass();
        MacAuthorityPlan cached = MAC_AUTHORITY_CACHE.get(type);
        if (cached != null && MAC_FULL_ANCHOR_PLANS.contains(type)
                && !fullHealthAnchor(victim, currentHealth(victim))) {
            MAC_AUTHORITY_CACHE.remove(type, cached);
            MAC_FULL_ANCHOR_PLANS.remove(type);
            cached = null;
        }
        if (cached != null) return cached == NO_MAC_AUTHORITY ? null : cached;
        if (MAC_AUTHORITY_NEGATIVE.blocked(type)) return null;
        // 同 shadowPlans：收割未就绪不能跳过本层（GreatSage 的一击致死正是靠这一层），只影响空结论是否退避
        boolean complete = seedHarvestReady(victim);
        MacAuthorityPlan found = null;
        try {
            found = discoverMacAuthority(victim);
        } catch (Throwable ignored) {
            // 探测失败留待下次
        }
        if (found != null) {
            MAC_AUTHORITY_CACHE.put(type, found);
            if (fullHealthAnchor(victim, currentHealth(victim))) MAC_FULL_ANCHOR_PLANS.add(type);
            else MAC_FULL_ANCHOR_PLANS.remove(type);
            MAC_AUTHORITY_NEGATIVE.clear(type);
        } else {
            // 负结果退避：没探到时每个致死命中都会重跑整套发现（候选类最多 8704 个 × 字段扫描 × 真调用模组编解码器）
            markDiscoveryMiss(MAC_AUTHORITY_NEGATIVE, type, complete);
        }
        return found;
    }

    static MacAuthorityPlan discoverMacAuthority(LivingEntity victim) {
        float health = healthOf(victim);
        if (Float.isNaN(health) || health <= 0.0F) return null;
        List<Class<?>> candidates = healthCoreCandidates(victim).classes();
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            for (Field field : declaredFieldsOf(clazz)) {
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

    /**
     * 认领记录里的角色并找出「编码器 / 校验器 / 规范构造器」。记录类按声明顺序的全部实例字段就是构造器参数（record 的规范构造器天然满足），
     * 其中有一个 String（密文）、一个 float（上限/血量）、至少一个整型（MAC，必要时再认领时间戳）就能组方案；认领不到时间戳时只在记录上写 MAC，时间戳沿用原值。
     */
    static MacAuthorityPlan buildMacPlan(Field store, Object value, List<Class<?>> candidates, float health) {
        Class<?> valueType = value.getClass();
        List<Field> all = new ArrayList<>();
        Field stringField = null;
        Field maxField = null;
        List<Field> intFields = new ArrayList<>(4);
        for (Field field : declaredFieldsOf(valueType)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            Class<?> type = field.getType();
            all.add(field);
            if (type == String.class) {
                if (stringField == null) stringField = field;
            } else if (type == float.class) {
                if (maxField == null) maxField = field;
            } else if (type == long.class || type == int.class || type == short.class || type == byte.class) {
                intFields.add(field);
            }
        }
        if (stringField == null || maxField == null || intFields.isEmpty() || all.isEmpty()) return null;
        Field[] fields = all.toArray(new Field[0]);

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : valueType.getDeclaredConstructors()) {
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length != fields.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> f = fields[i].getType();
                if (params[i].isPrimitive() || f.isPrimitive()) {
                    if (params[i] != f) {
                        ok = false;
                        break;
                    }
                } else if (!params[i].isAssignableFrom(f)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            try {
                candidate.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            constructor = candidate;
            break;
        }
        if (constructor == null) return null;

        String encrypted;
        float max;
        try {
            encrypted = (String) stringField.get(value);
            max = maxField.getFloat(value);
        } catch (Throwable ignored) {
            return null;
        }
        if (encrypted == null || !Float.isFinite(max)) return null;

        Method encrypt = findMacEncrypt(candidates, health, encrypted, value);
        if (encrypt == null) return null;

        MacMethod macMethod = findMacMethod(candidates, encrypted, value, intFields);
        if (macMethod == null) return null;

        Method verify = findMacVerify(candidates, valueType, store);

        return new MacAuthorityPlan(store, constructor, encrypt, macMethod.method(),
                fields, stringField, maxField, macMethod.tickField(), macMethod.macField(),
                macMethod.extra(), macMethod.extraFields(), verify);
    }

    /** MAC 校验器 + 它认领的角色字段（时间戳 / MAC 值）以及它实际用到的额外实参 */
    private record MacMethod(Method method, Field tickField, Field macField,
                             Object[] extra, Field[] extraFields) {
    }

    /** 一个实参候选：值 + 它来自记录里的哪个字段（合成不出来时为 null） */
    private record MacArg(Object value, Field field) {
    }

    /** 找编码器：静态、返回 String、首个参数是浮点（要编码的血量）；其余参数用记录自身同类型字段的当前值去凑。 */
    private static Method findMacEncrypt(List<Class<?>> candidates, float health, String encrypted,
                                         Object record) {
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                if (method.getReturnType() != String.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params.length > 3) continue;
                if (params[0] != float.class && params[0] != double.class) continue;
                for (MacArg[] extra : macExtraArgs(params, 1, record)) {
                    Object[] args = new Object[params.length];
                    args[0] = params[0] == float.class ? (Object) health : (Object) (double) health;
                    for (int k = 0; k < extra.length; k++) args[k + 1] = extra[k].value();
                    try {
                        if (method.invoke(null, args) instanceof String text && text.equals(encrypted)) {
                            return method;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找 MAC 校验器：静态、返回整型、首个参数是密文串、其余参数是浮点/整型；用记录自身的字段值去凑参数，结果正好等于某个整型字段的当前值就认下，并记住哪个字段是 MAC 值、实参来自哪些字段（写回时按来源刷新）。
     */
    private static MacMethod findMacMethod(List<Class<?>> candidates, String encrypted, Object record,
                                           List<Field> intFields) {
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                Class<?> returns = method.getReturnType();
                if (returns != long.class && returns != int.class && returns != short.class
                        && returns != byte.class) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 2 || params.length > 4 || params[0] != String.class) continue;
                boolean numeric = true;
                for (int i = 1; i < params.length; i++) {
                    Class<?> p = params[i];
                    if (p != float.class && p != double.class && p != long.class && p != int.class) {
                        numeric = false;
                        break;
                    }
                }
                if (!numeric) continue;
                for (MacArg[] extra : macExtraArgs(params, 1, record)) {
                    Object[] args = new Object[params.length];
                    args[0] = encrypted;
                    for (int k = 0; k < extra.length; k++) args[k + 1] = extra[k].value();
                    Number result;
                    try {
                        result = (Number) method.invoke(null, args);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (result == null) continue;
                    long produced = result.longValue();
                    for (Field field : intFields) {
                        long fieldValue;
                        try {
                            fieldValue = field.getLong(record);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (fieldValue != produced) continue;
                        Object[] values = new Object[extra.length];
                        Field[] sources = new Field[extra.length];
                        for (int k = 0; k < extra.length; k++) {
                            values[k] = extra[k].value();
                            sources[k] = extra[k].field();
                        }
                        Field tick = null;
                        for (int k = 0; k < extra.length && tick == null; k++) {
                            if (params[k + 1] == long.class || params[k + 1] == int.class) {
                                tick = sources[k];
                            }
                        }
                        return new MacMethod(method, tick, field, values, sources);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 为方法的「第 start 个参数起」凑实参：每个参数类型都尝试记录里同类型字段的当前值（每层最多 4 个候选、组合数封顶 24，避免组合爆炸）。{@code record} 为 null 时给出单一默认组合。
     */
    private static List<MacArg[]> macExtraArgs(Class<?>[] params, int start, Object record) {
        MacArg[] base = new MacArg[params.length - start];
        java.util.Arrays.fill(base, new MacArg(null, null));
        List<MacArg[]> out = new ArrayList<>();
        out.add(base);
        if (record == null) return out;
        List<Field> allFields = new ArrayList<>();
        for (Field field : declaredFieldsOf(record.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            allFields.add(field);
        }
        for (int i = start; i < params.length; i++) {
            Class<?> p = params[i];
            List<MacArg> choices = new ArrayList<>(4);
            for (Field field : allFields) {
                if (!compatible(p, field.getType())) continue;
                try {
                    Object v = field.get(record);
                    if (v != null) choices.add(new MacArg(v, field));
                } catch (Throwable ignored) {
                }
                if (choices.size() >= 4) break;
            }
            if (choices.isEmpty()) choices.add(new MacArg(defaultOf(p), null));
            List<MacArg[]> grown = new ArrayList<>(out.size() * choices.size());
            for (MacArg[] prefix : out) {
                for (MacArg choice : choices) {
                    MacArg[] copy = prefix.clone();
                    copy[i - start] = choice;
                    grown.add(copy);
                }
            }
            out = grown;
            if (out.size() > 24) out = new ArrayList<>(out.subList(0, 24));
        }
        return out;
    }

    /** 参数类型能否用该字段类型喂（同型或可装箱兼容） */
    private static boolean compatible(Class<?> param, Class<?> fieldType) {
        if (param == fieldType) return true;
        if (param == long.class && (fieldType == int.class || fieldType == short.class || fieldType == byte.class)) return true;
        if (param == int.class && (fieldType == short.class || fieldType == byte.class)) return true;
        if (param == float.class && (fieldType == int.class || fieldType == short.class || fieldType == byte.class)) return true;
        return param == double.class && (fieldType == float.class || fieldType == long.class
                || fieldType == int.class || fieldType == short.class || fieldType == byte.class);
    }

    private static Object defaultOf(Class<?> type) {
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        return null;
    }

    /** 把记录里某个字段的当前值按目标参数类型装箱（写回时刷新 MAC 实参用） */
    private static Object macArgFrom(Field field, Object record, Class<?> param) {
        // 形参类型被擦除（Object/Number）时唯一可靠的类型来源是记录里当前存的那个对象本身：原样传过去，绝不按声明类型猜装箱（猜错会让模组之后强转时崩）。
        try {
            Object value = field.get(record);
            if (!(value instanceof Number number)) return value;
            if (ModWriterProbe.isErasedNumericParam(param)) return value;
            if (param == float.class) return number.floatValue();
            if (param == double.class) return number.doubleValue();
            if (param == int.class) return number.intValue();
            if (param == long.class) return number.longValue();
            return number;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 「模组自校验方法并非 MAC 校验、已按 MAC 自洽处理」只打一次（每实体类） */
    private static final Set<String> MAC_VERIFY_OVERRIDDEN_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 记录是否与模组自己的 MAC 函数自洽：用记录实际的字段值重算 MAC，与记录里存的 MAC 比较。
     * 这是唯一可信的判据（记录本就是用同一个 {@code mac()} 造的）；{@link #findMacVerify} 挑到的只是「静态 boolean(记录)」方法，语义可能是别的东西。
     */
    private static boolean macSelfConsistent(MacAuthorityPlan plan, Object record) {
        Long stored = rawMac(plan, record);
        if (stored == null) return false;
        Long recomputed = recomputedMac(plan, record);
        return recomputed != null && recomputed.longValue() == stored;
    }

    /** 记录里存的 MAC（读不出来返回 {@code null}） */
    private static Long rawMac(MacAuthorityPlan plan, Object record) {
        if (plan.macField() == null) return null;
        try {
            return plan.macField().get(record) instanceof Number number ? number.longValue() : null;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 按记录实际的字段值用模组自己的 mac() 重算的 MAC（算不出来返回 {@code null}） */
    private static Long recomputedMac(MacAuthorityPlan plan, Object record) {
        if (plan.mac() == null || plan.stringField() == null) return null;
        try {
            if (!(plan.stringField().get(record) instanceof String text)) return null;
            long tick = plan.tickField() == null ? 0L : plan.tickField().getLong(record);
            return computeMac(plan, record, text, tick);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 诊断用：读记录的某个数值字段（读不到返回 0） */
    private static float safeFloat(Field field, Object record) {
        try {
            return field.getFloat(record);
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    /** 按发现时确定的实参来源重算 MAC：时间戳参数用本次 tick，其余参数沿用记录里的当前值 */
    private static Long computeMac(MacAuthorityPlan plan, Object record, String encoded, long tick) {
        Class<?>[] params = plan.mac().getParameterTypes();
        Object[] args = new Object[params.length];
        args[0] = encoded;
        for (int k = 0; k < plan.macExtra().length; k++) {
            Field source = plan.macExtraFields()[k];
            if (source != null && source == plan.tickField()) {
                Object sample = null;
                try {
                    sample = source.get(record);
                } catch (Throwable ignored) {
                    // 读不到原值：下面的 boxNumeric 会按声明类型判断，判断不出来就放弃本次 MAC
                }
                args[k + 1] = sample instanceof Number ? boxNumericLike(sample, tick)
                        : boxNumeric(params[k + 1], tick);
                if (args[k + 1] == null) return null;
            } else if (source != null) {
                args[k + 1] = macArgFrom(source, record, params[k + 1]);
            } else {
                args[k + 1] = plan.macExtra()[k];
            }
        }
        try {
            Object result = plan.mac().invoke(null, args);
            return result instanceof Number number ? number.longValue() : null;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 找模组自己的自校验方法：静态、返回 boolean，参数能由「新记录 + 实体 + 存储表」喂满；找不到就跳过。 */
    private static Method findMacVerify(List<Class<?>> candidates, Class<?> recordType, Object store) {
        for (Class<?> clazz : candidates) {
            if (clazz == null || isVanillaClass(clazz)) continue;
            for (Method method : declaredStaticMethods(clazz)) {
                if (method.getReturnType() != boolean.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params.length > 3) continue;
                boolean usable = false;
                for (Class<?> p : params) {
                    if (p.isAssignableFrom(recordType)) {
                        usable = true;
                        break;
                    }
                }
                if (!usable) continue;
                // 参数表里不能有我们喂不出来的东西（例如第二个记录类型、复杂对象）
                boolean ok = true;
                for (Class<?> p : params) {
                    if (p.isAssignableFrom(recordType) || p == int.class || p == long.class
                            || p == float.class || p == double.class || p == UUID.class
                            || p == boolean.class || (p.isInstance(store))) {
                        continue;
                    }
                    ok = false;
                    break;
                }
                if (ok) return method;
            }
        }
        return null;
    }

    record AuthorityPlan(Method entryAccessor, Field accField, Field refField, Field lastField,
                                 Method reader, Method decoder, Method setter, Method lastSetter) {
    }

    private static final Map<Class<?>, List<AuthorityPlan>> AUTHORITY_PLAN_CACHE = new ConcurrentHashMap<>();

    /** 模组权威层「没探到」时的退避缓存 */
    static final NegativeCache<Class<?>> AUTHORITY_NEGATIVE =
            new NegativeCache<>("模组权威条目");

    /** 已为某个实体类强制尝试过一次权威按量写入 */
    private static final Set<Class<?>> AUTHORITY_PROBED = ConcurrentHashMap.newKeySet();

    private static final int AUTHORITY_MAX_PLANS = 4;

    private static final int AUTHORITY_MAX_READERS = 32;

    /** 权威累计值写入方法单次允许的最大增量 */
    private static final float AUTHORITY_WRITE_STEP = 1000.0F;

    /** 权威按量写入的分步迭代上限（快路径失败时才会用到的兜底；进度跨击累积） */
    private static final int AUTHORITY_MAX_STEPS = 64;

    /** 把模组权威值直接压到 0（之后交给模组自己的死亡/移除流程） */
    static void writeViaModAuthority(LivingEntity victim) {
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

    /** 「权威按量写入放弃」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> AUTHORITY_DELTA_LOGGED = ConcurrentHashMap.newKeySet();

    /** 非致死：用模组自己的权威「累计值写入方法」把累计值按真伤量增加 */
    static void writeViaModAuthorityDelta(LivingEntity victim, float targetHealth) {
        String type = victim.getClass().getName();
        List<AuthorityPlan> plans = authorityPlans(victim);
        if (plans.isEmpty()) {
            if (oncePer(AUTHORITY_DELTA_LOGGED, victim.getClass(), "权威按量写入无方案")) {
                probeLog("权威按量写入放弃（{}）：一个权威方案都没探到", type);
            }
            return;
        }
        for (AuthorityPlan plan : plans) {
            if (plan.decoder() == null || plan.setter() == null) {
                if (oncePer(AUTHORITY_DELTA_LOGGED, victim.getClass(), "权威方案缺件")) {
                    probeLog("权威按量写入放弃（{}）：方案缺件（解码方法={}、写入方法={}）",
                            type, plan.decoder() != null, plan.setter() != null);
                }
                continue;
            }
            Object entry = invokeObject(plan.entryAccessor(), null, new Object[]{victim});
            if (entry == null) {
                if (oncePer(AUTHORITY_DELTA_LOGGED, victim.getClass(), "取不到权威条目")) {
                    probeLog("权威按量写入放弃（{}）：取不到权威条目", type);
                }
                continue;
            }
            float max = decodeAuthority(plan.decoder(), readFieldObject(plan.refField(), entry));
            float acc = decodeAuthority(plan.decoder(), readFieldObject(plan.accField(), entry));
            if (Float.isNaN(max) || Float.isNaN(acc) || max <= 0.0F) {
                if (oncePer(AUTHORITY_DELTA_LOGGED, victim.getClass(), "权威解码异常")) {
                    probeLog("权威按量写入放弃（{}）：解码异常（上限={}、累计={}）", type, max, acc);
                }
                continue;
            }
            float target = Math.max(0.0F, Math.min(max, max - targetHealth));
            if (target <= acc + PROBE_EPSILON) {
                // 目标已达成：持续攻击时每击都会走到这里，只打一次（D4）
                if (oncePer(AUTHORITY_DELTA_LOGGED, victim.getClass(), "权威累计已达目标")) {
                    probeLog("权威按量写入放弃（{}）：当前累计={} 已达目标={}（权威上限={}）", type, acc, target, max);
                }
                continue;
            }
            float current = acc;
            boolean written;
            // 快路径：多数权威写口接受「绝对值」，一次就能写到目标
            if (invokeQuietly(plan.setter(), new Object[]{victim, target, 1.0F})) {
                current = target;
                written = true;
            } else {
                // 退回分步推进并加硬性迭代上限：不能无界 while 累加（1e7 血的 Boss 就是 1e4 次模组方法调用，整 tick 卡死）。
                // 分步没走完不会丢覆盖：本层每个命中都会再跑一次，进度跨击累积。
                written = true;
                for (int step = 0; step < AUTHORITY_MAX_STEPS && current + PROBE_EPSILON < target; step++) {
                    float next = Math.min(target, current + AUTHORITY_WRITE_STEP);
                    if (!invokeQuietly(plan.setter(), new Object[]{victim, next, 1.0F})) {
                        probeLog("权威按量写入放弃（{}）：写入方法调用失败（累计 {} → {}）", type, current, next);
                        written = false;
                        break;
                    }
                    current = next;
                }
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
    static float decodeAuthority(Method decoder, Object cipher) {
        if (cipher == null) return Float.NaN;
        Double value = invokeForNumber(decoder, cipher, new Object[0]);
        return value == null ? Float.NaN : value.floatValue();
    }

    /** 「上一帧累计值未推进」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> AUTHORITY_LAST_LOGGED = ConcurrentHashMap.newKeySet();

    /** 把「上一帧累计值」推到 {@code target} */
    static void pushAuthorityLast(LivingEntity victim, AuthorityPlan plan, Object entry, float target) {
        if (plan.lastSetter() == null || plan.lastField() == null || plan.decoder() == null) return;
        float current = decodeAuthority(plan.decoder(), readFieldObject(plan.lastField(), entry));
        if (Float.isNaN(current)) return;
        // 与主按量写入循环同一口径的硬性迭代上限：不能无界 while（步长 1000F，1e7 血目标是 10000 次
        // 「反射调用 + 密文解码回读」，恰好落在击杀那一击上）。分步没走完不会丢覆盖：每个命中都会再跑一次。
        for (int step = 0; step < AUTHORITY_MAX_STEPS && current + PROBE_EPSILON < target; step++) {
            float next = Math.min(target, current + AUTHORITY_WRITE_STEP);
            if (!invokeQuietly(plan.lastSetter(), new Object[]{victim, next, 1.0F})) {
                if (oncePer(AUTHORITY_LAST_LOGGED, victim.getClass(), "上一帧累计值写入失败")) {
                    probeLog("权威上一帧累计值写入失败（{}）：{} → {}", victim.getClass().getName(), current, next);
                }
                return;
            }
            float observed = decodeAuthority(plan.decoder(), readFieldObject(plan.lastField(), entry));
            if (Float.isNaN(observed) || observed <= current + PROBE_EPSILON) {
                if (oncePer(AUTHORITY_LAST_LOGGED, victim.getClass(), "上一帧累计值写入未推进")) {
                    probeLog("权威上一帧累计值写入未推进（{}）：{} → {}（目标 {}）",
                            victim.getClass().getName(), current, observed, target);
                }
                return;
            }
            current = observed;
        }
    }

    /**
     * 权威累计值口径下的「仍未到位」：权威上限 − decode(acc) 仍大于目标。不能只看 {@code getHealth()} 就认定已写到位：影子层写的是模组的
     * 影子表（{@code Map<UUID,Float>} 形态），getHealth() 会跟着变，但模组自己的权威累计值 {@code acc} 没动；对方的死亡判据（acc >= 上限-1）
     * 与聊天/调试读数都读 acc，于是表现为「我们以为写好了，对方既不掉读数也不死」（实测 Mbt_New 的 NetheriteGuardian 读数停在 499.4）。
     * 本判据绕开影子表、直接按 acc 口径判定：影子层假成功时返回 true，权威层获得一次出手机会；权威确实写到位的实体返回 false，
     * 不白跑一次权威层（致死路径的密文互换不可逆，绝不能对已写好的实体乱做）；没有解码方法 / 取不到条目 / 解码失败一律返回 false。
     */
    static boolean authorityStillAbove(LivingEntity victim, float healthAfter) {
        for (AuthorityPlan plan : authorityPlans(victim)) {
            if (plan.decoder() == null) continue;
            Object entry = invokeObject(plan.entryAccessor(), null, new Object[]{victim});
            if (entry == null) continue;
            float max = decodeAuthority(plan.decoder(), readFieldObject(plan.refField(), entry));
            float acc = decodeAuthority(plan.decoder(), readFieldObject(plan.accField(), entry));
            if (Float.isNaN(max) || Float.isNaN(acc)) continue;
            float health = max - acc;
            if (health > healthAfter && !matches(health, healthAfter)) return true;
        }
        return false;
    }

    /** 是否值得走权威按量写入：有权威方案的类每击都补，没见过的类首击强制试一次 */
    static boolean shouldWriteModAuthority(LivingEntity victim, float healthAfter) {
        if (stillAboveTarget(victim, healthAfter)) return true;
        if (AUTHORITY_PLAN_CACHE.containsKey(victim.getClass())) return true;
        return AUTHORITY_PROBED.add(victim.getClass());
    }

    /** A：在「满血退化锚点」下建出的方案（锚点一旦离开满血就丢弃重探一次） */
    private static final Set<Class<?>> AUTHORITY_FULL_ANCHOR_PLANS = ConcurrentHashMap.newKeySet();

    static List<AuthorityPlan> authorityPlans(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<AuthorityPlan> cached = AUTHORITY_PLAN_CACHE.get(type);
        if (cached != null && AUTHORITY_FULL_ANCHOR_PLANS.contains(type)
                && !fullHealthAnchor(victim, currentHealth(victim))) {
            AUTHORITY_PLAN_CACHE.remove(type, cached);
            AUTHORITY_FULL_ANCHOR_PLANS.remove(type);
            cached = null;
        }
        if (cached != null) return cached;
        if (AUTHORITY_NEGATIVE.blocked(type)) return List.of();
        // 同 shadowPlans：收割未就绪不能跳过本层，只影响空结论要不要进入退避
        boolean complete = seedHarvestReady(victim);
        List<AuthorityPlan> found;
        try {
            found = discoverAuthorityPlans(victim);
        } catch (Throwable t) {
            markDiscoveryMiss(AUTHORITY_NEGATIVE, type, complete);
            return List.of();
        }
        if (!found.isEmpty()) {
            AUTHORITY_PLAN_CACHE.put(type, found);
            if (fullHealthAnchor(victim, currentHealth(victim))) AUTHORITY_FULL_ANCHOR_PLANS.add(type);
            else AUTHORITY_FULL_ANCHOR_PLANS.remove(type);
            AUTHORITY_NEGATIVE.clear(type);
        } else {
            // 负结果退避：探测会写活的模组权威条目并真调用其读写方法
            markDiscoveryMiss(AUTHORITY_NEGATIVE, type, complete);
        }
        return found;
    }

    static List<AuthorityPlan> discoverAuthorityPlans(LivingEntity victim) {
        float anchor = currentHealth(victim);
        if (Float.isNaN(anchor) || anchor <= 0.0F) return List.of();
        List<Class<?>> candidates = healthCoreCandidates(victim).classes();
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
        for (Method method : declaredMethodsOf(clazz)) {
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
        for (Method method : declaredMethodsOf(clazz)) {
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
    static AuthorityPlan probeAuthority(LivingEntity victim, Method accessor,
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
            for (Field field : declaredFieldsOf(current)) {
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
    static AuthorityPlan enrichAuthorityPlan(LivingEntity victim, AuthorityPlan plan) {
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
    record AuthoritySetters(Method acc, Method last) {
    }

    /** 权威累计值写入方法（形如 {@code (Entity, float, float) -> void}：第二参为累计值、第三参为守卫系数） */
    static AuthoritySetters authoritySetters(LivingEntity victim, AuthorityPlan plan, Method decoder,
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
        for (Method method : declaredMethodsOf(plan.entryAccessor().getDeclaringClass())) {
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
    static Field probeWriteField(LivingEntity victim, Method method, Method decoder, Object entry,
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

    static float readEntityNumber(Method method, LivingEntity victim) {
        Double value = method == null ? null : invokeForNumber(method, null, new Object[]{victim});
        return value == null ? Float.NaN : value.floatValue();
    }
}

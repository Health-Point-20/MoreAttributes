package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.CipherHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageWarmup.*;

/**
 * 「外部授权表」写入层（档 2+，通用发现，不写死任何模组 / 类名）。
 * 覆盖形状：血量真值被搬到模组自己的静态 / 单例容器里，实体方法（getHealth 等）被 agent / transformer
 * 改写成读该容器。容器可以是 Map（键 = 实体或其 UUID），也可以是元素集合（Set / List / 数组，
 * 元素里既有指向实体的字段、又有血量数值字段）。
 * 候选类来源：1) TrueDamageWarmup#healthCoreCandidates：实体常量池可达的模组类；
 * 2) #registryClasses：可达静态容器里持有的 Class 对象。第 2 条必需：把类体加密在自有容器文件里、
 * 运行期才 define 的工具类既没有 .class 资源、也不出现在实体常量池里，按类名加载会被直接跳过；
 * 注册表只收与受害实体同源的类，不调用任何工厂、不做 Class.forName，避免触发模组副作用。
 * 命中判定只读且保守：容器里必须已存在「指向该实体、且血量 ≈ 当前血量」的条目才算命中；
 * 写入后用 healthOf(victim) 校验，不达标即整体还原，并记负缓存退避。
 * 已知盲区：只写授权读数不足以阻止对方按结构完整性判据移除重建（如 avatar 的 corruptionTicks
 * 超阈值后移除重建），也不覆盖「真值不在容器里」的形状；本层不接管死亡，只扣血。
 */
final class StaticAuthorityLayer {

    private StaticAuthorityLayer() {
    }

    /** 表条目的键形态 */
    enum KeyKind {
        UUID, ENTITY
    }

    /** 容器形态 */
    enum ContainerKind {
        /** 键 → 值（值是 {@code Number} 或「条目对象」） */
        MAP,
        /** 元素集合：元素对象里既有指向实体的字段，又有血量数值字段 */
        ELEMENTS
    }

    /**
     * 一个命中方案。
     * singletonField 与 singletonFactory 都为 null 时 containerField 是静态字段；
     * 否则它是「经该访问器取到的单例对象」上的实例字段。
     * MAP：entryValueField 为 null 表示值是 Number，否则值是条目对象，血量在 entryValueField 里。
     * ELEMENTS：entryKeyField 是元素里指向本实体的字段，entryValueField 是元素里的血量字段。
     */
    record StaticAuthorityPlan(Field singletonField, Method singletonFactory, Field containerField,
                               ContainerKind kind, KeyKind keyKind, Field entryKeyField, Field entryValueField) {
    }

    /** 一个待写槽位：Map 条目（{@code field == null} 表示表值本身是 Number）或元素对象字段 */
    private static final class Slot {
        final Map<Object, Object> map;
        final Object key;
        final Object holder;
        final Field field;
        final Object snapshot;

        Slot(Map<Object, Object> map, Object key, Object holder, Field field, Object snapshot) {
            this.map = map;
            this.key = key;
            this.holder = holder;
            this.field = field;
            this.snapshot = snapshot;
        }

        boolean write(float healthAfter) {
            try {
                if (field == null) {
                    if (!(holder instanceof Number number)) return false;
                    // 保持条目现有的运行时类型：按擦除类型换装箱会让模组之后强转时崩
                    map.put(key, boxNumber(number.getClass(), healthAfter));
                    return true;
                }
                Object current = field.get(holder);
                Class<?> type = current instanceof Number number ? number.getClass() : field.getType();
                field.set(holder, boxNumber(type, healthAfter));
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        void restore() {
            try {
                if (field == null) {
                    map.put(key, snapshot);
                } else {
                    field.set(holder, snapshot);
                }
            } catch (Throwable ignored) {
                // 还原尽力而为
            }
        }
    }

    private static final Map<Class<?>, List<StaticAuthorityPlan>> PLAN_CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, List<Class<?>>> REGISTRY_CACHE = new ConcurrentHashMap<>();

    /** 本层「没探到」时的退避缓存 */
    static final NegativeCache<Class<?>> STATIC_AUTHORITY_NEGATIVE =
            new NegativeCache<>("外部授权表");

    private static final int MAX_PLANS = 8;

    private static final int MAX_STATIC_CONTAINERS = 64;

    private static final int MAX_ENTRY_FIELDS = 32;

    private static final int MAX_ELEMENTS = 4096;

    private static final int MAX_REGISTRY_CLASSES = 4096;

    private static final int MAX_REGISTRY_CONTAINERS = 24;

    private static final int MAX_REGISTRY_VALUES = 65536;

    /** 「外部授权表层未探到方案」只打一次（每 (实体类, 层)），避免刷屏 */
    private static final Set<String> PLAN_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    /** 把「外部授权表」里的该实体条目改成目标血量；未写到位则整体还原 */
    static void writeViaStaticAuthority(LivingEntity victim, float healthAfter, float damage) {
        List<StaticAuthorityPlan> plans = plans(victim);
        if (plans.isEmpty()) {
            if (oncePer(PLAN_MISS_LOGGED, victim.getClass(), "外部授权表层无方案")) {
                probeLog("外部授权表层：未探到方案，跳过（{}）", victim.getClass().getName());
            }
            return;
        }
        boolean attempted = false;
        for (StaticAuthorityPlan plan : plans) {
            Slot slot = resolveSlot(victim, plan);
            if (slot == null || !(slot.snapshot instanceof Number)) continue;
            attempted = true;
            if (!slot.write(healthAfter)) continue;
            // getHealth() 是本层唯一的校验依据
            float now = healthOf(victim);
            if (matches(now, healthAfter)) {
                probeLog("外部授权表写入生效：目标={} getHealth={} 扣血={}（{} 键={}）",
                        healthAfter, now, damage, plan.kind(), plan.keyKind());
                return;
            }
            probeLog("外部授权表未生效，已还原：目标={} getHealth={}", healthAfter, now);
            slot.restore();
        }
        if (attempted) {
            probeLog("外部授权表：全部候选表均未生效（已还原）");
        }
    }

    /** 按方案定位出「该实体的那个血量槽」；定位不到返回 {@code null} */
    private static Slot resolveSlot(LivingEntity victim, StaticAuthorityPlan plan) {
        Object singleton = resolveSingleton(plan.singletonField(), plan.singletonFactory());
        Object container = readFieldObject(plan.containerField(), singleton);
        if (container == null) return null;
        if (plan.kind() == ContainerKind.MAP) {
            if (!(container instanceof Map<?, ?> raw)) return null;
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) raw;
            Object key = plan.keyKind() == KeyKind.UUID ? victim.getUUID() : victim;
            Object entry;
            try {
                entry = map.get(key);
            } catch (Throwable ignored) {
                return null;
            }
            if (entry == null) return null;
            Object snapshot = plan.entryValueField() == null
                    ? entry : readFieldObject(plan.entryValueField(), entry);
            return new Slot(map, key, entry, plan.entryValueField(), snapshot);
        }
        Object element = findElement(container, plan.entryKeyField(), victim);
        if (element == null) return null;
        return new Slot(null, null, element, plan.entryValueField(),
                readFieldObject(plan.entryValueField(), element));
    }

    /** 在元素容器里找出「指向本实体」的那个元素 */
    private static Object findElement(Object container, Field keyField, LivingEntity victim) {
        Object uuid = victim.getUUID();
        if (container instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object element : iterable) {
                if (count++ >= MAX_ELEMENTS) return null;
                if (element != null && keyMatches(element, keyField, victim, uuid)) return element;
            }
            return null;
        }
        if (isObjectArray(container)) {
            Object[] array = (Object[]) container;
            int limit = Math.min(array.length, MAX_ELEMENTS);
            for (int i = 0; i < limit; i++) {
                Object element = array[i];
                if (element != null && keyMatches(element, keyField, victim, uuid)) return element;
            }
        }
        return null;
    }

    private static boolean keyMatches(Object element, Field keyField, LivingEntity victim, Object uuid) {
        Object raw = readFieldObject(keyField, element);
        return raw == victim || (uuid != null && uuid.equals(raw));
    }

    private static boolean isObjectArray(Object container) {
        Class<?> type = container.getClass();
        return type.isArray() && !type.getComponentType().isPrimitive();
    }

    private static boolean isElementContainer(Object container) {
        return container instanceof Iterable<?> || isObjectArray(container);
    }

    /** 「在满血退化锚点下建出的方案」的类集合（见 A 策略）。
     *  满血时任何等于上限的候选都会被误判成血量位点，锚点不再是满血时要丢弃重探一次；但不能在满血时
     *  干脆不缓存——对「getHealth() 恒等于上限」的假血量目标，那样会让每一击重跑整套候选发现并清掉负缓存。 */
    private static final Set<Class<?>> FULL_ANCHOR_PLANS = ConcurrentHashMap.newKeySet();

    /** 本层的类级方案缓存（含负缓存退避） */
    static List<StaticAuthorityPlan> plans(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<StaticAuthorityPlan> cached = PLAN_CACHE.get(type);
        // A：满血锚点下建出的方案，锚点一旦不再是「恰好满血」就丢弃并重探一次（只发生一次）
        if (cached != null && FULL_ANCHOR_PLANS.contains(type)
                && !fullHealthAnchor(victim, currentHealth(victim))) {
            PLAN_CACHE.remove(type, cached);
            FULL_ANCHOR_PLANS.remove(type);
            cached = null;
        }
        if (cached != null) return cached;
        if (STATIC_AUTHORITY_NEGATIVE.blocked(type)) return List.of();
        // 候选池未就绪（模组文件收割在跑）时的空结论只做短退避，收割完成后立刻允许重探
        boolean complete = seedHarvestReady(victim);
        List<StaticAuthorityPlan> found;
        try {
            found = discoverPlans(victim);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            markDiscoveryMiss(STATIC_AUTHORITY_NEGATIVE, type, complete);
            return List.of();
        }
        if (!found.isEmpty()) {
            PLAN_CACHE.put(type, found);
            if (fullHealthAnchor(victim, currentHealth(victim))) FULL_ANCHOR_PLANS.add(type);
            else FULL_ANCHOR_PLANS.remove(type);
            STATIC_AUTHORITY_NEGATIVE.clear(type);
        } else {
            markDiscoveryMiss(STATIC_AUTHORITY_NEGATIVE, type, complete);
        }
        return found;
    }

    static List<StaticAuthorityPlan> discoverPlans(LivingEntity victim) {
        // 锚点用真实血量：只有「已经等于当前血量」的条目才算命中
        float anchor = currentHealth(victim);
        if (Float.isNaN(anchor) || anchor <= 0.0F) return List.of();
        List<Class<?>> pool = new ArrayList<>();
        for (Class<?> clazz : healthCoreCandidates(victim).classes()) {
            if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            pool.add(clazz);
        }
        List<Class<?>> registry = registryClasses(victim);
        List<StaticAuthorityPlan> plans = new ArrayList<>();
        // 1) 常量池候选：静态容器 + 单例实例容器（单例访问器会调用静态工厂，只对这条可信候选开放）
        for (Class<?> clazz : pool) {
            if (plans.size() >= MAX_PLANS) break;
            probeStaticContainers(victim, clazz, anchor, plans);
            for (Object[] accessor : singletonAccessors(clazz)) {
                if (plans.size() >= MAX_PLANS) break;
                Field singletonField = (Field) accessor[0];
                Method singletonFactory = (Method) accessor[1];
                Object singleton = resolveSingleton(singletonField, singletonFactory);
                if (singleton == null) continue;
                for (Field containerField : containerFields(singleton.getClass(), Map.class)) {
                    StaticAuthorityPlan plan =
                            probeContainer(victim, singletonField, singletonFactory, containerField, anchor);
                    if (plan != null) plans.add(plan);
                    if (plans.size() >= MAX_PLANS) break;
                }
                for (Field containerField : containerFields(singleton.getClass(), Iterable.class)) {
                    StaticAuthorityPlan plan =
                            probeContainer(victim, singletonField, singletonFactory, containerField, anchor);
                    if (plan != null) plans.add(plan);
                    if (plans.size() >= MAX_PLANS) break;
                }
            }
        }
        // 2) 类注册表候选：只读静态容器，绝不调用任何工厂 / 方法，避免触发模组副作用
        for (Class<?> clazz : registry) {
            if (plans.size() >= MAX_PLANS) break;
            probeStaticContainers(victim, clazz, anchor, plans);
        }
        if (plans.isEmpty() && MAConfig.debugProbes) {
            diagnoseDiscoveryMiss(victim, anchor, pool.size(), registry.size());
        }
        return plans;
    }

    /** 探一个类的静态容器（Map / 元素集合），命中就加进方案 */
    private static void probeStaticContainers(LivingEntity victim, Class<?> clazz, float anchor,
                                              List<StaticAuthorityPlan> plans) {
        for (Field containerField : staticContainerFields(clazz)) {
            if (plans.size() >= MAX_PLANS) return;
            StaticAuthorityPlan plan = probeContainer(victim, null, null, containerField, anchor);
            if (plan != null) plans.add(plan);
        }
    }

    /** 类（含父类）上的静态容器字段（Map / Collection / 对象数组，已 setAccessible） */
    static List<Field> staticContainerFields(Class<?> clazz) {
        List<Field> found = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            for (Field field : declaredFieldsOf(current)) {
                if (found.size() >= MAX_STATIC_CONTAINERS) return found;
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                Class<?> type = field.getType();
                if (!Map.class.isAssignableFrom(type) && !Iterable.class.isAssignableFrom(type)
                        && !(type.isArray() && !type.getComponentType().isPrimitive())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                found.add(field);
            }
        }
        return found;
    }

    /** 只读探测：容器里是否存着「指向该实体、血量 ≈ 当前血量」的条目；命中才产出方案 */
    private static StaticAuthorityPlan probeContainer(LivingEntity victim, Field singletonField,
                                                      Method singletonFactory, Field containerField, float anchor) {
        Object singleton = resolveSingleton(singletonField, singletonFactory);
        Object container = readFieldObject(containerField, singleton);
        if (container == null) return null;
        if (container instanceof Map<?, ?> map) {
            for (KeyKind kind : KeyKind.values()) {
                Object key = kind == KeyKind.UUID ? victim.getUUID() : victim;
                Object value;
                try {
                    value = map.get(key);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value instanceof Number number) {
                    if (matches(number.floatValue(), anchor)) {
                        return new StaticAuthorityPlan(singletonField, singletonFactory, containerField,
                                ContainerKind.MAP, kind, null, null);
                    }
                    continue;
                }
                if (value == null) continue;
                Field valueField = findEntryNumberField(value, anchor);
                if (valueField != null) {
                    return new StaticAuthorityPlan(singletonField, singletonFactory, containerField,
                            ContainerKind.MAP, kind, null, valueField);
                }
            }
            return null;
        }
        if (!isElementContainer(container)) return null;
        return probeElements(victim, singletonField, singletonFactory, containerField, container, anchor);
    }

    /** 元素容器：在元素对象里同时找到「指向本实体的字段」与「≈ 当前血量的数值字段」 */
    private static StaticAuthorityPlan probeElements(LivingEntity victim, Field singletonField,
                                                     Method singletonFactory, Field containerField,
                                                     Object container, float anchor) {
        Object uuid = victim.getUUID();
        int visited = 0;
        for (Object element : elements(container)) {
            if (visited++ >= MAX_ELEMENTS) return null;
            if (element == null || skipDeepClass(element.getClass())) continue;
            Field keyField = null;
            Field valueField = null;
            KeyKind keyKind = KeyKind.ENTITY;
            int fields = 0;
            for (Class<?> current : hierarchy(element.getClass())) {
                for (Field field : declaredFieldsOf(current)) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                    if (fields++ >= MAX_ENTRY_FIELDS) break;
                    Class<?> type = field.getType();
                    boolean numeric = isWritableFieldType(type);
                    if (!numeric && !type.isAssignableFrom(victim.getClass())
                            && !victim.getClass().isAssignableFrom(type) && type != Object.class
                            && type != java.util.UUID.class) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    Object raw = readFieldObject(field, element);
                    if (raw == victim) {
                        keyField = field;
                        keyKind = KeyKind.ENTITY;
                    } else if (keyField == null && uuid.equals(raw)) {
                        keyField = field;
                        keyKind = KeyKind.UUID;
                    } else if (valueField == null && raw instanceof Number number
                            && matches(number.floatValue(), anchor)) {
                        valueField = field;
                    }
                }
                if (fields >= MAX_ENTRY_FIELDS) break;
            }
            if (keyField != null && valueField != null) {
                return new StaticAuthorityPlan(singletonField, singletonFactory, containerField,
                        ContainerKind.ELEMENTS, keyKind, keyField, valueField);
            }
        }
        return null;
    }

    private static Iterable<?> elements(Object container) {
        if (container instanceof Iterable<?> iterable) return iterable;
        Object[] array = (Object[]) container;
        return java.util.Arrays.asList(array);
    }

    /** 在「条目对象」的继承链里找数值等于当前血量的可写字段 */
    private static Field findEntryNumberField(Object entry, float anchor) {
        Class<?> type = entry.getClass();
        if (skipDeepClass(type)) return null;
        // record 是带校验和的不可变快照（各类 Authority Entry）。满血时它的 maxHealth 恰好等于锚点，
        // 会被误认成血量槽；写进去只会让对方自校验失效、回退读数变成上限。这类记录交给 MAC 权威层重建。
        if (type.isRecord()) return null;
        int visited = 0;
        for (Class<?> current : hierarchy(type)) {
            for (Field field : declaredFieldsOf(current)) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                if (!isWritableFieldType(field.getType())) continue;
                if (visited++ >= MAX_ENTRY_FIELDS) return null;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                Object raw = readFieldObject(field, entry);
                if (raw instanceof Number number && matches(number.floatValue(), anchor)) return field;
            }
        }
        return null;
    }

    /** 模组自己的「类注册表」：可达静态容器里持有的 Class 对象。
     *  把类体加密在自有容器文件里、运行时才 define 的工具类既不是 .class 资源、也不出现在实体常量池，
     *  按类名加载会被「资源不存在」跳过；注册表里已是活的 Class 对象，正好补上这个缺口。
     *  只收与受害实体同源的类（同一个类加载器，或同一个顶层包），不会把 JDK / 库类拉进来白跑，
     *  也不做任何 Class.forName——注册表里的类都已加载，按名加载只会带来副作用与开销。 */
    static List<Class<?>> registryClasses(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<Class<?>> cached = REGISTRY_CACHE.get(type);
        if (cached != null) return cached;
        Set<Class<?>> found = new LinkedHashSet<>();
        ClassLoader loader = type.getClassLoader();
        int containers = 0;
        for (Class<?> clazz : healthCoreCandidates(victim).classes()) {
            if (containers >= MAX_REGISTRY_CONTAINERS || found.size() >= MAX_REGISTRY_CLASSES) break;
            if (clazz == null || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            for (Field field : staticContainerFields(clazz)) {
                if (containers >= MAX_REGISTRY_CONTAINERS || found.size() >= MAX_REGISTRY_CLASSES) break;
                Object container = readFieldObject(field, null);
                if (container == null || container == victim) continue;
                containers++;
                harvestRegistry(container, found, type, loader);
            }
        }
        List<Class<?>> result = List.copyOf(found);
        REGISTRY_CACHE.put(type, result);
        return result;
    }

    private static void harvestRegistry(Object container, Set<Class<?>> found, Class<?> victimType,
                                        ClassLoader loader) {
        Iterable<?> values;
        if (container instanceof Map<?, ?> map) {
            values = map.values();
        } else if (isElementContainer(container)) {
            values = elements(container);
        } else {
            return;
        }
        int visited = 0;
        for (Object value : values) {
            if (visited++ >= MAX_REGISTRY_VALUES || found.size() >= MAX_REGISTRY_CLASSES) return;
            if (!(value instanceof Class<?> clazz) || isVanillaClass(clazz) || skipDeepClass(clazz)) continue;
            if (sameOrigin(clazz, victimType, loader)) found.add(clazz);
        }
    }

    /** 与受害实体同源：同一个类加载器，或同一个顶层包（运行时 define 的类可能挂在子加载器上） */
    private static boolean sameOrigin(Class<?> clazz, Class<?> victimType, ClassLoader loader) {
        if (clazz.getClassLoader() == loader) return true;
        String victimName = victimType.getName();
        int dot = victimName.indexOf('.');
        return dot > 0 && clazz.getName().startsWith(victimName.substring(0, dot + 1));
    }

    /** 诊断用：本层没探到方案时，列出候选规模与「类注册表里的容器」实况 */
    private static void diagnoseDiscoveryMiss(LivingEntity victim, float anchor, int poolCandidates,
                                             int registryCandidates) {
        probeLog("外部授权表诊断：锚点={} 常量池候选={} 注册表候选={}", anchor, poolCandidates, registryCandidates);
        int listed = 0;
        for (Class<?> clazz : registryClasses(victim)) {
            if (listed >= 16) return;
            for (Field field : staticContainerFields(clazz)) {
                if (listed >= 16) return;
                Object container = readFieldObject(field, null);
                if (container == null) continue;
                probeLog("外部授权表诊断容器：{}#{}({}) {}", clazz.getName(), field.getName(),
                        field.getType().getSimpleName(), describeContainer(container, victim));
                listed++;
            }
        }
    }
}

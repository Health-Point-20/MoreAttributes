package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.FieldHealthProbe.*;
import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/** 模组自身「读写口 / 击杀口」探针：血量写入口（读/写方法对，含 class 文件方法表与实参模板合成）、持有对象读写口（以同步血量为锚点配无门控读/写方法），以及死亡流程兜底（权威血量清零 + 调用模组击杀入口）。 */
final class ModWriterProbe {

    private static final Map<Class<?>, List<HealthWriter>> HEALTH_WRITER_CACHE = new ConcurrentHashMap<>();

    private static final int WRITER_MAX_CANDIDATES = 512;

    /** 写入口探针的可达类展开预算 */
    static final int WRITER_REFERENCE_MAX_DEPTH = 4;

    static final int WRITER_REFERENCE_MAX_CLASSES = 4096;

    /** 模组自己的血量写入口（调用入口 + 调用模板 + 数值位下标） */
    record HealthWriter(HealthCallable callable, Object[] template, int valueIndex) {
    }

    /** 写口拒收「非正目标」时，向上调整后能接受的最小正血量（按实体类缓存；失败不缓存） */
    private static final Map<Class<?>, Float> HEALTH_WRITE_FLOOR_CACHE = new ConcurrentHashMap<>();

    /** 写口永不接受任何正数时的退避缓存 */
    static final NegativeCache<Class<?>> WRITE_FLOOR_NEGATIVE =
            new NegativeCache<>("写口最小可写血量");

    /** 向上调整的起始探测值与翻倍次数上限 */
    private static final float FLOOR_PROBE_START = 1.0E-4F;

    private static final int FLOOR_PROBE_MAX_STEPS = 24;

    /**
     * 用模组自己的写入口把血量改成目标值，全部写完仍不到位则按原值还原。
     * @return 致死目标（<= 0）被写口拒收 0、只能停在最小正血量时返回 true；此时 getHealth() 非 0，死亡块仍按致死处理，见 {@link StrongTrueDamageHelper#applyStrongerTrueDamage}。
     */
    static boolean writeViaHealthWriter(LivingEntity victim, float healthAfter) {
        List<HealthWriter> writers = healthWriters(victim);
        if (writers.isEmpty()) return false;
        float before = healthOf(victim);
        for (HealthWriter writer : writers) {
            setViaWriter(writer, victim, healthAfter);
        }
        if (matches(healthOf(victim), healthAfter)) return false;
        // 致死目标（<= 0）被写口拒收：不预设下限，改为向上调整到写口肯接受的最小正血量并保留结果
        if (healthAfter <= 0.0F && before > 0.0F) {
            Float floor = writableFloor(writers, victim, before);
            if (floor != null && matches(healthOf(victim), floor)) return true;
        }
        if (!Float.isNaN(before)) {
            for (HealthWriter writer : writers) {
                setViaWriter(writer, victim, before);
            }
        }
        return false;
    }

    /** 从很小的正数起翻倍向上试探，返回写口第一个肯接受的血量；结果按实体类缓存 */
    static Float writableFloor(List<HealthWriter> writers, LivingEntity victim, float ceiling) {
        Class<?> type = victim.getClass();
        Float cached = HEALTH_WRITE_FLOOR_CACHE.get(type);
        if (cached != null) {
            for (HealthWriter writer : writers) {
                setViaWriter(writer, victim, cached);
            }
            return matches(healthOf(victim), cached) ? cached : null;
        }
        if (WRITE_FLOOR_NEGATIVE.blocked(type)) return null;
        float value = Math.min(FLOOR_PROBE_START, ceiling);
        for (int step = 0; step < FLOOR_PROBE_MAX_STEPS && value <= ceiling; step++) {
            for (HealthWriter writer : writers) {
                setViaWriter(writer, victim, value);
            }
            if (matches(healthOf(victim), value)) {
                HEALTH_WRITE_FLOOR_CACHE.put(type, value);
                WRITE_FLOOR_NEGATIVE.clear(type);
                return value;
            }
            value *= 2.0F;
        }
        // 写口永不接受任何正数：退避，避免每一次致死命中都重跑这 24 步试探
        WRITE_FLOOR_NEGATIVE.mark(type);
        return null;
    }

    static void setViaWriter(HealthWriter writer, LivingEntity victim, float value) {
        HealthCallable callable = writer.callable();
        invokeCallableQuietly(callable, fillArgs(callable, writer.template(), writer.valueIndex(), victim, value));
    }

    /** 「致死清零因写入口未缓存而跳过」只打一次（每实体类） */
    private static final Set<String> ZERO_SKIP_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 把该实体所有已验证的绝对血量写入口都写成 0。
     * 致死块只消费已缓存的写入口：写入口层没跑过时现场发现要 1.5~2 s，恰好落在击杀那一击，故未缓存即跳过清零（那种情形下权威值已被其他层写 0，清零只是兜底）。
     * 不能按 victim.isAlive()/isRemoved() 早停：这两个判据在目标模组里常被注入（yizheng 的 isAlive() 恒 false、Mobiaton 的 isRemoved() 查注入的 forceRemove），早停会在只写了第一个写入口后退出，权威存储反而没被清零；写入口都写 0，全部写一遍幂等且更可靠。
     */
    static void zeroAuthorityHealth(LivingEntity victim) {
        List<HealthWriter> writers = HEALTH_WRITER_CACHE.get(victim.getClass());
        if (writers == null) {
            if (oncePer(ZERO_SKIP_LOGGED, victim.getClass(), "致死清零跳过")) {
                probeLog("致死清零跳过（{}）：写入口未缓存，交由其他层保证权威值已归零",
                        victim.getClass().getName());
            }
            return;
        }
        for (HealthWriter writer : writers) {
            setViaWriter(writer, victim, 0.0F);
        }
    }

    private static final Map<Class<?>, Method> MOD_KILL_CACHE = new ConcurrentHashMap<>();

    private static final Set<Class<?>> NO_MOD_KILL = ConcurrentHashMap.newKeySet();

    /**
     * 血量已归零、但实体自认尚未死亡时，调模组自己的击杀入口兜底。
     * @param force 致死目标被写口拒收 0、血量停在最小正数时为 true，此时不因 getHealth() > 0 提前放弃。
     */
    static void forceModKillIfNeeded(LivingEntity victim, DamageSource source, boolean force) {
        if (victim.isRemoved() || victim.isDeadOrDying()) return;
        if (!force && healthOf(victim) > 0.0F) return;
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
    static Method modKillEntry(LivingEntity victim, DamageSource source) {
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
                // 这里有意不做 isRiskyName 过滤：本方法的语义就是找击杀口，
                // 名字像 kill/remove/die 的方法恰恰是目标，不要当成漏洞去修。
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
    static Object[] fillArgs(HealthCallable callable, Object[] template, int valueIndex,
                                     LivingEntity victim, double value) {
        Class<?>[] params = callable.parameterTypes();
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

    static List<HealthWriter> healthWriters(LivingEntity victim) {
        Class<?> type = victim.getClass();
        List<HealthWriter> cached = HEALTH_WRITER_CACHE.get(type);
        if (cached != null) return cached;
        // 探针要求「读方法返回值正好等于当前血量」「最大生命可读」，任一读不出来时的空结论是「这一刻探不了」
        // 而非「该类没有写入口」，按类缓存会把它永久固化（首个实例恰好濒死/被门控时）。
        float anchor = healthOf(victim);
        if (Float.isNaN(anchor) || !(anchor > 0.0F) || Float.isNaN(maxHealthOf(victim))) {
            return List.of();
        }
        // discoverHealthWriters 在「无可信读口 / 候选全被隔离」时返回不可变的 List.of()，直接 addAll 会抛
        // UnsupportedOperationException：其内外两次读数相互独立，门控/不稳定 getHealth 的目标可以不一致。
        List<HealthWriter> computed = new ArrayList<>(discoverHealthWriters(victim));
        // 「自定义容器」上的写口（不是静态工具的读写方法对，而是容器对象自己的 set/put 方法）
        boolean[] containerComplete = new boolean[1];
        computed.addAll(containerWriters(victim, containerComplete));
        if (!containerComplete[0]) {
            // 容器写口锚点那一刻不可用（实例濒死）/ 被异常中断时合并结果不完整，不得进入正缓存，否则
            // 「首个实例恰好濒死」会把空结论永久化；本次仍按现有结果返回（漏一次不影响正确性）。
            return computed;
        }
        List<HealthWriter> raced = HEALTH_WRITER_CACHE.putIfAbsent(type, computed);
        return raced != null ? raced : computed;
    }



    // 自定义容器写口（档 4）：血量存在模组自己的容器类里
    private static final Map<Class<?>, List<HealthWriter>> CONTAINER_WRITER_CACHE = new ConcurrentHashMap<>();

    /** 自定义容器写口「没探到」时的退避缓存 */
    static final NegativeCache<Class<?>> CONTAINER_WRITER_NEGATIVE =
            new NegativeCache<>("自定义容器写口");

    /** 一次最多检查多少个容器对象 / 每个容器最多试多少个方法 */
    private static final int CONTAINER_MAX_OBJECTS = 16;
    private static final int CONTAINER_MAX_METHODS = 24;

    /**
     * 自定义容器写口：血量存在模组自己的容器类里（例如 accelerator 的 {@code OpaqueStore<Integer, DamageBlob[]>}）时，既不是 java.util.Map、也没有「读方法返回值 == 当前血量」的读口，静态扫描发现不了。
     * 改从容器实例自己的方法入手：找「最后一个参数是数值、其余参数是实体的定位实参」的方法写入探测值，再用权威血量是否到位验证；命中即缓存（失败写回原值）。
     */
    static List<HealthWriter> containerWriters(LivingEntity victim) {
        return containerWriters(victim, new boolean[1]);
    }

    /**
     * @param complete 出参：本次结论是否「跑完」（false = 锚点不可用 / 异常中断，结论不完整）；调用方（{@link #healthWriters}）据此决定是否把合并结果放进正缓存。
     */
    static List<HealthWriter> containerWriters(LivingEntity victim, boolean[] complete) {
        complete[0] = true;
        Class<?> type = victim.getClass();
        List<HealthWriter> cached = CONTAINER_WRITER_CACHE.get(type);
        if (cached != null) return cached;
        if (CONTAINER_WRITER_NEGATIVE.blocked(type)) {
            // 处于退避窗口内 = 本次根本没探：结论同样不完整，不能让合并结果进正缓存
            complete[0] = false;
            return List.of();
        }
        // 锚点守卫必须在缓存之前短路：首个被观察实例恰好濒死（≤1.5 血）时的空结论是「这一刻探不了」，
        // 一旦被按类缓存，整个实体类就永久失去容器写口（只 mark，不入正缓存）。
        float anchor = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        if (Float.isNaN(anchor) || anchor <= 1.5F) {
            CONTAINER_WRITER_NEGATIVE.markShort(type);
            complete[0] = false;
            return List.of();
        }
        List<HealthWriter> found;
        try {
            found = new ArrayList<>(discoverContainerWriters(victim));
        } catch (Throwable t) {
            rethrowIfFatal(t);
            // 异常中断 = 结论不完整：只做短退避，绝不入正缓存
            CONTAINER_WRITER_NEGATIVE.markShort(type);
            complete[0] = false;
            return List.of();
        }
        if (!found.isEmpty()) {
            CONTAINER_WRITER_NEGATIVE.clear(type);
            if (MAConfig.debugProbes) {
                probeLog("自定义容器写口：{} 命中 {} 个", type.getName(), found.size());
            }
        }
        // 跑到这里才算「跑完的发现」：此时空结果是结构结论，可以按类缓存
        CONTAINER_WRITER_CACHE.put(type, List.copyOf(found));
        return CONTAINER_WRITER_CACHE.get(type);
    }

    private static List<HealthWriter> discoverContainerWriters(LivingEntity victim) {
        float before = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        if (Float.isNaN(before) || before <= 1.5F) return List.of();
        List<Object> containers = containerCandidates(victim);
        if (containers.isEmpty()) return List.of();
        float probe = Math.max(1.0F, before - 1.0F);
        List<HealthWriter> found = new ArrayList<>();
        for (Object container : containers) {
            if (found.size() >= 4) break;
            Method[] methods;
            Class<?> clazz = container.getClass();
            try {
                methods = declaredMethodsOf(clazz);
            } catch (Throwable ignored) {
                continue;
            }
            int inspected = 0;
            for (Method method : methods) {
                if (inspected++ >= CONTAINER_MAX_METHODS) break;
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params.length > 3) continue;
                int valueIndex = lastNumericIndex(params);
                if (valueIndex < 0 || !isNumericParam(params[valueIndex])) continue;
                boolean locatorsOk = true;
                for (int i = 0; i < params.length; i++) {
                    if (i == valueIndex) continue;
                    if (!isLocatorParam(params[i], victim)) {
                        locatorsOk = false;
                        break;
                    }
                }
                if (!locatorsOk) continue;
                if (cannotAccess(method)) continue;
                HealthWriter writer = new HealthWriter(new BoundCallable(container, method),
                        new Object[params.length], valueIndex);
                float anchorBefore = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
                try {
                    setViaWriter(writer, victim, probe);
                } catch (Throwable t) {
                    rethrowIfFatal(t);
                    continue;
                }
                float after = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
                if (!Float.isNaN(after) && matches(after, probe)) {
                    found.add(writer);
                    continue;
                }
                // 没生效：把容器里的值写回原值，避免留下探测痕迹
                if (!Float.isNaN(anchorBefore)) {
                    try {
                        setViaWriter(writer, victim, anchorBefore);
                    } catch (Throwable t) {
                        rethrowIfFatal(t);
                    }
                }
            }
        }
        return found;
    }

    /** 候选容器对象：实体可达类里的静态自定义容器（非 Map/Iterable/JDK 类型） */
    private static List<Object> containerCandidates(LivingEntity victim) {
        List<Object> out = new ArrayList<>();
        List<Class<?>> candidates = writerCandidateClasses(victim.getClass());
        int seen = 0;
        for (Class<?> clazz : candidates) {
            if (out.size() >= CONTAINER_MAX_OBJECTS || ++seen > WRITER_MAX_CANDIDATES) break;
            if (clazz == null || isVanillaClass(clazz) || isUnsafeToInvoke(clazz.getName())) continue;
            Field[] fields;
            try {
                fields = declaredFieldsOf(clazz);
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                Class<?> fieldType = field.getType();
                // 只收「模组自己的容器类」：JDK 容器（Map/List/Set/数组）交给既有各层
                if (fieldType.isPrimitive() || fieldType.isArray() || fieldType == String.class) continue;
                if (!isModClass(fieldType) || Map.class.isAssignableFrom(fieldType)
                        || Iterable.class.isAssignableFrom(fieldType)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null && !out.contains(value)) out.add(value);
                } catch (Throwable ignored) {
                    // 静态字段不可读则跳过
                }
                if (out.size() >= CONTAINER_MAX_OBJECTS) break;
            }
        }
        return out;
    }

    /** 定位实参：能按当前实体合成出值的参数类型 */
    private static boolean isLocatorParam(Class<?> param, LivingEntity victim) {
        return param.isInstance(victim) || param == int.class || param == Integer.class
                || param == long.class || param == Long.class || param == UUID.class
                || param == String.class || param == float.class || param == double.class;
    }

    private static boolean isNumericParam(Class<?> param) {
        return param == float.class || param == double.class || param == int.class || param == long.class
                || param == short.class || param == byte.class || param == Float.class
                || param == Double.class || param == Integer.class || param == Long.class
                || param == Short.class || param == Byte.class;
    }

    /** T11-A/B 内部回退开关：true = 字节码描述符预筛 + 方法级热点优先；false = 旧的全量反射枚举 */
    private static final boolean WRITER_BYTECODE_PREFILTER = true;

    /** 方法级热点签名：实体类自己的常量池里引用过的 owner.name:descriptor。实体真正调用过的读写口才是高概率的血量口，把 sortCandidatesByGate 的「类级优先」升级为「方法级优先」；池不可用（档位不用池情报）时返回空集，退化为原有顺序。 */
    private static Set<String> writerHotSignatures(Class<?> entityType) {
        if (!MAConfig.trueDamageMode.usesPoolIntel()) return Set.of();
        return PoolFingerprint.of(entityType).methodRefs();
    }

    /**
     * T11-A：把「描述符形态粗筛」前移到纯字节码方法表（{@link ReflectionToolkit#bytecodeStaticMethods}：只解析 class 文件、按类缓存、不加载被引用类），只对筛出来的少数方法取反射 Method 并做行为验证。
     * 反射枚举该类全部 public static 方法既是该层实测 1.55 s 成本的主因，又是 I-8 里 LinkageError 的高危面；字节码不可用 / 筛不出任何方法时回退到反射枚举，保证覆盖面不退化。形态：读候选 ()F / ()D；写候选 (...F)V / (...D)V / (...F)Z / (...D)Z。
     */
    private static List<Method> prefilteredStaticMethods(Class<?> clazz, Set<String> hotSignatures) {
        if (!WRITER_BYTECODE_PREFILTER) return publicStaticMethods(clazz);
        List<StaticMethodSig> sigs = bytecodeStaticMethods(clazz);
        if (sigs.isEmpty()) return publicStaticMethods(clazz);
        ClassLoader loader = clazz.getClassLoader();
        List<Method> selected = new ArrayList<>();
        Map<Method, String> descriptorOf = new IdentityHashMap<>();
        for (StaticMethodSig sig : sigs) {
            if (sig.name().startsWith("<")) continue;
            if (!isWriterShapeDescriptor(sig.descriptor())) continue;
            Class<?>[] params = parseParameterTypes(sig.descriptor(), loader);
            if (params == null) continue;
            Class<?> returns = parseReturnType(sig.descriptor(), loader);
            if (returns == null || returns == void.class) continue;
            try {
                Method method = clazz.getDeclaredMethod(sig.name(), params);
                int modifiers = method.getModifiers();
                if (method.isSynthetic() || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                        || Modifier.isNative(modifiers)) {
                    continue;
                }
                method.setAccessible(true);
                selected.add(method);
                descriptorOf.put(method, sig.descriptor());
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 该方法在运行期被 Mixin 移除 / 参数类型引用了缺失模组：只跳过它
            }
        }
        if (selected.isEmpty()) return publicStaticMethods(clazz);
        if (!hotSignatures.isEmpty()) {
            String owner = clazz.getName().replace('.', '/');
            selected.sort(Comparator.comparingInt(method -> hotSignatures.contains(
                    owner + "." + method.getName() + ":" + descriptorOf.get(method)) ? 0 : 1));
        }
        return selected;
    }

    /** 读写口候选的描述符形态（见 {@link #prefilteredStaticMethods}） */
    private static boolean isWriterShapeDescriptor(String descriptor) {
        if (descriptor == null) return false;
        int close = descriptor.indexOf(')');
        if (close < 0 || close + 1 >= descriptor.length()) return false;
        char returns = descriptor.charAt(close + 1);
        // 读候选：返回 float/double
        if (returns == 'F' || returns == 'D') return true;
        // 写候选：返回 void/boolean，且形参表里必须有一个 float/double
        if (returns != 'V' && returns != 'Z') return false;
        for (int i = 1; i < close; i++) {
            char c = descriptor.charAt(i);
            if (c == 'F' || c == 'D') return true;
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) return false;
                i = end;
            }
        }
        return false;
    }

    /** L0.3：按门控报告重排候选类（未命中门控签名者在前）；返回新列表，不改动缓存列表 */
    private static List<Class<?>> sortCandidatesByGate(List<Class<?>> candidates, Class<?> entityType) {
        if (candidates.size() < 2) return candidates;
        GateReport report = GateReport.forEntity(entityType);
        if (report == GateReport.EMPTY || report.gatedClasses().isEmpty()) return candidates;
        List<Class<?>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((Class<?> clazz) -> report.gatePredictedOn(clazz) ? 1 : 0));
        return sorted;
    }

    /** 识别该类实体的“血量写入口”：读方法与写方法都要过行为验证 */
    static List<HealthWriter> discoverHealthWriters(LivingEntity victim) {
        float healthBefore = healthOf(victim);
        if (Float.isNaN(healthBefore) || !(healthBefore > 0.0F)) return List.of();
        double delta = Math.max(1.0, Math.abs(healthBefore) * 0.01);
        List<HealthWriter> found = new ArrayList<>();
        int inspected = 0;
        // 把「未被门控签名命中」的候选类排在「疑似门控」类之前：干净入口更可能直接可用，首次命中更早成功，也减少对被门控写入口的无谓真实调用。
        List<Class<?>> candidates = sortCandidatesByGate(writerCandidateClasses(victim.getClass()), victim.getClass());
        // T11-B：实体自己引用过的方法签名优先（方法级热点）
        Set<String> hotSignatures = writerHotSignatures(victim.getClass());
        for (Class<?> clazz : candidates) {
            if (inspected == WRITER_MAX_CANDIDATES) break;
            // 原生/图形栈必须挡在反射调用之前
            if (isUnsafeToInvoke(clazz.getName())) continue;
            if (!isModClass(clazz) && isNotEnginePackageName(clazz.getName())) continue;
            // T11-A：字节码描述符预筛（回退到全量反射枚举）
            List<Method> methods = prefilteredStaticMethods(clazz, hotSignatures);
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
        //
        // 兜底：与「读方法参数表」形状不匹配的写口，按形参类型从取值池合成实参、标定目标血量位，由行为验证定夺；部分模组把成员登记进 JDK 反射黑名单，需先解除该过滤。
        //
        // 该激进兜底已硬关，probeSynthesizedWriters 实际不再运行：它会拿猜出来的实参真实调用候选类的任意静态方法（深度 4 / 4096 个类），对带自愈/反作弊的目标会让血量反而增加，也是首次命中卡顿的主因，不要重开。
        if (found.isEmpty() && MAConfig.trueDamageMode.allowsAggressiveDiscovery()) {
            probeSynthesizedWriters(candidates, victim, healthBefore, delta, found);
        }
        return found;
    }

    /** 合成写口探测的形参个数上限与单方法组合预算 */
    private static final int WRITER_MAX_PARAMS = 5;

    private static final int WRITER_MAX_SYNTH_COMBOS = 12;

    /**
     * 通用写口探针（实参合成 + 值位自标定），当前因激进兜底硬关而不运行：枚举候选类的公开静态 void/boolean 方法，把最后一个 float/double 位当目标血量位，其余形参按类型从取值池合成。
     * 取值池：定位位=实体/id/UUID、枚举=全部常量、辅助数值=最大生命/当前血量/0；命中以行为验证定夺（写入 probe 后 getHealth() 正好等于 probe，且最大生命不变）。只探测能证明自己持有该实体血量的类；成员被反射过滤时回落到 class 文件方法表 + MethodHandle。
     */
    static void probeSynthesizedWriters(List<Class<?>> candidates, LivingEntity victim, float healthBefore,
                                                double delta, List<HealthWriter> found) {
        float maxBefore = maxHealthOf(victim);
        if (Float.isNaN(maxBefore)) return;
        int inspected = 0;
        for (Class<?> clazz : candidates) {
            if (inspected >= WRITER_MAX_CANDIDATES) break;
            // 该形状较宽，只对模组类展开；引擎/原生栈不碰（可能触发 GL 等）
            if (!isModClass(clazz) || isUnsafeToInvoke(clazz.getName())) continue;
            // 解除该类的反射黑名单，否则写入口（如 preInit）根本不会出现在 getDeclaredMethods 里
            liftReflectiveFilters(clazz);
            // 优先反射方法；反射看不到任何方法时，用 class 文件方法表 + MethodHandle 兜底（G4）
            List<HealthCallable> callables = reflectCallables(clazz);
            if (callables.isEmpty()) callables = bytecodeStaticCallables(clazz);
            // 安全闸门：只探测「能证明自己持有该实体血量」的类，避免对无关工具类试写造成不可逆副作用（首击致目标消失的成因）。
            if (!hasVerifiedReader(callables, victim, healthBefore, maxBefore)) continue;
            for (HealthCallable writer : callables) {
                if (inspected >= WRITER_MAX_CANDIDATES) break;
                Class<?> returns = writer.returnType();
                if (returns != void.class && returns != boolean.class) continue;
                if (isRiskyName(writer.name())) continue;
                Class<?>[] params = writer.parameterTypes();
                if (params.length == 0 || params.length > WRITER_MAX_PARAMS) continue;
                if (lacksIdentityParam(params, victim)) continue;
                int valueIndex = lastNumericIndex(params);
                if (valueIndex < 0) continue;
                // 数值形参被擦除（Object/Number）时无法判断该装箱成什么：猜错的类型会被模组原样存进强类型
                // 槽位，之后强转必崩（iss_csw 与 jerotes 均因此崩过），故直接跳过。
                if (isErasedNumericParam(params[valueIndex])) continue;
                inspected++;
                for (Object[] template : synthesizeWriterTemplates(params, valueIndex, victim, maxBefore, healthBefore)) {
                    // 该循环最多 512 候选 × 12 模板：先判开关再组装日志实参
                    if (MAConfig.debugProbes) {
                        probeLog("柔性写口：试写 {}#{}（目标位={}）", clazz.getName(), writer.name(), valueIndex);
                    }
                    if (!probeWriter(writer, template, valueIndex, victim, delta)) continue;
                    if (matches(maxHealthOf(victim), maxBefore)) {
                        probeLog("柔性写口：命中 {}#{}", clazz.getName(), writer.name());
                        found.add(new HealthWriter(writer, template, valueIndex));
                        return;
                    }
                    // 该写法把最大生命一起改了：弃用，并把血量与最大生命都还原到探测前
                    restoreWriterState(writer, template, valueIndex, victim, healthBefore, maxBefore);
                }
            }
        }
    }

    /** 反射可见的公开静态方法 → 可调用入口 */
    static List<HealthCallable> reflectCallables(Class<?> clazz) {
        List<HealthCallable> result = new ArrayList<>();
        for (Method method : publicStaticMethods(clazz)) {
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // 公开方法通常无需 setAccessible，失败也照常尝试
            }
            result.add(new ReflectCallable(method));
        }
        return result;
    }

    /** 安全闸门：类里是否存在「用合成实参调用后正好返回当前血量」的静态读口 */
    static boolean hasVerifiedReader(List<HealthCallable> callables, LivingEntity victim,
                                             float healthBefore, float maxBefore) {
        for (HealthCallable reader : callables) {
            Class<?> returns = reader.returnType();
            if (returns != float.class && returns != double.class) continue;
            Class<?>[] params = reader.parameterTypes();
            if (params.length > WRITER_MAX_PARAMS || lacksIdentityParam(params, victim)) continue;
            // valueIndex = -1：读口没有「目标值位」，所有形参都从取值池合成
            for (Object[] template : synthesizeWriterTemplates(params, -1, victim, maxBefore, healthBefore)) {
                Double value = invokeCallableForNumber(reader, fillArgs(reader, template, -1, victim, 0.0));
                if (value != null && matches(value.floatValue(), healthBefore)) {
                    if (MAConfig.debugProbes) {
                        probeLog("柔性写口：读口 {} = {}", reader.name(), value);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 副作用风险较高的方法名：不作为写口探针目标（防止误调 kill/remove 之类导致目标消失） */
    static boolean isRiskyName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String bad : new String[]{"kill", "die", "death", "remove", "discard", "despawn", "delete",
                "suicide", "banish", "destroy"}) {
            if (lower.contains(bad)) return true;
        }
        return false;
    }

    /** 把 class 文件里的 public static 方法解析成 MethodHandle 入口；解析失败/超预算则跳过 */
    @SuppressWarnings("CatchMayIgnoreException")
    static List<HealthCallable> bytecodeStaticCallables(Class<?> clazz) {
        List<HealthCallable> result = new ArrayList<>();
        MethodHandles.Lookup lookup = implLookup();
        if (lookup == null) return result;
        ClassLoader loader = clazz.getClassLoader();
        for (StaticMethodSig sig : bytecodeStaticMethods(clazz)) {
            if (sig.name().startsWith("<")) continue;
            Class<?>[] params = parseParameterTypes(sig.descriptor(), loader);
            if (params == null || params.length > WRITER_MAX_PARAMS) continue;
            Class<?> returns = parseReturnType(sig.descriptor(), loader);
            if (returns == null) continue;
            try {
                MethodHandle handle = lookup.findStatic(clazz, sig.name(), MethodType.methodType(returns, params));
                result.add(new HandleCallable(sig.name(), handle, params, returns));
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }
        return result;
    }

    /** 最后一个 float/double 形参下标；没有则 -1（约定：目标血量位是最后一个数值位） */
    private static int lastNumericIndex(Class<?>[] params) {
        for (int i = params.length - 1; i >= 0; i--) {
            if (params[i] == float.class || params[i] == double.class) return i;
        }
        return -1;
    }

    /** 合成调用模板：目标位留空，其余位按取值池枚举取笛卡尔积；超出组合预算即截断 */
    private static List<Object[]> synthesizeWriterTemplates(Class<?>[] params, int valueIndex, LivingEntity victim,
                                                            float maxBefore, float healthBefore) {
        List<Object[]> templates = new ArrayList<>();
        templates.add(new Object[params.length]);
        for (int i = 0; i < params.length; i++) {
            if (i == valueIndex) continue;
            List<Object> choices = parameterChoices(params[i], victim, maxBefore, healthBefore);
            if (choices.isEmpty()) return List.of();
            List<Object[]> next = new ArrayList<>();
            for (Object[] template : templates) {
                for (Object choice : choices) {
                    Object[] copy = template.clone();
                    copy[i] = choice;
                    next.add(copy);
                    if (next.size() >= WRITER_MAX_SYNTH_COMBOS) break;
                }
                if (next.size() == WRITER_MAX_SYNTH_COMBOS) break;
            }
            templates = next;
        }
        return templates;
    }

    /** 单个形参的取值候选：定位位→null（调用时按实体补），枚举→全部常量，辅助数值位→最大生命/当前血量/0；其余类型无法安全合成，该方案放弃。 */
    static List<Object> parameterChoices(Class<?> param, LivingEntity victim, float maxBefore, float healthBefore) {
        if (isIdentityParam(param, victim)) return Collections.singletonList(null);
        if (param.isEnum()) {
            Object[] constants;
            try {
                constants = param.getEnumConstants();
            } catch (Throwable ignored) {
                return List.of();
            }
            if (constants == null || constants.length == 0 || constants.length > 8) return List.of();
            return new ArrayList<>(Arrays.asList(constants));
        }
        if (param == float.class || param == double.class) {
            List<Object> values = new ArrayList<>(3);
            values.add(boxNumber(param, maxBefore));
            if (!matches(healthBefore, maxBefore)) values.add(boxNumber(param, healthBefore));
            values.add(boxNumber(param, 0.0));
            return values;
        }
        return List.of();
    }

    /** 形参是否可按实体定位（实体本身 / 实体 id / 实体 UUID） */
    private static boolean isIdentityParam(Class<?> param, LivingEntity victim) {
        return param == int.class || param == Integer.class || param == UUID.class || param.isInstance(victim);
    }

    /** 形参表里是否没有任何一个「按实体定位」的形参（实体本身 / 实体 id / 实体 UUID） */
    private static boolean lacksIdentityParam(Class<?>[] params, LivingEntity victim) {
        for (Class<?> param : params) {
            if (isIdentityParam(param, victim)) return false;
        }
        return true;
    }

    /** 为已通过读验证的读方法逐个试写方法：任一试成即记入 found 并返回 true */
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
            HealthCallable callable = new ReflectCallable(candidate);
            if (probeWriter(callable, template, valueIndex, victim, delta)) {
                found.add(new HealthWriter(callable, template, valueIndex));
                return true;
            }
        }
        return false;
    }

    /** 读方法取值验证：以固定参数调用它，返回值必须正好等于该实体当前血量 */
    static boolean readsVictimHealth(Method reader, Object[] template, int valueIndex,
                                             LivingEntity victim, float healthBefore) {
        HealthCallable callable = new ReflectCallable(reader);
        Double value = invokeCallableForNumber(callable, fillArgs(callable, template, valueIndex, victim, 0.0));
        return value != null && matches(value.floatValue(), healthBefore);
    }

    /** 写方法行为验证：写入「当前血量 ± delta」，看当前血量是否正好随之变化 */
    static boolean probeWriter(HealthCallable writer, Object[] template, int valueIndex,
                                       LivingEntity victim, double delta) {
        float healthBefore = healthOf(victim);
        float maxBefore = maxHealthOf(victim);
        if (Float.isNaN(healthBefore) || Float.isNaN(maxBefore)) return false;
        double down = healthBefore - delta;
        // 向下探（不越界）；血量不足一个 delta 时才退回向上探
        double[] probes = down > 0.0 ? new double[]{down, healthBefore + delta} : new double[]{healthBefore + delta};
        for (double probe : probes) {
            if (!invokeCallableQuietly(writer, fillArgs(writer, template, valueIndex, victim, probe))) {
                // 写入抛异常也可能是「已经写了一半」：失败路径同样要尝试还原
                restoreWriterState(writer, template, valueIndex, victim, healthBefore, maxBefore);
                return false;
            }
            float healthNow = healthOf(victim);
            float maxNow = maxHealthOf(victim);
            if (matches(healthNow, (float) probe)) {
                restoreWriterState(writer, template, valueIndex, victim, healthBefore, maxBefore);
                return true;
            }
            // 没命中也要还原
            restoreWriterState(writer, template, valueIndex, victim, healthBefore, maxBefore);
            // 观察到的是最大血量在动，换下一个常量再试
            if (matches(maxNow, (float) (maxBefore + (probe - healthBefore)))) return false;
        }
        return false;
    }

    /**
     * 把写口探测期间被改动的血量还原回探针前的快照。
     * 只还原血量，绝不写最大生命：这类写口的数值形参就是当前血量，把 maxBefore 写进去等于把实体直接回满血（真伤打上去血量反而增加）；最大生命被改动时只记日志，那种写口随后会被行为验证否掉。
     */
    static void restoreWriterState(HealthCallable writer, Object[] template, int valueIndex,
                                           LivingEntity victim, float healthBefore, float maxBefore) {
        if (!Float.isNaN(healthBefore) && !matches(healthOf(victim), healthBefore)) {
            invokeCallableQuietly(writer, fillArgs(writer, template, valueIndex, victim, healthBefore));
        }
        if (!Float.isNaN(maxBefore) && !matches(maxHealthOf(victim), maxBefore)) {
            probeLog("写口探测改动了最大生命，按「不做向上写入」策略不再还原：{}", writer.name());
        }
    }

    /** 写方法与读方法的参数表对应关系 → 目标血量应填的参数下标，形状不匹配返回 -1 */
    private static int writerValueIndex(Method reader, int readerValue, Method writer) {
        Class<?>[] readerParams = reader.getParameterTypes();
        Class<?>[] writerParams = writer.getParameterTypes();
        int index;
        if (writerParams.length == readerParams.length) {
            index = readerValue >= 0 && sameParamTypes(readerParams, writerParams) ? readerValue : -1;
        } else if (readerValue >= 0 || writerParams.length != readerParams.length + 1) {
            return -1;
        } else {
            Class<?> last = writerParams[writerParams.length - 1];
            if (last != float.class && last != double.class) return -1;
            for (int i = 0; i < readerParams.length; i++) {
                if (readerParams[i] != writerParams[i]) return -1;
            }
            index = writerParams.length - 1;
        }
        if (index < 0) return -1;
        // 数值形参被擦除（Object/Number/Comparable/Serializable）的写口一律拒绝：只能靠猜装箱类型，猜错
        // （Float/Double 交给内部存 Integer 的槽位）会让模组之后强转抛 ClassCastException，最坏连存档一起崩。
        return isErasedNumericParam(writerParams[index]) ? -1 : index;
    }

    /** 该形参是否「擦除到无法判断具体数值类型」（Object/Number/Comparable/Serializable） */
    static boolean isErasedNumericParam(Class<?> param) {
        return param == Object.class || param == Number.class || param == Comparable.class
                || param == java.io.Serializable.class;
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

    private static final int ARG_IDENTITY = 0;

    /** 实参取自实体自身的同类型字段（如实体持有的密钥表） */
    private static final int ARG_ENTITY_FIELD = 1;

    /** 实参取自持有对象自身的同类型字段 */
    private static final int ARG_HOLDER_FIELD = 2;

    /** 持有对象上的「无门控血量读口」。path 从实体出发逐跳解析持有对象：元素为 {@link Field}（实例字段）或 {@link Integer}（数组下标，用于 Forge capability 提供者数组这类血量挂载点）。 */
    record HolderReader(List<Object> path, Method method, int[] kinds, Field[] sources) {
    }

    /** 持有对象上的“无门控血量写口”（单个 float/double 形参） */
    record HolderWriter(List<Object> path, Method method) {
    }

    record HolderPlan(HolderReader reader, HolderWriter writer) {
    }

    /** 持有对象读写口：按实体类缓存（只存类级数据） */
    private static final Map<Class<?>, List<HolderPlan>> HOLDER_PLAN_CACHE = new ConcurrentHashMap<>();

    private static final int HOLDER_MAX_DEPTH = 4;

    private static final int HOLDER_MAX_NODES = 256;

    private static final int HOLDER_MAX_READERS = 8;

    private static final int HOLDER_MAX_ARG_SPECS = 8;

    static final int HOLDER_MAX_READER_PARAMS = 2;

    /** 每类实体最多保留几个「读口 + 写口」方案（供逐个尝试，避免被首个受限写口挡住） */
    private static final int HOLDER_MAX_PLANS = 4;

    /** 门控实体或仍未写到位时，就走持有对象读写口 */
    static boolean needsHolderWrite(boolean gatedGetter, LivingEntity victim, float healthAfter) {
        return gatedGetter || stillAboveTarget(victim, healthAfter);
    }

    /** 用实体可达对象上的无门控写口把血量改成目标值；返回是否有方案命中（门控实体靠它判成败） */
    static boolean writeViaHolderWriter(LivingEntity victim, float healthAfter, float damage) {
        for (HolderPlan plan : holderPlans(victim)) {
            Object holder = resolveHolder(victim, plan.writer().path());
            if (holder == null) continue;
            float before = readViaHolderReader(victim, plan.reader());
            Class<?> readerType = plan.reader().method().getReturnType();
            // 持有对象的血量刻度可能与结算基线不同（亚波伦 getHealth()=646 而镜像=320），故按「它自己的当前值 − 本次真伤」写，绝不直接写结算算出的绝对值。
            float target = !Float.isNaN(before) && damage > 0.0F
                    ? Math.max(0.0F, before - damage) : healthAfter;
            if (!invokeInstanceWithNumber(plan.writer().method(), holder, target, readerType)) continue;
            float now = readViaHolderReader(victim, plan.reader());
            if (!Float.isNaN(now) && matches(now, target)) {
                probeLog("持有对象层：写入命中 {}#{}", holder.getClass().getName(), plan.writer().method().getName());
                return true;
            }
            // 没写到位就按读口读到的原值还原
            if (!Float.isNaN(before)) invokeInstanceWithNumber(plan.writer().method(), holder, before, readerType);
        }
        return false;
    }

    /**
     * 持有对象层读到的「模组自有血量」（首次探测完成后可用，读不到返回 NaN）。用于把结算基线与模组自己的刻度对齐。
     * 亚波伦的 getHealth() 恒等于最大血量（666），原版镜像只有 320（陈旧），真实血量在 MegaCapability 的 FloatWrapped 里（646）；按镜像取基线会把写入的绝对值从 646 一刀砍到 220（本次真伤只有 100）。
     */
    static float holderHealth(LivingEntity victim) {
        List<HolderPlan> plans = HOLDER_PLAN_CACHE.get(victim.getClass());
        if (plans == null || plans.isEmpty()) return Float.NaN;
        return readViaHolderReader(victim, plans.get(0).reader());
    }

    /** 该类实体的持有对象读写口（首次触发才探测）；锚点无效时不缓存 */
    /** A：在「满血退化锚点」下建出的方案（锚点一旦离开满血就丢弃重探一次） */
    private static final Set<Class<?>> HOLDER_FULL_ANCHOR_PLANS = ConcurrentHashMap.newKeySet();

    static List<HolderPlan> holderPlans(LivingEntity victim) {
        Class<?> holderType = victim.getClass();
        List<HolderPlan> cached = HOLDER_PLAN_CACHE.get(holderType);
        if (cached != null && HOLDER_FULL_ANCHOR_PLANS.contains(holderType)
                && !fullHealthAnchor(victim, currentHealth(victim))) {
            HOLDER_PLAN_CACHE.remove(holderType, cached);
            HOLDER_FULL_ANCHOR_PLANS.remove(holderType);
            cached = null;
        }
        if (cached != null) return cached;
        float anchor = currentHealth(victim);   // 锚点 = 权威血量（假血量/门控时为原版镜像）
        float getterAnchor = healthOf(victim);  // getHealth() 原值：假血量实体的真值也可能在这里
        if (Float.isNaN(anchor) || !(anchor > 0.0F)) {
            // 锚点无效：这是「这一刻探不了」，直接返回且不入缓存（与 healthWriters 的样板一致）
            if (Float.isNaN(getterAnchor) || !(getterAnchor > 0.0F)) return List.of();
            anchor = getterAnchor;
        }
        List<Object[]> holders = reachableHolders(victim);
        List<HolderPlan> found = new ArrayList<>();
        // 读方法可能不止一个吻合，逐个配写方法并全部保留：实体自身的 setHealth 常被单次上限/无敌帧削
        // （探针的小量能过、真伤大量过不了），需保留后续方案（如 capability 上的 setData）兜底。
        List<HolderReader> readers = new ArrayList<>(findHolderReaders(victim, holders, anchor));
        // 假血量实体：镜像与 getHealth() 都可能配到读口，两个锚点都试一遍（取并集，不丢已有方案）
        if (!Float.isNaN(getterAnchor) && getterAnchor > 0.0F && !matches(getterAnchor, anchor)) {
            readers.addAll(findHolderReaders(victim, holders, getterAnchor));
        }
        for (HolderReader reader : readers) {
            HolderWriter writer = findHolderWriter(victim, holders, reader, anchor);
            if (writer == null && !matches(getterAnchor, anchor)) {
                writer = findHolderWriter(victim, holders, reader, getterAnchor);
            }
            if (writer != null) {
                found.add(new HolderPlan(reader, writer));
                if (found.size() >= HOLDER_MAX_PLANS) break;
            }
        }
        HOLDER_PLAN_CACHE.put(victim.getClass(), found);
        if (fullHealthAnchor(victim, anchor)) HOLDER_FULL_ANCHOR_PLANS.add(victim.getClass());
        else HOLDER_FULL_ANCHOR_PLANS.remove(victim.getClass());
        if (MAConfig.debugProbes) {
            probeLog("持有对象层：{} 锚点={}（getHealth={}）方案数={}",
                    victim.getClass().getName(), anchor, getterAnchor, found.size());
        }
        return found;
    }

    /** 实体可达的持有对象（含自身）：实例引用字段 + Forge capability 提供者数组 */
    static List<Object[]> reachableHolders(LivingEntity victim) {
        List<Object[]> holders = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{victim, List.of(), 0});
        visited.add(victim);
        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < HOLDER_MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            @SuppressWarnings("unchecked")
            List<Object> path = (List<Object>) entry[1];
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
                if (value == null || value instanceof Entity && value != victim) continue;
                // Forge capability 提供者数组：按下标展开（血量常挂在 capability 实现对象本身上）
                if (value.getClass().isArray()) {
                    enqueueProviderArray(queue, visited, path, field, value, depth);
                    continue;
                }
                if (!isHolderNode(value)) continue;
                if (!visited.add(value)) continue;
                List<Object> childPath = new ArrayList<>(path);
                childPath.add(field);
                queue.add(new Object[]{value, childPath, depth + 1});
            }
        }
        if (MAConfig.debugProbes) {
            probeLog("持有对象层：实体={} 可达持有对象={} 个", victim.getClass().getName(), holders.size());
        }
        return holders;
    }

    /** 若数组元素类型是 ICapabilityProvider，把每个元素作为一个持有对象入队（路径含数组下标） */
    static void enqueueProviderArray(Deque<Object[]> queue, Set<Object> visited, List<Object> path,
                                             Field field, Object array, int depth) {
        Class<?> component = array.getClass().getComponentType();
        if (component == null || component.isPrimitive()
                || !ICapabilityProvider.class.isAssignableFrom(component)) return;
        int length;
        try {
            length = java.lang.reflect.Array.getLength(array);
        } catch (Throwable ignored) {
            return;
        }
        if (MAConfig.debugProbes) {
            probeLog("持有对象层：展开 capability 数组字段 {}#{}（{} 个元素）", field.getDeclaringClass().getSimpleName(),
                    field.getName(), length);
        }
        for (int i = 0; i < length; i++) {
            Object element;
            try {
                element = java.lang.reflect.Array.get(array, i);
            } catch (Throwable ignored) {
                continue;
            }
            if (element == null || !visited.add(element)) continue;
            if (MAConfig.debugProbes) {
                probeLog("持有对象层：capability[{}] = {}", i, element.getClass().getName());
            }
            List<Object> childPath = new ArrayList<>(path);
            childPath.add(field);
            childPath.add(i);
            queue.add(new Object[]{element, childPath, depth + 1});
        }
    }

    /** 值得当作“血量保管者”展开的对象：排除字符串/数值/数组/集合/大对象 */
    static boolean isHolderNode(Object value) {
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return false;
        if (value.getClass().isArray() || value instanceof Iterable<?> || value instanceof Map<?, ?>) return false;
        if (value instanceof Class<?> || value instanceof ClassLoader) return false;
        return !skipDeepClass(value.getClass());
    }

    /** 找出读值正好等于锚点的无门控读方法 */
    static List<HolderReader> findHolderReaders(LivingEntity victim, List<Object[]> holders, float anchor) {
        List<HolderReader> found = new ArrayList<>();
        for (Object[] entry : holders) {
            Object holder = entry[1];
            @SuppressWarnings("unchecked")
            List<Object> path = (List<Object>) entry[0];
            for (Method method : numericReturningMethods(holder.getClass())) {
                for (Object[] spec : argSpecs(method, victim, holder)) {
                    HolderReader reader = new HolderReader(path, method, (int[]) spec[0], (Field[]) spec[1]);
                    Object[] args = resolveArgs(reader, victim, holder);
                    if (args == null) continue;
                    Double value = invokeForNumber(method, holder, args);
                    if (value != null && matches(value.floatValue(), anchor)) {
                        probeLog("持有对象层：读口 {}#{} = {}", holder.getClass().getName(), method.getName(), value);
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
    static HolderWriter findHolderWriter(LivingEntity victim, List<Object[]> holders,
                                                 HolderReader reader, float anchor) {
        double delta = Math.max(1.0, Math.abs(anchor) * 0.01);
        double down = anchor - delta;
        double[] probes = down > 0.0 ? new double[]{down, anchor + delta} : new double[]{anchor + delta};
        for (Object[] entry : holders) {
            Object holder = entry[1];
            @SuppressWarnings("unchecked")
            List<Object> path = (List<Object>) entry[0];
            for (Method method : singleNumberSetters(holder.getClass())) {
                if (isRiskyName(method.getName())) continue;
                for (double probe : probes) {
                    if (!invokeInstanceWithNumber(method, holder, probe)) break;
                    float observed = readViaHolderReader(victim, reader);
                    if (!Float.isNaN(observed) && matches(observed, (float) probe)) {
                        probeLog("持有对象层：写口 {}#{}", holder.getClass().getName(), method.getName());
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
    static List<Object[]> argChoices(Class<?> param, LivingEntity victim, Object holder) {
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
    static float readViaHolderReader(LivingEntity victim, HolderReader reader) {
        Object holder = resolveHolder(victim, reader.path());
        if (holder == null) return Float.NaN;
        Object[] args = resolveArgs(reader, victim, holder);
        if (args == null) return Float.NaN;
        Double value = invokeForNumber(reader.method(), holder, args);
        return value == null ? Float.NaN : value.floatValue();
    }

    /** 按字段路径/数组下标从实体解析出持有对象 */
    private static Object resolveHolder(LivingEntity victim, List<Object> path) {
        Object node = victim;
        for (Object step : path) {
            if (node == null) return null;
            try {
                if (step instanceof Integer index) {
                    node = java.lang.reflect.Array.get(node, index);
                } else {
                    node = ((Field) step).get(node);
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return node;
    }
}

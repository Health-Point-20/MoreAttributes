package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.truedamage.ModWriterProbe.*;
import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/**
 * 隐藏状态帧层（档 3，级联最后一层）。
 * 先按结构指纹定位帧类型并标定字段语义，整体写入并回读验证；
 * 指纹未命中时改用行为发现：喂参考帧反推读写中继，再造帧写入。
 */
final class StateFrameLayer {

    /** 标定造帧入口用的哨兵基数（只要求互不相等） */
    private static final int STATE_SENTINEL_INT_BASE = 1_000_003;

    private static final long STATE_SENTINEL_LONG_BASE = 1_000_000_007L;

    private static final String STATE_SENTINEL_STRING = "ma$sentinel$string";

    /** 标定「值位 → 观察血量」映射时的采样步长与次数 */
    private static final int STATE_VALUE_PROBE_STEPS = 6;

    private static final int STATE_TRANSITION_PROBE_MAX = 15;

    /** 单次结算里最多尝试几种「造帧方案」（转换码 × 次序，已确认过的组合优先） */
    private static final int STATE_FRAME_MAX_ATTEMPTS = 72;

    /** 状态帧机制的形状句柄，按实体类缓存；空结果不缓存 */
    private static final Map<Class<?>, StateFrameHandle> STATE_FRAME_CACHE = new ConcurrentHashMap<>();

    static final NegativeCache<Class<?>> STATE_FRAME_NEGATIVE =
            new NegativeCache<>("状态帧结构发现");

    /** 已确认可用的「转换类型 + 次序」组合，按实体类缓存 */
    private static final Map<Class<?>, int[]> STATE_TRANSITION_CACHE = new ConcurrentHashMap<>();

    /** 辅助方法形参 / 帧内位点的语义（标定产物） */
    enum FrameSemantic {
        ENTITY, VALUE, PREVIOUS, TRANSITION, STRING, VIEW, ISSUED,
        /** 仅用于帧内位点：模拟签名 / 死亡许可 / 毫微秒 */
        SIMULATION_STAMP, DEATH_PERMIT
    }

    /** 帧里的一个位点：array 非空时指该 int[] 字段的第 index 格，否则指字段本身。 */
    record FrameSlot(Field array, int index, Field owner, Field field) {
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

        /** 把数值写回该位点（仅供 G5 行为发现层使用） */
        @SuppressWarnings("CatchMayIgnoreException")
        void write(Object frame, Object value) {
            try {
                if (array != null) {
                    if (array.get(frame) instanceof int[] values && index >= 0 && index < values.length
                            && value instanceof Number number) {
                        values[index] = number.intValue();
                    }
                    return;
                }
                if (field == null || !(value instanceof Number number)) return;
                Object target = owner == null ? frame : owner.get(frame);
                if (target == null) return;
                Class<?> type = field.getType();
                if (type == int.class) field.setInt(target, number.intValue());
                else if (type == long.class) field.setLong(target, number.longValue());
                else if (type == float.class) field.setFloat(target, number.floatValue());
                else if (type == double.class) field.setDouble(target, number.doubleValue());
                else if (type == short.class) field.setShort(target, number.shortValue());
                else if (type == byte.class) field.setByte(target, number.byteValue());
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }
    }

    record FrameFieldRef(FrameSlot slot, Class<?> type, Object value) {
    }

    record CalibratedCall(Method method, FrameSemantic[] slots) {
    }

    /** 一次「造帧 + 写回」所需的全部取值（按 {@link FrameSemantic} 取用） */
    record FrameValues(Object entity, Object value, Object previous, Object transition,
                               Object issued, String string, Object view) {
    }

    /** 「帧值位 ↔ 观察血量」的仿射映射：观察血量 = scale × 帧值 + offset。 */
    record ValueMap(double scale, double offset) {
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
     * 状态帧机制的形状句柄；形参顺序与位点语义均来自现场标定。
     * read/writes/verifies 为中继读口、写口、校验口，encodeView 等为已标定实参语义的辅助调用；createSlots
     * 为造帧形参对应的帧内位点，slots 为语义 → 位点表，ambiguous 为语义未定的时间线位点，valueMap 为帧值位 ↔ 观察血量。
     */
    record StateFrameHandle(Class<?> frameType, Method create, FrameSlot[] createSlots,
                                    List<FrameSlot> equalSlots, Map<FrameSemantic, FrameSlot> slots,
                                    List<FrameSlot> ambiguous, Method read, List<Method> writes,
                                    List<Method> verifies, CalibratedCall encodeView, CalibratedCall encodeString,
                                    CalibratedCall stampSim, CalibratedCall stampPermit, ValueMap valueMap) {
    }

    /** 「状态帧机制未发现」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> STATE_FRAME_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    /** 用实体的「权威状态帧」把血写到位（只在扣血时使用；写不到就还原） */
    static void writeViaStateFrame(LivingEntity victim, float healthAfter) {
        StateFrameHandle handle = stateFrameHandle(victim);
        if (handle == null) {
            // 结构指纹未命中：改用行为发现（见 G5）。G5 对候选类的所有 (实体, 帧) 双参静态方法以活帧
            // 真调用且没有快照可还原，属激进兜底，只随完全体（档 4）启用，没有单独的配置开关。
            if (MAConfig.trueDamageMode.allowsAggressiveDiscovery() && writeViaBehavioralFrame(victim, healthAfter)) return;
            if (oncePer(STATE_FRAME_MISS_LOGGED, victim.getClass(), "状态帧机制未发现")) {
                probeLog("状态帧直写：未发现状态帧机制（{}）", victim.getClass().getName());
            }
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
        // 写入成功必须保留（那是本层的目的），其余任何出口都必须退回参考帧
        boolean committed = false;
        try {
            for (int[] plan : attemptPlans(victim.getClass(), ambiguousValues(handle, reference))) {
                // 预算判定：方案上限恰等于 STATE_FRAME_MAX_ATTEMPTS，原先「第 73 次才触发」的守卫是死代码
                if (attempts++ >= STATE_FRAME_MAX_ATTEMPTS) break;
                int transition = plan[0];
                int order = plan[1];
                Object frame = buildStateFrame(handle, victim, wantBits, previousBits, transition, order);
                if (frame == null) continue;
                if (writeAndVerify(handle, victim, frame, healthAfter)) {
                    committed = true;
                    STATE_TRANSITION_CACHE.put(victim.getClass(), plan);
                    probeLog("状态帧直写：{} 血量 {} => {}（转换={} 次序={}）",
                            victim.getClass().getName(), current, healthAfter, transition, order);
                    return;
                }
                // 每一次失败都退回参考帧，避免留下半改状态或让它读到非法帧
                restoreStateFrame(handle, victim, reference);
            }
        } finally {
            if (!committed) restoreStateFrame(handle, victim, reference);
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

    /** 用已标定的「语义 → 位点」表造一帧；order 为两个待定位点与实参语义的次序组合。 */
    static Object buildStateFrame(StateFrameHandle handle, LivingEntity victim, int wantBits,
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
    static Object invokeCalibrated(CalibratedCall call, FrameValues values) {
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
    static boolean writeAndVerify(StateFrameHandle handle, LivingEntity victim,
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

    static void restoreStateFrame(StateFrameHandle handle, LivingEntity victim, Object reference) {
        if (reference == null) return;
        for (Method write : handle.writes()) {
            invokeObject(write, null, new Object[]{victim, reference});
        }
    }

    // 不预设帧类 / 时间线 / 工厂 / 签名，纯行为地找静态读口 read(实体)->帧与幂等中继写口 write(实体,帧)，
    // 再变异参考帧的 int 数值位（原值或取反，与目标位同值位点一起改）写回，达标看 getHealth()；带签名帧由结构指纹层负责。

    private static final int G5_MAX_INT_SLOTS = 64;

    private static final int G5_MAX_WRITES = 8;

    /** G5 命中方案（按帧类缓存；中继写口 + 参考帧里的第几个 int 位 + 位模式种类） */
    record BehavioralFramePlan(Method read, List<Method> writes, int slotOrdinal, int encodingKind) {
    }

    private static final Map<Class<?>, BehavioralFramePlan> G5_PLAN_CACHE = new ConcurrentHashMap<>();

    /** G5 行为发现落空的退避缓存；失败可能只是时机未到（例如帧要等实体进入某阶段），故仍会周期性重试。 */
    static final NegativeCache<Class<?>> G5_NEGATIVE = new NegativeCache<>("状态帧行为发现");

    static boolean writeViaBehavioralFrame(LivingEntity victim, float healthAfter) {
        float current = healthOf(victim);
        if (Float.isNaN(current) || healthAfter >= current) return false;
        Class<?> type = victim.getClass();
        BehavioralFramePlan plan = G5_PLAN_CACHE.get(type);
        if (plan != null) return applyBehavioralPlan(victim, healthAfter, plan, true);
        if (G5_NEGATIVE.blocked(type)) return false;
        plan = discoverBehavioralPlan(victim, healthAfter);
        if (plan == null) {
            G5_NEGATIVE.mark(type);
            return false;
        }
        G5_PLAN_CACHE.put(type, plan);
        G5_NEGATIVE.clear(type);
        return applyBehavioralPlan(victim, healthAfter, plan, true);
    }

    /** 行为发现：读口 + 幂等中继写口 + 参考帧 int 位 + 编码方式，逐个试探到 getHealth() 达标 */
    static BehavioralFramePlan discoverBehavioralPlan(LivingEntity victim, float healthAfter) {
        for (Class<?> clazz : behavioralFrameClasses(victim)) {
            if (clazz == null || !isModClass(clazz) || isUnsafeToInvoke(clazz.getName())) continue;
            liftReflectiveFilters(clazz);
            for (Method read : declaredStaticMethods(clazz)) {
                Class<?>[] params = read.getParameterTypes();
                if (params.length != 1 || !params[0].isInstance(victim)) continue;
                if (cannotAccess(read)) continue;
                Object reference = invokeObject(read, null, new Object[]{victim});
                if (reference == null || !isModClass(reference.getClass())) continue;
                List<Method> writes = idempotentRelays(clazz, reference, victim);
                if (writes.isEmpty()) continue;
                int slotCount = intSlotsOf(reference).size();
                for (int ordinal = 0; ordinal < slotCount; ordinal++) {
                    for (int kind = 0; kind < 2; kind++) {
                        BehavioralFramePlan plan = new BehavioralFramePlan(read, writes, ordinal, kind);
                        if (applyBehavioralPlan(victim, healthAfter, plan, false)) {
                            probeLog("状态帧直写(G5)：命中 {}#{} 位[{}] 编码={}（帧类={}）",
                                    clazz.getName(), read.getName(), ordinal, kind, reference.getClass().getName());
                            return plan;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 试写：commit=false 时无论成败都还原（发现用）；commit=true 时成功即保留（应用用） */
    static boolean applyBehavioralPlan(LivingEntity victim, float healthAfter,
                                               BehavioralFramePlan plan, boolean commit) {
        Object reference = invokeObject(plan.read(), null, new Object[]{victim});
        if (reference == null) return false;
        List<FrameFieldRef> intSlots = intSlotsOf(reference);
        if (plan.slotOrdinal() >= intSlots.size()) return false;
        int original = intOf(intSlots.get(plan.slotOrdinal()).value(), 0);
        float wantedValue = plan.encodingKind() == 0 ? healthAfter : -healthAfter;
        if (wantedValue == 0.0F) wantedValue = Float.intBitsToFloat(0);   // 归一化 −0.0，避免死亡位模式不符
        int wanted = Float.floatToRawIntBits(wantedValue);
        // 与目标位同值的位点一起改（校验要求「当前值 == 模拟值」，只改一格会造出自相矛盾的帧）；
        // 写入成功时这些同值位点必须保留，所以不能用「只还原非目标位」的写法。
        List<FrameFieldRef> changed = new ArrayList<>();
        boolean committed = false;
        try {
            for (FrameFieldRef ref : intSlots) {
                if (intOf(ref.value(), Integer.MIN_VALUE) == original) {
                    ref.slot().write(reference, wanted);
                    changed.add(ref);
                }
            }
            relayWrite(plan.writes(), victim, reference);
            boolean ok = matches(healthOf(victim), healthAfter);
            if (ok && commit) {
                committed = true;
                return true;
            }
            return ok;
        } finally {
            // 非提交路径（发现模式、或没写到位、或抛异常）一律还原，避免留下半改状态
            if (!committed) {
                restoreSlots(changed, reference, original);
                relayWrite(plan.writes(), victim, reference);
            }
        }
    }

    private static void restoreSlots(List<FrameFieldRef> slots, Object reference, int value) {
        for (FrameFieldRef ref : slots) {
            ref.slot().write(reference, value);
        }
    }

    static void relayWrite(List<Method> writes, LivingEntity victim, Object frame) {
        for (Method write : writes) {
            invokeObject(write, null, new Object[]{victim, frame});
        }
    }

    /** 行为发现层的候选类：实体常量池可达的嵌套类 + 它们的外层类（中继/管理器多半是外层） */
    static List<Class<?>> behavioralFrameClasses(LivingEntity victim) {
        List<Class<?>> result = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> nested : referencedNestedClasses(victim.getClass())) {
            if (nested == null) continue;
            if (seen.add(nested)) result.add(nested);
            for (Class<?> outer = nested.getEnclosingClass(); outer != null && outer != Object.class;
                 outer = outer.getEnclosingClass()) {
                if (seen.add(outer)) result.add(outer);
            }
        }
        return result;
    }

    /** 静态中继写口：形如 (实体, 帧对象) 的 void/boolean 方法，写回参考帧后血量不变（幂等） */
    static List<Method> idempotentRelays(Class<?> manager, Object reference, LivingEntity victim) {
        List<Method> result = new ArrayList<>();
        for (Method method : declaredStaticMethods(manager)) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2) continue;
            if (!params[0].isInstance(victim) || !params[1].isInstance(reference)) continue;
            Class<?> returns = method.getReturnType();
            if (returns != void.class && returns != boolean.class) continue;
            if (isRiskyName(method.getName())) continue;
            if (cannotAccess(method)) continue;
            float before = healthOf(victim);
            invokeObject(method, null, new Object[]{victim, reference});
            if (!matches(healthOf(victim), before)) continue;
            result.add(method);
            if (result.size() >= G5_MAX_WRITES) break;
        }
        return result;
    }

    static List<FrameFieldRef> intSlotsOf(Object frame) {
        List<FrameFieldRef> result = new ArrayList<>();
        for (Field field : declaredFieldsOf(frame.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || cannotAccess(field)) continue;
            if (field.getType() == int.class) {
                result.add(new FrameFieldRef(FrameSlot.of(field), int.class, readFieldQuietly(field, frame)));
            } else if (field.getType() == int[].class && readFieldQuietly(field, frame) instanceof int[] values) {
                for (int i = 0; i < values.length && result.size() < G5_MAX_INT_SLOTS; i++) {
                    result.add(new FrameFieldRef(FrameSlot.of(field, i), int.class, values[i]));
                }
            }
            if (result.size() >= G5_MAX_INT_SLOTS) break;
        }
        return result;
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static StateFrameHandle stateFrameHandle(LivingEntity victim) {
        Class<?> type = victim.getClass();
        StateFrameHandle cached = STATE_FRAME_CACHE.get(type);
        if (cached != null) return cached;
        if (STATE_FRAME_NEGATIVE.blocked(type)) return null;
        StateFrameHandle found = discoverStateFrameHandle(victim);
        if (found != null) {
            STATE_FRAME_CACHE.put(type, found);
            STATE_FRAME_NEGATIVE.clear(type);
        } else {
            // 负结果退避：结构发现 + 现场标定要在数百个嵌套类上做反射与真调用造帧
            STATE_FRAME_NEGATIVE.mark(type);
        }
        return found;
    }

    /** 提前发现入口：与级联共用缓存与负缓存路径，只做结构发现和造帧标定，不写血。 */
    static void preDiscoverStateFrame(LivingEntity victim) {
        stateFrameHandle(victim);
    }

    /** 按结构（嵌套帧类 + int[] 时间线）发现状态帧机制，并现场标定各形参与帧内位点的语义 */
    static StateFrameHandle discoverStateFrameHandle(LivingEntity victim) {
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
    static Method frameFactory(Class<?> frameType) {
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
    static FrameSlot[] calibrateFactorySlots(Method create, Class<?> frameType, Field timeline) {
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
    static StateFrameHandle calibrateAgainstReference(LivingEntity victim, Class<?> frameType,
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
            int[] intParams = intParamIndices(params, entityParam);
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
                        FrameSemantic[] layout = intLayout(params.length, entityParam, intParams, strParam,
                                tagParam, new int[]{a, b, c}, tl, valueBits);
                        // 语义表有洞即标定失败：否则每次命中都白跑 72 次造帧且永不写入组合缓存
                        if (layout == null) return null;
                        stampSim = new CalibratedCall(method, layout);
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
            int[] intParams = intParamIndices(params, entityParam);
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
        // 语义表有洞（例如两个 int 形参都等于值位、没人拿到 TRANSITION）即判定标定失败：
        // 否则每次造帧返回 null，仍白跑满 72 次，且 STATE_TRANSITION_CACHE 永不写入。
        for (FrameSemantic slot : slots) {
            if (slot == null) return null;
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

    /** 找「值位 → 显示位」纯函数：静态 (实体,int) -> int，且对时间线里某个值正好返回当前血量的位。 */
    static Method displayMap(LivingEntity victim, Class<?> manager, int[] timeline, float health) {
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
    static ValueMap fitValueMap(LivingEntity victim, Method display, int valueBits, float health) {
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
    static List<FrameFieldRef> frameFieldRefs(Object frame) {
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
    static boolean isModOwnedClass(Class<?> type) {
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
                && !name.startsWith("sun.") && !isVanillaClass(type);
    }

    static FrameSlot locateTimelineValue(Field timeline, Object frame, int wanted) {
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
    static List<FrameFieldRef> longFields(List<FrameFieldRef> refs) {
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

    /** 形参表里全部 {@code int} 位的下标（跳过 {@link #paramAccepting} 选出的实体位） */
    private static int[] intParamIndices(Class<?>[] params, int skip) {
        int count = 0;
        for (int i = 0; i < params.length; i++) {
            if (i != skip && params[i] == int.class) count++;
        }
        int[] found = new int[count];
        int cursor = 0;
        for (int i = 0; i < params.length; i++) {
            if (i != skip && params[i] == int.class) found[cursor++] = i;
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
            declared = declaredFieldsOf(frameType);
        } catch (Throwable ignored) {
            return null;
        }
        for (Field field : declared) {
            if (field.getType() != int[].class || Modifier.isStatic(field.getModifiers())) continue;
            return field;
        }
        return null;
    }
}

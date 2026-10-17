package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.MoreAttributesMod;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.PrintWriter;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;

/**
 * 真伤各层共用的运行时底座与「血量位点」抽象。
 * 这里提供探针日志（debugProbes 打开时写入 logs/more_attributes-probe.log）、服务端 tick 时钟、
 * 「探测失败」的两段式退避缓存、致命错误重抛、锁外缓存计算、数值一致性判定、
 * 血量读取（含原版同步血量镜像）、类归属判定与数值装箱。
 * {@link HealthSlot}、{@link StringSlot} 及字段/数组/同步数据等位点实现也在此，
 * 各层共用同一套「读数 / 写值」接口做行为验证与写入。
 */
final class TrueDamageRuntime {

    /** 负结果首次退避时长（tick）：只短暂退避，运行时注入的仓库才能被尽快发现 */
    private static final long NEGATIVE_FIRST_RETRY_TICKS = 20L;

    /** 负结果的长退避时长（tick）：连续多次没探到时使用 */
    private static final long NEGATIVE_BACKOFF_TICKS = 200L;

    /** 长退避之前允许的快速重探次数 */
    private static final int NEGATIVE_FAST_RETRIES = 3;

    /** 指数退避的封顶（tick）：5 分钟。再长的退避对「运行期注入」毫无意义，只会丢掉发现机会 */
    private static final long NEGATIVE_BACKOFF_MAX_TICKS = 6000L;

    /** 服务端 tick 计数（负结果退避的时基）。只在服务端线程递增，但会被预热线程/模组文件收割线程读取，
     * 故用 AtomicLong：volatile long 的自增是「读-改-写」复合操作，既不原子也会让检查工具报警。 */
    static final AtomicLong SERVER_TICK = new AtomicLong();

    /** 由本模组真伤/斩杀导致、正在 die() 调用栈内的目标。下沉到运行时底座，保持
     * 「编排 → 层 → 底座」的依赖方向：写口层 ModWriterProbe 要直接读写它。 */
    static final Set<LivingEntity> PENDING_FORCED_DEATH = ConcurrentHashMap.newKeySet();

    /**
     * 「探测失败」的退避缓存：到期自动放行一次重探，因此不会永久错过运行时才注入的血量仓库，
     * 也不会让打不穿的实体每击重跑整套发现（常为数十万次反射）。
     * 前 NEGATIVE_FAST_RETRIES 次失败只退避 NEGATIVE_FIRST_RETRY_TICKS，之后按 200 × 2^(marks-4)
     * 指数增长并封顶 NEGATIVE_BACKOFF_MAX_TICKS：固定时长会让同一次结算里失败的多层在同一 tick
     * 一起重探，形成周期性雷群。到期 tick 再加由 key 哈希决定的 ±20% 确定性抖动（可复现）打散。
     */
    static final class NegativeCache<K> {
        private final String name;
        /** 键 → {允许重探的 tick, 已退避次数} */
        private final Map<K, long[]> state = new ConcurrentHashMap<>();

        NegativeCache(String name) {
            this.name = name;
        }

        /** 是否仍在退避窗口内；窗口已过则顺手清掉条目 */
        boolean blocked(K key) {
            long[] entry = state.get(key);
            if (entry == null) return false;
            if (SERVER_TICK.get() < entry[0]) return true;
            state.remove(key, entry);
            return false;
        }

        void mark(K key) {
            mark(key, NEGATIVE_BACKOFF_TICKS);
        }

        /** 只做首段时长的一次短退避：用于「本次结论本身不完整」的场合（如模组文件收割未完成），
         * 既抑制同一秒内反复重跑整套发现，又能在收割完成后马上重探。 */
        void markShort(K key) {
            mark(key, NEGATIVE_FIRST_RETRY_TICKS);
        }

        /** 永久不退避重探：结论由类结构决定、进程内不会再变时使用。首次发现可能很贵（扫数百个模组类、
         * 每个过一次 ClassLoader.getResource，可达数秒），而 mark 的固定退避会让它反复重跑且永远
         * 得不到不同结论：结论可信就别再算。 */
        void markPermanent(K key) {
            long[] entry = state.get(key);
            long marks = entry == null ? 1L : entry[1] + 1L;
            state.put(key, new long[]{Long.MAX_VALUE, marks});
            probeLog("负结果永久缓存：{} 命中 {}（第 {} 次，本进程内不再重探）", name, key, marks);
        }

        private void mark(K key, long delay) {
            long[] entry = state.get(key);
            long marks = entry == null ? 1L : entry[1] + 1L;
            long base;
            if (delay != NEGATIVE_BACKOFF_TICKS) {
                // markShort：始终用首段时长，不进入指数曲线
                base = delay;
            } else if (marks <= NEGATIVE_FAST_RETRIES) {
                base = NEGATIVE_FIRST_RETRY_TICKS;
            } else {
                // 长退避：200 × 2^(marks-4)，封顶 5 分钟
                int shift = (int) Math.min(marks - NEGATIVE_FAST_RETRIES - 1L, 30L);
                base = shift >= 5 ? NEGATIVE_BACKOFF_MAX_TICKS
                        : Math.min(NEGATIVE_BACKOFF_MAX_TICKS, NEGATIVE_BACKOFF_TICKS << shift);
            }
            long actual = jitter(base, key);
            state.put(key, new long[]{SERVER_TICK.get() + actual, marks});
            probeLog("负结果退避：{} 命中 {}（第 {} 次，{} tick 内不重探）", name, key, marks, actual);
        }

        /** ±20% 确定性抖动：同一 key 的偏移固定（可复现），不同 key 互相错开，
         * 把同一次结算里失败的多层到期 tick 打散。 */
        private long jitter(long base, K key) {
            if (base <= 0L) return base;
            int spread = (int) Math.max(1L, base / 5L);
            int offset = Math.floorMod(key == null ? 0 : key.hashCode(), spread * 2 + 1) - spread;
            return Math.max(1L, base + offset);
        }

        void clear(K key) {
            state.remove(key);
        }
    }

    /** 当前真实血量：getHealth() 被门控（恒为 0）时回退到原版同步血量镜像，否则以 getHealth() 为准。 */
    static float currentHealth(LivingEntity victim) {
        return authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
    }

    /**
     * 权威血量：决定命中前血量、写入目标与是否已到位。
     * 优先级：getHealth() 可信 → 用它；被门控（≤0 而镜像 >0）→ 用镜像；都读不出 → 返回原读数。
     * 只保留「门控」这一条硬证据：绝不用「getHealth() 不跟随镜像」推断假血量——那只能说明它不读镜像，
     * 而 omni 铁傀儡这类实体的 getHealth() 就是真实血量。该推断按类缓存后会让 currentHealth() 返回
     * 镜像的 0/NaN，导致每击都被判为「不值得结算」。
     */
    static float authoritativeHealth(LivingEntity victim, float getter, float mirrored) {
        if (Float.isNaN(getter)) return mirrored;
        if (getter <= 0.0F && mirrored > 0.0F) return mirrored;
        return getter;
    }

    /**
     * 「假血量」判定：该实体类的 getHealth() 是否不跟原版同步血量（DATA_HEALTH_ID）走。
     * 部分模组把 getHealth() 写成恒定假值，此类实体上行为探针失去判据、stillAboveTarget 永远为真，
     * 血被写成 0 也进不了死亡流程。
     * 判定（按类缓存）：把同步镜像扰动 1 点再读 getHealth()，跟上说明走原版通道，随后立即还原镜像。
     * 镜像 ≤2 点时不扰动（怕把只剩 1 点血的实体探死），该情形不写缓存、按「可信」处理。
     */
    private static final Map<Class<?>, Boolean> HEALTH_GETTER_DETACHED = new ConcurrentHashMap<>();

    static boolean healthGetterDetached(LivingEntity victim, float getter, float mirrored) {
        if (Float.isNaN(getter) || Float.isNaN(mirrored)) return false;
        if (victim.level().isClientSide) return false;
        Class<?> type = victim.getClass();
        Boolean cached = HEALTH_GETTER_DETACHED.get(type);
        if (cached != null) return cached;
        // 读数与镜像一致时无需扰动：扰动会写实体真实血量，对带完整性校验的模组是可被观测的写入；结论也不缓存。
        if (matches(getter, mirrored)) return false;
        if (mirrored <= 2.0F) return false;
        float probe = mirrored - 1.0F;
        writeVanillaHealthData(victim, probe);
        float after = healthOf(victim);
        writeVanillaHealthData(victim, mirrored);      // 立即还原
        boolean detached = !Float.isNaN(after) && !matches(after, probe);
        HEALTH_GETTER_DETACHED.put(type, detached);
        if (detached) {
            probeLog("getHealth() 脱离原版同步血量（假血量）：{}（getHealth={} 镜像={}）",
                    type.getName(), getter, mirrored);
        }
        return detached;
    }

    /** 上一层写入后血量仍高于目标值——只有此时才值得继续下一层兜底 */
    static boolean stillAboveTarget(LivingEntity victim, float healthAfter) {
        float now = healthOf(victim);
        // 读不出来时按「未到位」处理：宁可多做一层兜底，也不要把「读不到」误判成「已写好」而漏写。
        if (Float.isNaN(now)) return true;
        return now > healthAfter && !matches(now, healthAfter);
    }

    /** 同上，但可选按原版同步镜像判定（getHealth() 是假血量时镜像才是真值）；读不出则退回 getHealth()。 */
    static boolean stillAboveTarget(LivingEntity victim, float healthAfter, boolean byMirror) {
        if (byMirror) {
            float mirrored = readVanillaHealthData(victim);
            if (!Float.isNaN(mirrored)) return mirrored > healthAfter && !matches(mirrored, healthAfter);
        }
        return stillAboveTarget(victim, healthAfter);
    }

    /** 字段名是否像血量：整型/装箱数值字段只有名字像血量时才值得做行为探针 */
    private static final java.util.regex.Pattern HEALTH_LIKE_NAME =
            java.util.regex.Pattern.compile("(?i).*(health|hp|life|heal|vital|血量|生命).*");

    static boolean isHealthLikeName(String name) {
        return name != null && HEALTH_LIKE_NAME.matcher(name).matches();
    }

    /** 该字段类型是否是可承载“血量数值”的类型（浮点/整型/装箱，含 String 之外的数值包装） */
    static boolean isHealthCapableType(Class<?> type) {
        return type == float.class || type == double.class || type == int.class || type == long.class
                || type == short.class || type == byte.class || type == Float.class || type == Double.class
                || type == Integer.class || type == Long.class || type == Short.class || type == Byte.class;
    }

    /** 该字段声明类型是否是可安全写入的「具体数值类型」。Object/Number/Comparable 等擦除类型一律 false：
     * 按它们装箱只能靠猜，猜成 Float/Double 会写坏模组 BYTE/INT 的强类型槽位。 */
    static boolean isWritableFieldType(Class<?> type) {
        return isHealthCapableType(type);
    }

    /**
     * 不可写位点：读恒 NaN、写无操作。
     * B：record 的实例字段绝不作为可写位点。record 不可变、字段 final，反射写必然失败；且模组常把
     * 「值 + 校验和」一起塞进 record（如 GreatSageHealthManager$Entry 带 encryptedHealth/mac），
     * 改任一字段都会让对方自校验失效，而它失败时的回退读数往往是上限本身（getHealth() 直接返回
     * maxHealth，假装满血）。这类记录应由 MAC 权威层走「按规范构造器整体重建 + 重算校验」，通用字段位点不碰。
     */
    static final HealthSlot INERT_SLOT = new HealthSlot() {
        @Override
        public double read() {
            return Double.NaN;
        }

        @Override
        public void write(double value) {
            // 有意为空：不可写位点
        }
    };

    /** 该字段是否是「record 的实例字段」（不可写快照，见 {@link #INERT_SLOT}） */
    static boolean isRecordInstanceField(Field field) {
        return field != null && !Modifier.isStatic(field.getModifiers())
                && field.getDeclaringClass().isRecord();
    }

    /** 按字段类型选位点实现：浮点走 VarHandle 优化的 {@link NumberFieldSlot}，其余数值走通用装箱位点 */
    static HealthSlot fieldSlot(Object owner, Field field) {
        // B：record 的实例字段返回惰性位点，读不到也写不进，探针会判为 miss 而跳过
        if (isRecordInstanceField(field)) return INERT_SLOT;
        Class<?> type = field.getType();
        if (type == float.class || type == double.class) return new NumberFieldSlot(owner, field);
        return new NumberValueFieldSlot(owner, field);
    }

    /**
     * 满血退化锚点：anchor 恰好等于实体上限时，任何等于上限的候选（maxHealth 字段、getMaxHealth()、
     * 满血派生读数）都会被当成血量位点。
     * A：满血锚点下方案可以当场使用，但不进类级正缓存，否则一次满血误判会被永久固化成该实体类的方案
     * （GreatSageHealthManager$Entry.maxHealth 曾与实体上限 600 相同而被缓存）。
     * 刻意不采用「满血就整层跳过」：getHealth() 由权威累计值派生的目标满血时正需出手，
     * 跳过会丢掉击杀路径（违反「首击当次生效」）。
     */
    static boolean fullHealthAnchor(LivingEntity victim, double anchor) {
        if (Double.isNaN(anchor)) return false;
        float max = maxHealthOf(victim);
        return !Float.isNaN(max) && max > 0.0F && matches((float) anchor, max);
    }

    /** 位点当前承载值的运行时类型（读不出来返回 null）。用于写入口的数值形参被擦除时按真实类型装箱：
     * 把 Double 交给内部存 Integer/Float 的槽位，模组之后强转会抛 ClassCastException。 */
    static Class<?> slotValueType(HealthSlot slot) {
        try {
            if (slot instanceof SyncedNumberSlot synced) {
                Object value = synced.entityData().get(synced.accessor());
                return value.getClass();
            }
            if (slot instanceof NumberValueFieldSlot field) return field.field().getType();
            if (slot instanceof NumberFieldSlot field) return field.field().getType();
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        return null;
    }

    /** 数值一致性判定：小值用固定容差，大值按 1e-6 相对缩放 */
    static boolean matches(float actual, float expected) {
        return Math.abs(actual - expected) <= Math.max(PROBE_EPSILON, Math.abs(expected) * 1.0E-6F);
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
    static void writeVanillaHealthData(LivingEntity victim, float healthAfter) {
        EntityDataAccessor<Float> accessor = getVanillaHealthAccessor();
        if (accessor == null) return;
        try {
            float clamped = Math.max(0.0F, healthAfter);
            // 最大生命为 NaN / 非正时不参与上钳，否则会把 NaN 或 0 写进同步血量污染实体
            float max = victim.getMaxHealth();
            if (Float.isFinite(max) && max > 0.0F && clamped > max) {
                clamped = max;
            }
            victim.getEntityData().set(accessor, clamped);
        } catch (Throwable ignored) {
            // 忽略写入失败
        }
    }

    /** 读原版同步血量数据（{@code DATA_HEALTH_ID}） */
    static float readVanillaHealthData(LivingEntity victim) {
        EntityDataAccessor<Float> accessor = getVanillaHealthAccessor();
        if (accessor == null) return Float.NaN;
        try {
            return victim.getEntityData().get(accessor);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /** 一个“可读写血量”的位点 */
    interface HealthSlot {
        double read();

        void write(double value);
    }

    /** 是否为模组自定义类（排除 JDK / 原版 / Forge / 常见依赖） */
    static boolean isModClass(Class<?> clazz) {
        return isModClassName(clazz.getName());
    }

    /** 同上，按类名判断（供尚未加载的引用类使用） */
    static boolean isModClassName(String name) {
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                || name.startsWith("com.mojang.") || name.startsWith("org.lwjgl.")
                || name.startsWith("it.unimi.") || name.startsWith("com.google.")
                || name.startsWith("org.slf4j") || name.startsWith("org.apache"));
    }

    /** 原版 / Forge 类：字段级探针一律跳过 */
    static boolean isVanillaClass(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.");
    }

    /** 跳过 JDK / 大型世界对象 */
    static boolean skipDeepClass(Class<?> clazz) {
        if (clazz == Class.class || clazz == String.class || clazz == ClassLoader.class || clazz == Thread.class) return true;
        String name = clazz.getName();
        // 同步数据传输结构不是血量存储：展开会把 DataItem.value（声明 Object、运行时 Byte/Integer）
        // 误当血量位点并写坏同步项类型。
        if (name.startsWith("net.minecraft.network.syncher")) return true;
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.google.") || name.startsWith("it.unimi.")
                || name.startsWith("org.slf4j") || name.startsWith("org.apache") || name.startsWith("com.mojang.")
                || name.startsWith("net.minecraft.world.level.") || name.startsWith("net.minecraft.server.")
                || name.startsWith("net.minecraft.client.") || name.startsWith("net.minecraft.world.entity.ai.");
    }

    /** OOM / StackOverflow 等致命错误不应被安静失败吞掉 */
    static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError error) throw error;
    }

    /** 与 {@link Map#computeIfAbsent} 等价，但把昂贵计算放到锁外（先读、未命中才算、再 putIfAbsent 收敛），
     * 避免占着 ConcurrentHashMap 的 bin 锁做反射/字节码解析/行为探针而卡住攻击路径。
     * 仅在服务端单线程调用；映射函数不得返回 null。 */
    static <K, V> V computeOutsideLock(Map<K, V> cache, K key, Supplier<V> compute) {
        V cached = cache.get(key);
        if (cached != null) return cached;
        V computed = compute.get();
        V raced = cache.putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }

    /** float/double 实例字段的统一读写位点 */
    @SuppressWarnings("CatchMayIgnoreException")
    record NumberFieldSlot(Object owner, Field field) implements HealthSlot {
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

    /** 通用数值字段位点：按字段声明类型装箱写回，覆盖 float/double 之外的整型与包装类型。
     * 部分模组把血量放在 int/long 字段（计数式或加密的血量）里，浮点探针看不到；
     * 这类字段只在名字像血量时才收集，避免扰动计时器/计数器。 */
    @SuppressWarnings("CatchMayIgnoreException")
    record NumberValueFieldSlot(Object owner, Field field) implements HealthSlot {
        @Override
        public double read() {
            try {
                Object value = field.get(owner);
                return value instanceof Number number ? number.doubleValue() : Double.NaN;
            } catch (Throwable t) {
                rethrowIfFatal(t);
                return Double.NaN;
            }
        }

        @Override
        public void write(double value) {
            try {
                // 装箱类型优先级：字段当前实际值的运行时类型（最可靠）> 声明类型。
                // 声明类型被擦除（Object/Number）的字段绝不能按声明类型装箱：如
                // SynchedEntityData$DataItem.value 声明 Object、运行时 Byte/Integer，按 Object 装箱会写成
                // Float/Double，而序列化器是 BYTE/INT，模组强转时直接 ClassCastException。
                Object current = field.get(owner);
                Class<?> boxType = current instanceof Number ? current.getClass() : field.getType();
                if (!isHealthCapableType(boxType)) {
                    probeLog("字段类型不可安全写入，跳过：{}#{} 声明={} 现值={}",
                            field.getDeclaringClass().getName(), field.getName(),
                            field.getType().getSimpleName(),
                            current == null ? "null" : current.getClass().getSimpleName());
                    return;
                }
                field.set(owner, boxNumber(boxType, value));
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 写入失败即放弃
            }
        }
    }

    record FloatArraySlot(float[] array, int index) implements HealthSlot {
        @Override
        public double read() {
            return array[index];
        }

        @Override
        public void write(double value) {
            array[index] = (float) value;
        }
    }

    record DoubleArraySlot(double[] array, int index) implements HealthSlot {
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
    record ByteArrayFloatSlot(byte[] array, int offset, boolean bigEndian) implements HealthSlot {
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

    /** 探针判定阈值 */
    static final float PROBE_EPSILON = 1.0E-3F;

    private static final DateTimeFormatter PROBE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Object PROBE_LOG_LOCK = new Object();

    private static PrintWriter probeLogFile;

    private static boolean probeLogFileResolved;

    /** 真伤各层诊断日志的统一出口（含 L0.3 常量池指纹层）。
     * debugProbes 打开时写到 logs/more_attributes-probe.log（带时间/线程前缀、每行即时 flush），
     * 关闭时输出到主日志（INFO）；文件建不出来时同样退回主日志。 */
    static void probeLog(String format, Object... args) {
        String message = "[真伤探针] " + format;
        PrintWriter out = MAConfig.debugProbes ? probeLogWriter() : null;
        if (out == null) {
            // 未开启独立文件（或文件建不出来）：正常写主日志
            MoreAttributesMod.LOGGER.info(message, args);
            return;
        }
        synchronized (PROBE_LOG_LOCK) {
            out.print('[');
            out.print(PROBE_TIME_FORMAT.format(LocalDateTime.now()));
            out.print("][");
            out.print(Thread.currentThread().getName());
            out.print("] ");
            out.println(renderProbeMessage(message, args));
            out.flush();
        }
    }

    /**
     * 一次性日志去重闸门：每 (实体类, 键) 只放行一次，用于给「每击都命中、结论按类稳定」的
     * 跳过/未探到消息降噪。不要用它去重「状态变化」类消息（写入生效/被回滚/结算后血量高于命中前/
     * 层抛异常），那些每次都可能带来新信息。
     *
     * @param seen 调用方自有的去重集合（按用途分开，避免不同层的键互相挤占）
     * @param type 实体类；为 null 时退化为每次都打（无类可归因）
     * @param key  该类的稳定键（层名/结论名），同一层不同原因用不同键
     * @return true 表示本次允许输出
     */
    static boolean oncePer(Set<String> seen, Class<?> type, String key) {
        if (type == null) return true;
        return seen.add(type.getName() + '#' + key);
    }

    /** 懒创建探针日志文件；只尝试一次，失败则返回 null（调用方退回主日志） */
    private static PrintWriter probeLogWriter() {
        synchronized (PROBE_LOG_LOCK) {
            if (probeLogFileResolved) return probeLogFile;
            probeLogFileResolved = true;
            try {
                Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
                Files.createDirectories(dir);
                Path file = dir.resolve("more_attributes-probe.log");
                // 每次启动换新文件：上一次会话的日志保留为 .1，本次从空文件开始（不再无限叠加）
                if (Files.exists(file)) {
                    Files.move(file, dir.resolve("more_attributes-probe.log.1"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                probeLogFile = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
                MoreAttributesMod.LOGGER.info("[真伤探针] 探针日志写入 {}", file);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                MoreAttributesMod.LOGGER.warn("[真伤探针] 无法创建探针日志文件，退回主日志：{}", t.toString());
                probeLogFile = null;
            }
            return probeLogFile;
        }
    }

    /** 把 SLF4J 风格的 {} 占位符替换成实参（探针日志统一用这种写法） */
    private static String renderProbeMessage(String template, Object[] args) {
        if (args == null || args.length == 0) return template;
        StringBuilder sb = new StringBuilder(template.length() + 48);
        int argIndex = 0;
        int cursor = 0;
        while (cursor < template.length()) {
            int brace = template.indexOf("{}", cursor);
            if (brace < 0 || argIndex >= args.length) {
                sb.append(template, cursor, template.length());
                break;
            }
            sb.append(template, cursor, brace).append(args[argIndex++]);
            cursor = brace + 2;
        }
        return sb.toString();
    }

    /** 日志里打印长列表时的截断上限 */
    private static final int LOG_LIST_LIMIT = 24;

    /** 长列表的日志投影：超过上限时截断并附总数（避免一行几百个字段名） */
    static String briefList(List<String> values) {
        if (values == null) return "[]";
        if (values.size() <= LOG_LIST_LIMIT) return values.toString();
        return values.subList(0, LOG_LIST_LIMIT) + " …（共 " + values.size() + " 个）";
    }

    /** 行为探针结果：命中“当前血量”位点（位点变大则 getHealth() 变大） */
    static final int PROBE_DIRECT = 1;

    /** 行为探针结果：命中“累计伤害”位点（位点变大则 getHealth() 变小） */
    static final int PROBE_TAKEN = -1;

    /** 行为探针结果：该位点不影响 getHealth() */
    static final int PROBE_MISS = 0;

    /**
     * 同步项「按序列化器」决定的装箱类型（EntityDataSerializers.INT → Integer 等）。
     * 这是权威的类型来源：槽位里可能暂时没值或已被写坏，但序列化器不会变。写同步项必须用它而非
     * 猜 Float/Double，否则模组按声明类型强转时抛 ClassCastException
     *（如 {@code EntityDataAccessor<Integer> DATA_HURTCOOLDOWN} 被写成 Float 就会在攻击/存档时崩）。
     */
    static Class<?> accessorBoxingType(EntityDataAccessor<?> accessor) {
        if (accessor == null) return null;
        try {
            Object serializer = accessor.getSerializer();
            if (serializer == EntityDataSerializers.INT) return Integer.class;
            if (serializer == EntityDataSerializers.FLOAT) return Float.class;
            if (serializer == EntityDataSerializers.LONG) return Long.class;
            if (serializer == EntityDataSerializers.BYTE) return Byte.class;
            if (serializer == EntityDataSerializers.BOOLEAN) return Boolean.class;
            if (serializer == EntityDataSerializers.STRING) return String.class;
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        return null;
    }

    /** 该装箱类型是否是可承载血量的数值包装类型 */
    static boolean isNumericBoxingType(Class<?> type) {
        return type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                || type == Float.class || type == Double.class;
    }

    /** 同步数据血量位点：读写都走 SynchedEntityData，装箱类型以序列化器为准 */
    record SyncedNumberSlot(SynchedEntityData entityData, EntityDataAccessor<Object> accessor)
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
                // 装箱类型以访问器的序列化器为准（权威、结构上不会变）；拿不到再退回当前值的类型。
                // 类型判定不出来就不写：猜 Float/Double 会写坏模组 BYTE/INT 的强类型槽位。
                Class<?> boxType = accessorBoxingType(accessor);
                if (!isNumericBoxingType(boxType)) {
                    boxType = entityData.get(accessor) instanceof Number current ? current.getClass() : null;
                }
                if (!isNumericBoxingType(boxType)) return;
                entityData.set(accessor, boxNumber(boxType, value));
            } catch (Throwable ignored) {
                // 写入失败即放弃
            }
        }
    }

    /** 把同步数据 accessor 包装成可读写位点；Long 也收（部分模组用它存加密/编码血量，能否命中交给行为验证） */
    static SyncedNumberSlot dataAccessorSlot(SynchedEntityData entityData, EntityDataAccessor<?> accessor) {
        try {
            Object current = entityData.get(accessor);
            if (!(current instanceof Number)) return null;
        } catch (Throwable ignored) {
            // 该 accessor 未在此实体注册等情况
            return null;
        }
        return new SyncedNumberSlot(entityData, rawAccessor(accessor));
    }

    /** 按目标类型装箱：基本类型与包装类型都接受，未列出的类型按 double 处理。
     * 整数存储用四舍五入而非截断：截断会把 0.5 点真伤变成 0（整型血量表上等于没扣或直接致死），
     * 也会让 3.7 点真伤只掉 3。 */
    static Object boxNumber(Class<?> type, double value) {
        if (type == float.class || type == Float.class) return (float) value;
        if (type == double.class || type == Double.class) return value;
        if (type == long.class || type == Long.class) return Math.round(value);
        if (type == int.class || type == Integer.class) {
            return (int) clampLong(Math.round(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        if (type == short.class || type == Short.class) {
            return (short) clampLong(Math.round(value), Short.MIN_VALUE, Short.MAX_VALUE);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) clampLong(Math.round(value), Byte.MIN_VALUE, Byte.MAX_VALUE);
        }
        // 类型未知（Object/Number/擦除泛型）时按 float 处理：MC 的血量本体是 float。
        // 绝不默认 Double——写进模组的 Float/Integer 槽位会让它之后强转必崩（ClassCastException）。
        return (float) value;
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    /** 一个可读写的字符串位点（普通字段或同步数据） */
    interface StringSlot {
        String read();

        void write(String value);
    }

    record StringFieldSlot(Object owner, Field field) implements StringSlot {
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

    record SyncedStringSlot(SynchedEntityData entityData, EntityDataAccessor<Object> accessor)
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
    record StringCandidate(Field field, EntityDataAccessor<?> accessor, StringSlot slot) {
    }

    /** 明文格式化：避免科学计数法 */
    static String plainText(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    static float healthOf(LivingEntity victim) {
        try {
            return victim.getHealth();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Float.NaN;
        }
    }

    static float maxHealthOf(LivingEntity victim) {
        try {
            return victim.getMaxHealth();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return Float.NaN;
        }
    }
}

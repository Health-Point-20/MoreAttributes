package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.fml.loading.FMLPaths;

import com.fu_meng.more_attributes.truedamage.GateSignatureDetector.GateKind;

import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.computeOutsideLock;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.probeLog;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.rethrowIfFatal;

/**
 * 单个实体类的 L0.3 门控报告（按类缓存，任何层都能廉价查询，无需改方法签名）：汇总继承链上各类的
 * 池指纹签名与相对原版基线的差集，供上游提前解反射黑名单、修正 gatedGetter 预判、候选重排、
 * 加密/MAC 层优先级提示与诊断。边界：gatedClasses 的 key 是类级内部名而非方法级，池无法定位门控所在方法。
 */
record GateReport(
        Class<?> entityType,
        Map<String, EnumSet<GateKind>> gatedClasses,
        Set<GateKind> entitySignatures,
        Map<String, Set<String>> poolDelta,
        boolean needsFilterLift,
        boolean hiddenClassSuspected,
        boolean encryptedStorageSuspected,
        boolean macAuthoritySuspected
) {

    static final GateReport EMPTY = new GateReport(
            null, Map.of(), Set.of(), Map.of(), false, false, false, false);

    private static final Map<Class<?>, GateReport> CACHE = new ConcurrentHashMap<>();

    /** 差集达到该数量才判为「疑似注入」（防误报） */
    private static final int DEFAULT_DELTA_THRESHOLD = 3;

    /** 按实体类取报告（缓存命中零开销）；档位不使用池情报时恒返回 {@link #EMPTY} */
    static GateReport forEntity(Class<?> entityType) {
        if (entityType == null || !MAConfig.trueDamageMode.usesPoolIntel()) return EMPTY;
        return computeOutsideLock(CACHE, entityType, () -> build(entityType));
    }

    /** 某个类是否被本报告标记为「含门控签名」 */
    boolean gatePredictedOn(Class<?> clazz) {
        return clazz != null && gatedClasses.containsKey(clazz.getName().replace('.', '/'));
    }

    private static GateReport build(Class<?> entityType) {
        Map<String, EnumSet<GateKind>> gated = new LinkedHashMap<>();
        EnumSet<GateKind> signatures = EnumSet.noneOf(GateKind.class);
        Map<String, Set<String>> delta = new LinkedHashMap<>();
        // 池差集只用于诊断输出（见 VanillaBaseline 边界说明），因此只在 debugProbes 下计算，
        // 默认路径连「逐继承链类取差集」的循环都不进。
        boolean baselineActive = MAConfig.debugProbes;
        boolean hidden = false;

        for (Class<?> current : ReflectionToolkit.hierarchy(entityType)) {
            try {
                EnumSet<GateKind> kinds = GateSignatureDetector.detectFor(current);
                if (!kinds.isEmpty()) {
                    gated.put(current.getName().replace('.', '/'), kinds);
                    signatures.addAll(kinds);
                }
                String internal = current.getName().replace('.', '/');
                if (internal.contains("/0x") || current.isHidden()) {
                    hidden = true;
                }
                if (baselineActive) {
                    Set<String> extras = VanillaBaseline.delta(internal, PoolFingerprint.of(current));
                    if (extras.size() >= DEFAULT_DELTA_THRESHOLD) {
                        delta.put(internal, extras);
                    }
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }

        boolean filterLift = hasKind(signatures, GateKind.REFLECTION_FILTER)
                || hasKind(signatures, GateKind.STACK_WALKER)
                || hasKind(signatures, GateKind.CALLER_CLASS)
                || hasKind(signatures, GateKind.GET_STACK_TRACE);
        boolean encrypted = hasKind(signatures, GateKind.CIPHER_STRING);
        boolean mac = hasKind(signatures, GateKind.MAC_AUTHORITY);

        GateReport report = new GateReport(entityType, Map.copyOf(gated), Set.copyOf(signatures),
                Map.copyOf(delta), filterLift, hidden, encrypted, mac);
        if (!report.isEmptyReport()) {
            probeLog("实体 {} 门控报告：类级签名={} 命中类={} 池差集类={} 解过滤={} 隐藏类={} 密文={} MAC={}",
                    entityType.getName(), signatures, gated.keySet(), delta.keySet(),
                    filterLift, hidden, encrypted, mac);
            int shown = 0;
            for (Map.Entry<String, Set<String>> entry : delta.entrySet()) {
                if (shown++ >= 20) {
                    probeLog("池差集：……(其余 {} 类已省略)", delta.size() - 20);
                    break;
                }
                probeLog("池差集 {} 新增 {} 项：{}", entry.getKey(), entry.getValue().size(),
                        preview(entry.getValue(), 8));
            }
        }
        return report;
    }

    private boolean isEmptyReport() {
        return gatedClasses.isEmpty() && poolDelta.isEmpty() && !hiddenClassSuspected;
    }

    private static boolean hasKind(Set<GateKind> kinds, GateKind kind) {
        return kinds.contains(kind);
    }

    private static String preview(Set<String> set, int limit) {
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (String value : set) {
            if (shown++ > 0) builder.append(", ");
            if (shown > limit) {
                builder.append("…");
                break;
            }
            builder.append(value);
        }
        return builder.toString();
    }
}




/**
 * 门控签名检测器：从运行时常量池指纹识别「常见反作弊 / 调用栈门控」的引用签名；常量池是类级视图，
 * 只能回答「该类含门控签名」，不能回答「是 getHealth 还是 hurt 被注入」。
 * 调用栈类规则（StackWalker / getStackTrace / getCallerClass）与 REFLECTION_FILTER 恒开——后者驱动
 * GateReport.needsFilterLift()，是功能性判据，不能挪到诊断开关后面，否则「把自己成员登记进过滤黑名单」
 * 的目标在正常游玩里会让我们少看到方法，属静默回退；其余规则（类加载器/身份/MAC/密文/隐藏类/klassPtr）
 * 只喂诊断日志，仅在 {@link MAConfig#debugProbes} 打开时检测，正常游玩零成本。
 */
final class GateSignatureDetector {

    private GateSignatureDetector() {
    }

    /** 门控形态 */
    enum GateKind {
        /** 调用栈遍历（StackWalker） */
        STACK_WALKER,
        /** Thread/Throwable.getStackTrace */
        GET_STACK_TRACE,
        /** sun.reflect.Reflection.getCallerClass（JDK 17 已移除，保留规则无害） */
        CALLER_CLASS,
        /** 探测自身来自哪个类加载器 / 哪个模组（ModList 等） */
        CLASS_LOADER_PROBE,
        /** 身份校验：UUID 形态常量 + 摘要算法 */
        UUID_IDENTITY,
        /** MAC / 摘要校验权威性 */
        MAC_AUTHORITY,
        /** 密文字符串存储（Cipher / Base64） */
        CIPHER_STRING,
        /** 主动登记 JDK 反射过滤黑名单 */
        REFLECTION_FILTER,
        /** defineHiddenClass 隐藏类加载 */
        HIDDEN_CLASS_LOADER,
        /** Unsafe 字段偏移 + Class 型字段（klassPtr 重定向迹象） */
        KLASS_PTR_MANIP
    }

    private static final Map<Class<?>, EnumSet<GateKind>> CACHE = new ConcurrentHashMap<>();

    /** 含 FULL 规则的检测结果缓存：与基础缓存分开，避免运行期开关 debugProbes 时读到旧结论 */
    private static final Map<Class<?>, EnumSet<GateKind>> FULL_RULE_CACHE = new ConcurrentHashMap<>();

    /** 按类缓存的检测结果（含空集） */
    static EnumSet<GateKind> detectFor(Class<?> owner) {
        if (owner == null) return EnumSet.noneOf(GateKind.class);
        boolean full = MAConfig.debugProbes;
        Map<Class<?>, EnumSet<GateKind>> cache = full ? FULL_RULE_CACHE : CACHE;
        EnumSet<GateKind> cached = cache.get(owner);
        if (cached != null) return cached;
        EnumSet<GateKind> detected = detect(PoolFingerprint.of(owner), full);
        EnumSet<GateKind> raced = cache.putIfAbsent(owner, detected);
        return raced != null ? raced : detected;
    }

    /** 纯函数：由指纹与「是否跑 FULL 规则」算出签名集合（不缓存，便于单测） */
    static EnumSet<GateKind> detect(PoolFingerprint fp, boolean full) {
        EnumSet<GateKind> kinds = EnumSet.noneOf(GateKind.class);
        if (fp == null) return kinds;
        Set<String> classes = fp.classRefs();
        Set<String> methods = fp.methodRefs();

        // 调用栈类规则：恒开
        if (classes.contains("java/lang/StackWalker") || hasAny(methods, "java/lang/StackWalker.getInstance")) {
            kinds.add(GateKind.STACK_WALKER);
        }
        if (hasAny(methods, "java/lang/Thread.getStackTrace", "java/lang/Throwable.getStackTrace")) {
            kinds.add(GateKind.GET_STACK_TRACE);
        }
        if (hasAny(methods, "sun/reflect/Reflection.getCallerClass")) {
            kinds.add(GateKind.CALLER_CLASS);
        }
        // 主动登记 JDK 反射过滤黑名单：恒开（功能性判据，见类注释）
        if (classes.contains("jdk/internal/reflect/Reflection")
                || classes.contains("sun/reflect/Reflection")
                || hasAny(methods, "jdk/internal/reflect/Reflection.registerFieldsToFilter",
                "jdk/internal/reflect/Reflection.registerMethodsToFilter")) {
            kinds.add(GateKind.REFLECTION_FILTER);
        }
        // 其余规则只喂诊断日志：仅在 debugProbes 下检测
        if (!full) return kinds;

        // 类加载器 / 模组探测
        if (classes.contains("net/minecraftforge/fml/ModList")
                || classes.contains("net/minecraftforge/fml/loading/FMLPaths")
                || classes.contains("cpw/mods/modlauncher/Launcher")
                || classes.contains("net/minecraftforge/fml/loading/ModLoadingContext")) {
            kinds.add(GateKind.CLASS_LOADER_PROBE);
        }
        // 身份校验：UUID 形态常量 + 摘要/加解密
        if ((classes.contains("java/util/UUID") || hasUuidConstant(fp.utf8Constants()))
                && (classes.contains("java/security/MessageDigest") || classes.contains("javax/crypto/Mac"))) {
            kinds.add(GateKind.UUID_IDENTITY);
        }
        // MAC / 摘要校验
        if (classes.contains("javax/crypto/Mac")
                || hasAny(methods, "javax/crypto/Mac.getInstance", "javax/crypto/Mac.doFinal")) {
            kinds.add(GateKind.MAC_AUTHORITY);
        }
        // 密文字符串存储
        if (classes.contains("javax/crypto/Cipher")
                || hasAny(methods, "java/util/Base64.getDecoder", "java/util/Base64.getEncoder",
                "javax/crypto/Cipher.getInstance", "javax/crypto/Cipher.doFinal")) {
            kinds.add(GateKind.CIPHER_STRING);
        }
        // 隐藏类加载
        if (hasAny(methods, "java/lang/invoke/MethodHandles$Lookup.defineHiddenClass",
                "java/lang/invoke/MethodHandles$Lookup.defineHiddenClassWithClassData",
                "java/lang/invoke/MethodHandles.privateLookupIn")) {
            kinds.add(GateKind.HIDDEN_CLASS_LOADER);
        }
        // klassPtr 重定向迹象：Unsafe 字段偏移 + Class 型成员引用
        if (classes.contains("sun/misc/Unsafe")
                && hasAny(methods, "sun/misc/Unsafe.objectFieldOffset", "sun/misc/Unsafe.staticFieldOffset")) {
            kinds.add(GateKind.KLASS_PTR_MANIP);
        }
        return kinds;
    }

    private static boolean hasAny(Set<String> refs, String... prefixes) {
        for (String ref : refs) {
            for (String prefix : prefixes) {
                if (ref.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /** 是否含 UUID 形态的字面量（8-4-4-4-12 十六进制） */
    private static boolean hasUuidConstant(Set<String> utf8) {
        for (String value : utf8) {
            if (isUuid(value)) return true;
        }
        return false;
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
                continue;
            }
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}





/**
 * 原版基线：在（尽量）干净的 Forge 环境采样原版血量相关类的常量池引用集合，再对运行时指纹做差集，
 * 把「模组向原版类注入的引用」暴露出来；差集来源可能是正常模组的 Mixin 注入，也可能是恶意门控，池层面无法区分。
 * 基线时效性：采样环境若已装载注入原版血量类的模组，基线会被污染、差集漏报——这是已知且被接受的限制，
 * 差集只用于诊断输出，不作写入判据；且无法枚举哪些模组会注入，故不做名单检查。
 * 常态开销：本类只服务诊断，{@link #bootstrapIfAbsent()}（采样并落盘）与 {@link #delta}（逐类差集）都只在
 * {@link MAConfig#debugProbes} 打开时工作；默认路径不读写 pool_baseline.bin，也不做差集。
 */
final class VanillaBaseline {

    private VanillaBaseline() {
    }

    /** 覆盖的原版类（内部名，'/' 分隔） */
    private static final Map<String, String> VANILLA_CLASSES = new LinkedHashMap<>();

    static {
        for (String binary : new String[]{
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.player.Player",
                "net.minecraft.server.level.ServerPlayer",
                "net.minecraft.world.entity.Entity",
                "net.minecraft.network.syncher.SynchedEntityData",
                "net.minecraft.world.damagesource.DamageSource"}) {
            VANILLA_CLASSES.put(binary.replace('.', '/'), binary);
        }
    }

    private static final String FILE_NAME = "pool_baseline.bin";

    /** 内存中的基线：内部类名 → 引用字符串集合；null 表示尚未初始化 */
    private static volatile Map<String, Set<String>> baseline;

    private static volatile boolean bootstrapAttempted;

    private static Map<String, Set<String>> current() {
        Map<String, Set<String>> loaded = baseline;
        if (loaded != null) return loaded.isEmpty() ? null : loaded;
        return null;
    }

    /** 首次调用时加载基线文件，不存在则在（干净）环境采样并落盘。由后台预热线程调用，失败静默。 */
    static void bootstrapIfAbsent() {
        // 采样要读 6 个原版类的 class 字节并落盘，且结论只喂诊断日志：默认不开。
        // 守卫放在 bootstrapAttempted 之前，这样运行期（/ma 命令）打开 debugProbes 后仍能补采样。
        if (!MAConfig.debugProbes) return;
        if (bootstrapAttempted) return;
        synchronized (VanillaBaseline.class) {
            if (bootstrapAttempted) return;
            bootstrapAttempted = true;
            try {
                Path file = file();
                if (Files.exists(file)) {
                    baseline = read(file);
                    return;
                }
                Map<String, Set<String>> sampled = sample();
                if (!sampled.isEmpty()) {
                    write(file, sampled);
                    baseline = sampled;
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }
    }

    private static Map<String, Set<String>> sample() {
        Map<String, Set<String>> sampled = new LinkedHashMap<>();
        ClassLoader loader = VanillaBaseline.class.getClassLoader();
        for (Map.Entry<String, String> entry : VANILLA_CLASSES.entrySet()) {
            Class<?> type;
            try {
                type = Class.forName(entry.getValue(), false, loader);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                continue;
            }
            Set<String> refs = refsOf(PoolFingerprint.of(type));
            if (!refs.isEmpty()) sampled.put(entry.getKey(), refs);
        }
        return sampled;
    }

    /** 计算「基线没有」的新增引用（差集）；基线不可用或该类不在基线中时返回空集。
     * @param internalName 目标类的内部名（'/' 分隔）
     * @param fp 运行时指纹 */
    static Set<String> delta(String internalName, PoolFingerprint fp) {
        if (!MAConfig.debugProbes) return Set.of();
        Map<String, Set<String>> loaded = current();
        if (loaded == null || fp == null) return Set.of();
        Set<String> known = loaded.get(internalName);
        if (known == null) return Set.of();
        Set<String> extras = new LinkedHashSet<>(refsOf(fp));
        extras.removeAll(known);
        return extras;
    }

    private static Set<String> refsOf(PoolFingerprint fp) {
        if (fp == null) return Set.of();
        Set<String> refs = new LinkedHashSet<>();
        refs.addAll(fp.classRefs());
        refs.addAll(fp.methodRefs());
        refs.addAll(fp.fieldRefs());
        return refs;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("more_attributes").resolve(FILE_NAME);
    }

    private static void write(Path file, Map<String, Set<String>> data) throws Exception {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(data.size());
            for (Map.Entry<String, Set<String>> entry : data.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().size());
                for (String ref : entry.getValue()) {
                    out.writeUTF(ref);
                }
            }
        }
    }

    private static Map<String, Set<String>> read(Path file) throws Exception {
        Map<String, Set<String>> data = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int classes = in.readInt();
            for (int i = 0; i < classes; i++) {
                String name = in.readUTF();
                int refs = in.readInt();
                Set<String> set = new LinkedHashSet<>(Math.max(4, refs));
                for (int j = 0; j < refs; j++) {
                    set.add(in.readUTF());
                }
                data.put(name, set);
            }
        }
        return data;
    }
}


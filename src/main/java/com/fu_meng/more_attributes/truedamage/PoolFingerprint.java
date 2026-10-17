package com.fu_meng.more_attributes.truedamage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.computeOutsideLock;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.rethrowIfFatal;




/**
 * 一个类的运行时常量池指纹：变换后内容的引用视图（类 / 方法 / 字段 / Utf8 文本）。这是 L0.3 的原子数据，构造只走
 * {@link ConstantPoolAccess} 的安全读取通道，绝不调用会崩 JVM 的 {@code getStringAt}（字符串文本经 {@code Utf8} 条目获得）。池在类定义后即稳定，不存在「运行时才补齐」的可能，空结果也缓存。
 */
record PoolFingerprint(
        Class<?> owner,
        int poolSize,
        Set<String> classRefs,        // 内部名，'/' 分隔
        Set<String> methodRefs,       // "owner.name:descriptor"
        Set<String> fieldRefs,        // "owner.name:descriptor"
        Set<String> utf8Constants     // 全部 CONSTANT_Utf8 文本（含 Mixin 注入的字符串与描述符）
) {

    static final PoolFingerprint EMPTY = new PoolFingerprint(
            null, 0, Set.of(), Set.of(), Set.of(), Set.of());

    private static final Map<Class<?>, PoolFingerprint> CACHE = new ConcurrentHashMap<>();

    /** 按类缓存（含空结果）：池在类定义后稳定，不会「迟到注入」 */
    static PoolFingerprint of(Class<?> owner) {
        if (owner == null) return EMPTY;
        return computeOutsideLock(CACHE, owner, () -> build(owner));
    }

    private static PoolFingerprint build(Class<?> owner) {
        Object pool = ConstantPoolAccess.poolOf(owner);
        if (pool == null) return EMPTY;
        int size = ConstantPoolAccess.size(pool);
        if (size <= 0) return EMPTY;

        Set<String> classRefs = new LinkedHashSet<>();
        Set<String> methodRefs = new LinkedHashSet<>();
        Set<String> fieldRefs = new LinkedHashSet<>();
        Set<String> utf8Constants = new LinkedHashSet<>();

        for (int i = 1; i < size; i++) {
            String tag = ConstantPoolAccess.tagAt(pool, i);
            if (tag == null) continue;
            try {
                switch (tag) {
                    case "UTF8" -> {
                        String text = ConstantPoolAccess.utf8At(pool, i);
                        if (text == null) break;
                        utf8Constants.add(text);
                        // 描述符 / 类名形态的 Utf8 也纳入 classRefs：Mixin 注入的类型引用常只出现在描述符里
                        if (ReflectionToolkit.looksLikeInternalClassName(text)) classRefs.add(text);
                        collectDescriptorTypes(text, classRefs);
                    }
                    case "CLASS" -> {
                        // CLASS 条目本身不能调 getClassRefIndexAt（会抛 Wrong type）；
                        // 用 getClassAtIfLoaded 取已加载的类名，未加载返回 null（不触发加载）
                        Class<?> loaded = ConstantPoolAccess.classAtIfLoaded(pool, i);
                        if (loaded != null) classRefs.add(loaded.getName().replace('.', '/'));
                    }
                    case "FIELDREF", "METHODREF", "INTERFACEMETHODREF" -> {
                        String[] info = ConstantPoolAccess.memberRefAt(pool, i);
                        if (info == null || info.length < 3) break;
                        classRefs.add(info[0]);
                        String ref = info[0] + "." + info[1] + ":" + info[2];
                        if ("FIELDREF".equals(tag)) {
                            fieldRefs.add(ref);
                        } else {
                            methodRefs.add(ref);
                        }
                        collectDescriptorTypes(info[2], classRefs);
                    }
                    case "INVOKEDYNAMIC" -> {
                        // indy 的 name&type 描述符里可能含类型引用，纳入 classRefs；不统计 indy 计数（字符串拼接 / lambda 遍地都是，作门控是噪声）。
                        int natIndex = ConstantPoolAccess.nameAndTypeRefIndexAt(pool, i);
                        if (natIndex > 0) {
                            String[] info = ConstantPoolAccess.nameAndTypeInfoAt(pool, natIndex);
                            if (info != null && info.length >= 2) {
                                collectDescriptorTypes(info[1], classRefs);
                            }
                        }
                    }
                    default -> {
                        // STRING / NAMEANDTYPE / METHODHANDLE / METHODTYPE / 数值常量：不单独取值（STRING 文本已由其 Utf8 条目覆盖）。
                    }
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 单条目失败跳过，不影响整体指纹
            }
        }

        return new PoolFingerprint(owner, size,
                Set.copyOf(classRefs), Set.copyOf(methodRefs), Set.copyOf(fieldRefs),
                Set.copyOf(utf8Constants));
    }

    /** 从 JVM 描述符中提取 {@code L...;} 形态的内部类名，追加到 {@code out} */
    private static void collectDescriptorTypes(String descriptor, Set<String> out) {
        if (descriptor == null || descriptor.isEmpty()) return;
        int length = descriptor.length();
        // 快路径：不含 'L' 的描述符（纯基本类型）直接跳过
        if (descriptor.indexOf('L') < 0) return;
        int i = 0;
        while (i < length) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i + 1);
                if (end < 0) return;
                String name = descriptor.substring(i + 1, end);
                if (ReflectionToolkit.looksLikeInternalClassName(name)) out.add(name);
                i = end + 1;
            } else {
                i++;
            }
        }
    }
}




/**
 * 运行时常量池的原始读取封装（L0.3 常量池指纹层的最底层）。通过
 * {@code jdk.internal.access.SharedSecrets.getJavaLangAccess().getConstantPool(Class)} 拿到运行时常量池：
 * 类在 {@code defineClass} 前已完成 Mixin / CoreMod / Agent 变换，JVM 解析的是变换后的字节码，
 * 这个池就是变换后内容的引用视图，也是不用 Java Agent 时唯一能观测运行期真实类内容的口子。
 * 安全铁律：{@code getStringAt} 会触发 {@code EXCEPTION_ACCESS_VIOLATION} 直接崩 JVM（native 层，
 * try/catch 兜不住），本类绝不提供该方法，字符串字面量文本改从 {@link #utf8At} 读。
 * {@code getClassRefIndexAt} / {@code getNameAndTypeRefIndexAt} 只对 {@code *REF} /
 * {@code INVOKEDYNAMIC} 有效，对 {@code CLASS} 调用会抛 {@code IllegalArgumentException}。
 * 所有方法失败一律静默返回安全值（null / -1 / 空），绝不向调用方抛异常；用
 * {@link ReflectionToolkit#implLookup()}（TRUSTED 查找）访问未导出包，无需 {@code --add-opens}。
 */
final class ConstantPoolAccess {

    private ConstantPoolAccess() {
    }

    private static final Object LOCK = new Object();

    /** 解析完成标志（无论成功还是失败，只尝试一次） */
    private static volatile boolean resolved;

    private static Object javaLangAccess;
    private static MethodHandle getConstantPool;
    private static MethodHandle getSize;
    private static MethodHandle getTagAt;
    private static MethodHandle getUTF8At;
    private static MethodHandle getMemberRefInfoAt;
    private static MethodHandle getNameAndTypeRefIndexAt;
    private static MethodHandle getNameAndTypeRefInfoAt;
    private static MethodHandle getClassAtIfLoaded;

    /** 双重检查 + volatile 发布；解析失败只记一次，返回 {@code false} */
    private static boolean resolve() {
        if (resolved) return javaLangAccess != null;
        synchronized (LOCK) {
            if (resolved) return javaLangAccess != null;
            resolved = true;
            try {
                MethodHandles.Lookup lookup = ReflectionToolkit.implLookup();
                if (lookup == null) return false;
                Class<?> sharedSecrets = Class.forName("jdk.internal.access.SharedSecrets");
                javaLangAccess = lookup.unreflect(sharedSecrets.getMethod("getJavaLangAccess")).invoke();
                if (javaLangAccess == null) return false;
                Class<?> poolClass = Class.forName("jdk.internal.reflect.ConstantPool");
                getConstantPool = lookup.unreflect(
                        javaLangAccess.getClass().getMethod("getConstantPool", Class.class));
                getSize = lookup.unreflect(poolClass.getMethod("getSize"));
                getTagAt = lookup.unreflect(poolClass.getMethod("getTagAt", int.class));
                getUTF8At = lookup.unreflect(poolClass.getMethod("getUTF8At", int.class));
                getMemberRefInfoAt = lookup.unreflect(poolClass.getMethod("getMemberRefInfoAt", int.class));
                getNameAndTypeRefIndexAt = lookup.unreflect(poolClass.getMethod("getNameAndTypeRefIndexAt", int.class));
                getNameAndTypeRefInfoAt = lookup.unreflect(poolClass.getMethod("getNameAndTypeRefInfoAt", int.class));
                getClassAtIfLoaded = lookup.unreflect(poolClass.getMethod("getClassAtIfLoaded", int.class));
                return true;
            } catch (Throwable t) {
                rethrowIfFatal(t);
                javaLangAccess = null;
                return false;
            }
        }
    }

    /**
     * 取目标类的运行时常量池对象（{@code jdk.internal.reflect.ConstantPool}）；失败返回 {@code null}。隐藏类（{@code defineHiddenClass} 生成、名字含 {@code /0x...}）没有稳定的外层枚举视图，其池对「找血量仓库」也无额外价值，直接跳过。
     */
    static Object poolOf(Class<?> target) {
        if (target == null || !resolve()) return null;
        if (target.isPrimitive() || target.isArray() || target.isHidden()) return null;
        try {
            return getConstantPool.invoke(javaLangAccess, target);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 池大小（= 合法索引上限）；失败返回 0 */
    static int size(Object pool) {
        if (pool == null) return 0;
        try {
            return (int) getSize.invoke(pool);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return 0;
        }
    }

    /** 条目 tag 名（{@code CLASS}/{@code METHODREF}/{@code UTF8}/…）；失败返回 {@code null} */
    static String tagAt(Object pool, int index) {
        if (pool == null) return null;
        try {
            Object tag = getTagAt.invoke(pool, index);
            return tag == null ? null : tag.toString();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** {@code CONSTANT_Utf8} 文本；失败返回 {@code null}。字符串字面量的文本也从这里读（不用 getStringAt） */
    static String utf8At(Object pool, int index) {
        if (pool == null) return null;
        try {
            return (String) getUTF8At.invoke(pool, index);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 成员引用信息 {@code [owner, name, descriptor]}（owner 为 {@code /} 分隔内部名）；失败返回 {@code null} */
    static String[] memberRefAt(Object pool, int index) {
        if (pool == null) return null;
        try {
            return (String[]) getMemberRefInfoAt.invoke(pool, index);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** name&type 引用索引（对 {@code *REF} / {@code INVOKEDYNAMIC} 有效）；失败返回 -1 */
    static int nameAndTypeRefIndexAt(Object pool, int index) {
        if (pool == null) return -1;
        try {
            return (int) getNameAndTypeRefIndexAt.invoke(pool, index);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return -1;
        }
    }

    /** name&type 信息 {@code [name, descriptor]}；失败返回 {@code null} */
    static String[] nameAndTypeInfoAt(Object pool, int index) {
        if (pool == null) return null;
        try {
            return (String[]) getNameAndTypeRefInfoAt.invoke(pool, index);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 已加载时返回该 {@code CLASS} 条目对应的类，未加载返回 {@code null}（不触发加载）；失败返回 {@code null} */
    static Class<?> classAtIfLoaded(Object pool, int index) {
        if (pool == null) return null;
        try {
            return (Class<?>) getClassAtIfLoaded.invoke(pool, index);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }
}


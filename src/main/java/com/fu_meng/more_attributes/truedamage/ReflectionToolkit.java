package com.fu_meng.more_attributes.truedamage;

import net.minecraft.network.syncher.EntityDataAccessor;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.SoftReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import sun.misc.Unsafe;

import static com.fu_meng.more_attributes.truedamage.ModWriterProbe.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/**
 * 所有层共用的底层工具箱：VarHandle 优先 / 反射兜底的字段读写、静默反射调用与取值、Unsafe 直写，
 * 以及类层级与方法表缓存、JDK 反射黑名单解除、class 文件级读取与「引用类展开」（嵌套类 / 模组类 / 写入口候选类）。
 */
final class ReflectionToolkit {

    private static final Map<Field, VarHandle> FIELD_VAR_HANDLES = new ConcurrentHashMap<>();

    private static final Set<Field> NO_FIELD_VAR_HANDLE = ConcurrentHashMap.newKeySet();

    static VarHandle varHandleOf(Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers()) || NO_FIELD_VAR_HANDLE.contains(field)) {
            return null;
        }
        VarHandle cached = FIELD_VAR_HANDLES.get(field);
        if (cached != null) return cached;
        VarHandle handle = unreflectVarHandle(field);
        if (handle == null) {
            // 句柄不可用则记入负缓存
            NO_FIELD_VAR_HANDLE.add(field);
            return null;
        }
        FIELD_VAR_HANDLES.put(field, handle);
        return handle;
    }

    /**
     * 取字段的 VarHandle：先试 IMPL_LOOKUP，再退 privateLookupIn。
     * 顺序不可交换：IMPL_LOOKUP 绕过模块开放与 JDK 反射过滤表；部分模组会把自己的成员登记进
     * 过滤表甚至清空 Class.reflectionData，这些目标上 privateLookupIn 会失败。
     */
    @SuppressWarnings("CatchMayIgnoreException")
    static VarHandle unreflectVarHandle(Field field) {
        MethodHandles.Lookup impl = implLookup();
        if (impl != null) {
            try {
                return impl.unreflectVarHandle(field);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                // 落到下一档
            }
        }
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflectVarHandle(field);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static EntityDataAccessor<Object> rawAccessor(EntityDataAccessor<?> accessor) {
        return (EntityDataAccessor<Object>) accessor;
    }

    /** 按类缓存的成员枚举结果（只存 Class/Method/Field，不持有实体，可跨 tick 复用）；冷路径上会被反复调用（最多上千个候选类 × 多次），是首击停顿的主要来源之一。 */
    private static final Map<Class<?>, List<Method>> STATIC_METHOD_CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, List<Method>> PUBLIC_STATIC_METHOD_CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, List<Method>> NUMERIC_RETURNING_METHOD_CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, List<Method>> SINGLE_NUMBER_SETTER_CACHE = new ConcurrentHashMap<>();

    /** 类的静态方法（含非公开；供权威读口 / 注销口探测） */
    static List<Method> declaredStaticMethods(Class<?> clazz) {
        return computeOutsideLock(STATIC_METHOD_CACHE, clazz, () -> collectDeclaredStaticMethods(clazz));
    }

    private static List<Method> collectDeclaredStaticMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Method[] declared;
        try {
            declared = declaredMethodsOf(clazz);
        } catch (Throwable ignored) {
            return result;
        }
        for (Method method : declared) {
            int modifiers = method.getModifiers();
            // native 方法一律不收（进程级 abort 风险，见 isNativeMember）
            if (method.isSynthetic() || !Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            result.add(method);
        }
        return result;
    }

    /** 引用关系展开深度与规模上限 */
    private static final int REFERENCE_MAX_DEPTH = 2;

    static final int REFERENCE_MAX_CLASSES = 512;

    /** 「找字段登记表 / 静态容器 / 编解码器」这类间接持有形态用的更深一跳 */
    private static final int REGISTRY_MAX_DEPTH = 3;

    private static final int REFERENCE_MAX_TABLES = 64;

    /** 每个类只解析一次字节码，缓存它引用的类名（内部名，'/' 分隔） */
    private static final Map<Class<?>, List<String>> REFERENCED_CLASS_CACHE = new ConcurrentHashMap<>();

    /** 类字节读取失败的退避缓存：临时 IO 失败不得固化成「该类没有引用」 */
    static final NegativeCache<Class<?>> REFERENCE_READ_NEGATIVE =
            new NegativeCache<>("引用常量池读取");

    /** 每个实体类只找一次静态表字段 */
    static final Map<Class<?>, List<Field>> REFERENCED_TABLE_CACHE = new ConcurrentHashMap<>();

    /** 可达模组类 + 嵌套类：静态表查找与核心类候选共用 */
    private static final Map<Class<?>, List<Class<?>>> REFERENCED_NESTED_CACHE = new ConcurrentHashMap<>();

    /** 宽包名可达类 + 嵌套类：健康读写口与模组击杀口共用 */
    private static final Map<Class<?>, List<Class<?>>> WRITER_CANDIDATE_CACHE = new ConcurrentHashMap<>();

    /** 更深一跳的模组类展开结果（只含 Class，可跨 tick 复用），省去攻击路径重复走 BFS */
    private static final Map<Class<?>, List<Class<?>>> REFERENCED_DEEP_CACHE = new ConcurrentHashMap<>();

    /** 深度 3 可达模组类 + 嵌套类：档 3/4 的「代管血量核心类」候选（纯常量池解析，不读模组文件），
     *  上限比普通展开略大（要多容纳一层嵌套类）。 */
    private static final Map<Class<?>, List<Class<?>>> REFERENCED_DEEP_NESTED_CACHE = new ConcurrentHashMap<>();

    private static final int REFERENCE_DEEP_NESTED_MAX = 1024;

    /** 条目类继承链上可写的 value 字段偏移（按类缓存一次） */
    private static final Map<Class<?>, OptionalLong> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    /** Unsafe 实例持有者：{@code null} 值表示「解析过但取不到」，与「尚未解析」区分开 */
    record UnsafeRef(Unsafe value) {
    }

    /** {@code null} = 尚未解析；解析结果（含失败）经 volatile 一次性发布 */
    private static volatile UnsafeRef unsafeRef;

    private static final Object UNSAFE_LOCK = new Object();

    /** 模组类上的静态 Map 字段（静态表探测与 MAC 权威记录探测共用） */
    static boolean isStaticMapField(Field field) {
        return Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                && Map.class.isAssignableFrom(field.getType());
    }

    /** 实体类沿常量池引用关系可达的模组类里的静态 Map 字段 */
    static List<Field> findReferencedTables(Class<?> entityType) {
        List<Class<?>> candidates = referencedNestedClasses(entityType);
        List<Field> tables = new ArrayList<>();
        outer:
        for (Class<?> candidate : candidates) {
            // 逐类隔离：候选类的字段表/父类可能引用着本整合包没装的模组，getDeclaredFields()
            // 会抛 NoClassDefFoundError。它只代表「这个类扫不了」，绝不能让整层乃至整次结算中止，
            // 否则后面十几层（深度图/影子/权威/写入口…）全都不跑。
            Field[] declared;
            try {
                declared = declaredFieldsOf(candidate);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                continue;
            }
            for (Field field : declared) {
                // 预算必须在外层也判：否则超出上限后仍会遍历全部候选类的字段表
                if (tables.size() >= REFERENCE_MAX_TABLES) break outer;
                try {
                    if (isStaticMapField(field)) {
                        field.setAccessible(true);
                        tables.add(field);
                    }
                } catch (Throwable t) {
                    // field.getType() / setAccessible 同样可能因缺失依赖而抛 LinkageError
                    rethrowIfFatal(t);
                }
            }
        }
        return tables;
    }

    /** 把每个候选类的嵌套类一并纳入扫描范围 */
    static List<Class<?>> withNestedClasses(List<Class<?>> candidates, int maxClasses) {
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
    static List<Class<?>> referencedNestedClasses(Class<?> entityType) {
        List<Class<?>> cached = REFERENCED_NESTED_CACHE.get(entityType);
        if (cached != null) return cached;
        List<Class<?>> found = withNestedClasses(referencedModClasses(entityType), REFERENCE_MAX_CLASSES);
        // 空结果也缓存：常量池可达性是结构性结论，不缓存会让「没有可达模组类」的实体每次命中都重跑 BFS + 类加载。
        REFERENCED_NESTED_CACHE.put(entityType, found);
        return found;
    }

    /** 宽包名可达类 + 嵌套类（健康读写口与模组击杀口共用） */
    static List<Class<?>> writerCandidateClasses(Class<?> entityType) {
        List<Class<?>> cached = WRITER_CANDIDATE_CACHE.get(entityType);
        if (cached != null) return cached;
        // 池可达者必然已加载（或可由 CXF 解析），且能看到 Mixin 注入的引用与隐藏类的引用者；
        // jar BFS 只能看到变换前的静态引用，故池可达者排在前面，两者合并后去重。
        List<Class<?>> merged = new ArrayList<>();
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> candidate : poolReachableClasses(entityType)) {
            if (seen.add(candidate)) merged.add(candidate);
        }
        for (Class<?> candidate : referencedWriterClasses(entityType)) {
            if (seen.add(candidate)) merged.add(candidate);
        }
        List<Class<?>> found = withNestedClasses(merged, WRITER_REFERENCE_MAX_CLASSES);
        // 空结果也缓存（同上：结构性结论）
        WRITER_CANDIDATE_CACHE.put(entityType, found);
        return found;
    }

    /** {@code poolReachableClasses} 的按类缓存（含空结果） */
    private static final Map<Class<?>, Set<Class<?>>> POOL_REACHABLE_CACHE = new ConcurrentHashMap<>();

    /** 池可达模组类的 BFS 最大深度（起点算 0 层） */
    private static final int POOL_REACHABLE_MAX_DEPTH = 2;

    /** 从运行时常量池引用关系出发可达的模组类（不初始化，仅加载）。
     *  池是变换后视图，能看到 Mixin/CoreMod 注入的类引用与「外层类对隐藏类的引用」，这是它与 jar 字节解析的关键差异。 */
    static Set<Class<?>> poolReachableClasses(Class<?> start) {
        if (start == null) return Set.of();
        return computeOutsideLock(POOL_REACHABLE_CACHE, start, () -> collectPoolReachable(start));
    }

    private static Set<Class<?>> collectPoolReachable(Class<?> start) {
        Set<Class<?>> result = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        ClassLoader loader = start.getClassLoader();
        queue.add(new Object[]{start, 0});
        visited.add(start);
        while (!queue.isEmpty() && result.size() < WRITER_REFERENCE_MAX_CLASSES) {
            Object[] entry = queue.poll();
            Class<?> current = (Class<?>) entry[0];
            int depth = (Integer) entry[1];
            for (String internal : PoolFingerprint.of(current).classRefs()) {
                if (internal.startsWith("[")) continue;
                String binary = internal.replace('/', '.');
                if (isUnsafeToInvoke(binary) || !isModClassName(binary)) continue;
                Class<?> loaded = loadClass(binary, loader);
                if (loaded == null || loaded.isInterface() || !visited.add(loaded)) continue;
                result.add(loaded);
                if (depth + 1 < POOL_REACHABLE_MAX_DEPTH) {
                    queue.add(new Object[]{loaded, depth + 1});
                }
            }
        }
        return result;
    }

    /** 池引用类名（内部名）；池不可用（档位不用池情报或读取失败）时返回 null，调用方据此降级到 jar 解析。 */
    static List<String> poolReferencedClasses(Class<?> type) {
        if (!com.fu_meng.more_attributes.MAConfig.trueDamageMode.usesPoolIntel()) return null;
        PoolFingerprint fp = PoolFingerprint.of(type);
        if (fp.classRefs().isEmpty()) return null;
        return new ArrayList<>(fp.classRefs());
    }


    /** 从实体类出发沿常量池引用关系广度优先展开，收集可达的模组类（不含起点自身） */
    static List<Class<?>> referencedModClasses(Class<?> entityType) {
        return referencedClasses(entityType, false, REFERENCE_MAX_DEPTH, REFERENCE_MAX_CLASSES);
    }

    /** 更深一跳的模组类展开，给「找字段登记表 / 静态容器 / 编解码器」这类间接持有形态用 */
    static List<Class<?>> referencedModClassesDeep(Class<?> entityType) {
        List<Class<?>> cached = REFERENCED_DEEP_CACHE.get(entityType);
        if (cached != null) return cached;
        List<Class<?>> found = referencedClasses(entityType, false, REGISTRY_MAX_DEPTH, REFERENCE_MAX_CLASSES);
        // 空结果也缓存（结构性结论）
        REFERENCED_DEEP_CACHE.put(entityType, found);
        return found;
    }

    /** 血量写入口探针用的可达类集合：模组类之外还包含伪装成 {@code net.minecraft.*} 的核心库 */
    static List<Class<?>> referencedWriterClasses(Class<?> entityType) {
        return referencedClasses(entityType, true, WRITER_REFERENCE_MAX_DEPTH, WRITER_REFERENCE_MAX_CLASSES);
    }

    private static List<Class<?>> referencedClasses(Class<?> entityType, boolean enginePackages,
                                                    int maxDepth, int maxClasses) {
        List<Class<?>> found = new ArrayList<>();
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        ClassLoader loader = entityType.getClassLoader();
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
                // 先确认类文件资源存在再加载：对不存在的名字直接跳过，避免 Class.forName 抛异常填栈（BFS 是最热路径）
                Class<?> referenced = loadSeedClass(name, loader);
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
    static boolean refusesReferenced(boolean enginePackages, String binaryName) {
        if (isUnsafeToInvoke(binaryName)) return true;
        return !isModClassName(binaryName) && (!enginePackages || isNotEnginePackageName(binaryName));
    }

    /** 名称落在原版 / Forge / Mojang 前缀里的类 */
    static boolean isNotEnginePackageName(String name) {
        return !name.startsWith("net.minecraft.") && !name.startsWith("net.minecraftforge.")
                && !name.startsWith("com.mojang.");
    }

    /** 反射调用时禁止触碰的类前缀（如 LWJGL / blaze3d） */
    static boolean isUnsafeToInvoke(String binaryName) {
        return binaryName.startsWith("org.lwjgl.") || binaryName.startsWith("com.mojang.blaze3d.");
    }

    /** 类文件常量池里列出的被引用类名（内部名）；解析失败返回空列表 */
    private static List<String> referencedClasses(Class<?> type) {
        List<String> cached = REFERENCED_CLASS_CACHE.get(type);
        if (cached != null) return cached;
        // 优先取运行时常量池引用（Mixin/CoreMod/Agent 变换后的真实视图），池不可用时才降级到 jar 字节解析（变换前）。
        List<String> pool = poolReferencedClasses(type);
        if (pool != null) {
            REFERENCED_CLASS_CACHE.put(type, pool);
            return pool;
        }
        if (REFERENCE_READ_NEGATIVE.blocked(type)) return List.of();
        // 不用 computeIfAbsent：读字节 + 解析常量池耗时，占着 bin 锁会阻塞攻击路径
        byte[] bytes = readClassBytes(type);
        if (bytes == null) {
            // 读不到字节（临时 IO 失败 / 命名模块封装下的 getResourceAsStream 返回 null）：
            // 结论不可信，绝不固化成「该类没有引用类」——那会让档 3 的静态表与写入口对该类永久失效
            REFERENCE_READ_NEGATIVE.mark(type);
            return List.of();
        }
        List<String> parsed = parseReferencedClasses(bytes);
        REFERENCED_CLASS_CACHE.put(type, parsed);
        return parsed;
    }

    /** 读取类文件字节；取不到时该探针整体跳过。
     *  隐藏类必须沿父类链回退：coremod / ASM 运行期 defineHiddenClass 出的子类（名字含 .../0x...）
     *  或纯 ASM 生成的实体类磁盘上没有字节码资源，getResourceAsStream 恒返回 null，常量池解析、
     *  静态表查找、写入口候选会全部落空；它们继承的普通类通常可读，血量仓库的引用一般就在父类里。 */
    private static byte[] readClassBytes(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            // 名字里的 "/0x..." 是隐藏类后缀，不是包名的一部分，必须剥掉再当资源名用
            String name = current.getName();
            int hidden = name.indexOf("/0x");
            String binary = hidden < 0 ? name : name.substring(0, hidden);
            String path = binary.replace('.', '/') + ".class";
            byte[] bytes = readResource(current.getResourceAsStream("/" + path));
            if (bytes == null && loader != null) {
                bytes = readResource(loader.getResourceAsStream(path));
            }
            if (bytes == null && context != null && context != loader) {
                bytes = readResource(context.getResourceAsStream(path));
            }
            if (bytes != null) return bytes;
        }
        return null;
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

    /** 常量池解析的按线程复用缓冲，避免每个 class 都新分配两个大数组 */
    private static final ThreadLocal<PoolScratch> POOL_SCRATCH = new ThreadLocal<>();

    /** 复用缓冲的容量上限：超过就不复用，避免常驻线程永久保留超大数组（可达 MB 级） */
    private static final int POOL_SCRATCH_MAX = 8192;

    /** 一次常量池解析使用的两个数组（可能来自线程复用缓冲，也可能是一次性临时数组） */
    record PoolArrays(String[] utf8, int[] classNames) {
    }

    static final class PoolScratch {
        private String[] utf8 = new String[1];
        private int[] classNames = new int[1];

        /** 准备本次解析的数组：容量不足时新分配（天然零化），容量足够时清零本次用到的区间；
         *  超过 POOL_SCRATCH_MAX 时返回一次性临时数组，不写回本对象（避免常驻线程永久持有）。 */
        private PoolArrays prepare(int poolSize) {
            if (poolSize > POOL_SCRATCH_MAX) {
                return new PoolArrays(new String[poolSize], new int[poolSize]);
            }
            if (utf8.length < poolSize) {
                utf8 = new String[poolSize];
                classNames = new int[poolSize];
            } else {
                Arrays.fill(utf8, 0, poolSize, null);
                Arrays.fill(classNames, 0, poolSize, 0);
            }
            return new PoolArrays(utf8, classNames);
        }
    }

    /** 常量池解析：{@code collectStringNames} 为真时还收集类名形态的 {@code Utf8} 字符串常量 */
    static List<String> parseClassNames(byte[] bytes, boolean collectStringNames) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int poolSize = in.readUnsignedShort();
            PoolScratch scratch = POOL_SCRATCH.get();
            if (scratch == null) {
                scratch = new PoolScratch();
                POOL_SCRATCH.set(scratch);
            }
            PoolArrays arrays = scratch.prepare(poolSize);
            String[] utf8 = arrays.utf8();
            int[] classNames = arrays.classNames();
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
            // 复用数组容量可能大于本次 poolSize，必须按本次范围遍历（超出部分是上次遗留，不读）
            for (int i = 1; i < poolSize; i++) {
                int index = classNames[i];
                if (index > 0 && index < poolSize && utf8[index] != null) names.add(utf8[index]);
            }
            if (stringNames != null) names.addAll(stringNames);
            return names;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** 值是否形如 JVM 内部类名（{@code a/b/C$D}）；常量池指纹层共用同一判定 */
    static boolean looksLikeInternalClassName(String value) {
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

    /** 按内部名加载一个种子类；失败返回 null（只加载不初始化）。
     *  不做「类文件资源存在性预检查」：有些模组（如 MBT）的核心类不落盘 .class，由自带 agent / 载荷在运行时
     *  定义，资源查询恒为假但类加载器仍能定义它们，预检查会整体漏掉；不存在的名字只是返回 null。 */
    static Class<?> loadSeedClass(String internalName, ClassLoader loader) {
        return loadClass(internalName.replace('/', '.'), loader);
    }

    /** 类名加载失败计数：ClassLoader → (二进制类名 → 失败次数) */
    private static final Map<ClassLoader, Map<String, Integer>> CLASS_LOAD_FAILURES = new ConcurrentHashMap<>();

    /** 失败计数的窗口起点（纳秒） */
    private static volatile long classLoadWindowStart = System.nanoTime();

    /** 失败负缓存窗口长度。到期整体清空，运行期由 agent / 载荷注入的类最多 5 分钟就再获得一次加载机会（不落盘、不永久化）。 */
    private static final long CLASS_LOAD_WINDOW_NANOS = 300_000_000_000L;

    /** 同一个类名先失败多少次才进入负缓存（先给一次重试机会，避免误伤瞬时失败） */
    private static final int CLASS_LOAD_FAILED_MIN_COUNT = 2;

    /** 单个 ClassLoader 的失败名单上限；超过就整体清空（极端整合包也不至于无限增长） */
    private static final int CLASS_LOAD_FAILURE_MAX_ENTRIES = 65_536;

    private static void rotateClassLoadWindow() {
        long now = System.nanoTime();
        if (now - classLoadWindowStart <= CLASS_LOAD_WINDOW_NANOS) return;
        synchronized (CLASS_LOAD_FAILURES) {
            if (now - classLoadWindowStart <= CLASS_LOAD_WINDOW_NANOS) return;
            CLASS_LOAD_FAILURES.clear();
            classLoadWindowStart = now;
        }
    }

    /** 该名字是否已在退避窗口内（是则直接返回 null，不再吃一次填栈的 ClassNotFoundException） */
    private static boolean classLoadBackedOff(String binaryName, ClassLoader loader) {
        // ConcurrentHashMap 不接受 null 键；bootstrap 加载器（null）不参与负缓存
        if (loader == null) return false;
        rotateClassLoadWindow();
        Map<String, Integer> failures = CLASS_LOAD_FAILURES.get(loader);
        if (failures == null) return false;
        Integer count = failures.get(binaryName);
        return count != null && count >= CLASS_LOAD_FAILED_MIN_COUNT;
    }

    private static void rememberClassLoadFailure(String binaryName, ClassLoader loader) {
        if (loader == null) return;
        rotateClassLoadWindow();
        // 不用 computeIfAbsent 里的 return 值做二次旋转：清空只发生在窗口到期时，且上面已经转过一次
        Map<String, Integer> failures = CLASS_LOAD_FAILURES.computeIfAbsent(
                loader, key -> new ConcurrentHashMap<>());
        if (failures.size() >= CLASS_LOAD_FAILURE_MAX_ENTRIES) failures.clear();
        failures.merge(binaryName, 1, Integer::sum);
    }

    /** 只加载不初始化；失败返回 null。
     *  只对已失败 CLASS_LOAD_FAILED_MIN_COUNT 次的名字做长退避，绝不恢复「类文件资源存在性预检查」：MBT 形态的运行时 defineClass 类没有资源也不在任何池里，预检查会整体漏掉。 */
    private static Class<?> loadClass(String binaryName, ClassLoader loader) {
        if (classLoadBackedOff(binaryName, loader)) return null;
        try {
            return Class.forName(binaryName, false, loader);
        } catch (Throwable ignored) {
            rememberClassLoadFailure(binaryName, loader);
            return null;
        }
    }

    record StaticMethodSig(String name, String descriptor) {
    }

    private static final Map<Class<?>, List<StaticMethodSig>> CLASS_METHOD_TABLE_CACHE = new ConcurrentHashMap<>();

    static List<StaticMethodSig> bytecodeStaticMethods(Class<?> clazz) {
        return computeOutsideLock(CLASS_METHOD_TABLE_CACHE, clazz, () -> {
            byte[] bytes = readClassBytes(clazz);
            return bytes == null ? List.of() : parseStaticMethods(bytes);
        });
    }

    /** 解析 class 文件，取出全部 {@code public static} 方法的 名字 + 描述符 */
    private static List<StaticMethodSig> parseStaticMethods(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int poolSize = in.readUnsignedShort();
            String[] utf8 = new String[poolSize];
            for (int i = 1; i < poolSize; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
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
            in.skipBytes(6); // access_flags + this_class + super_class
            int interfaces = in.readUnsignedShort();
            in.skipBytes(interfaces * 2);
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) skipClassMember(in);
            int methods = in.readUnsignedShort();
            List<StaticMethodSig> result = new ArrayList<>();
            for (int i = 0; i < methods; i++) {
                int access = in.readUnsignedShort();
                int nameIndex = in.readUnsignedShort();
                int descIndex = in.readUnsignedShort();
                skipClassAttributes(in);
                // 只要 public static，且排除 native（ACC_NATIVE = 0x0100）：
                // 在非预期线程上调用 native 绑定是进程级 abort，反射的 try/catch 兜不住（交接文档 §5.4）
                if ((access & 0x0001) == 0 || (access & 0x0008) == 0 || (access & 0x0100) != 0) continue;
                String name = nameIndex > 0 && nameIndex < poolSize ? utf8[nameIndex] : null;
                String desc = descIndex > 0 && descIndex < poolSize ? utf8[descIndex] : null;
                if (name != null && desc != null) result.add(new StaticMethodSig(name, desc));
            }
            return result;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static void skipClassMember(DataInputStream in) throws Exception {
        in.skipBytes(6); // access_flags + name_index + descriptor_index
        skipClassAttributes(in);
    }

    private static void skipClassAttributes(DataInputStream in) throws Exception {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(2);            // attribute_name_index
            int length = in.readInt();  // attribute_length
            in.skipBytes(length);
        }
    }

    /** 解析方法描述符里的形参类型；含无法加载的类型时返回 null */
    static Class<?>[] parseParameterTypes(String descriptor, ClassLoader loader) {
        try {
            int[] cursor = {1}; // 跳过 '('
            List<Class<?>> params = new ArrayList<>();
            while (descriptor.charAt(cursor[0]) != ')') {
                Class<?> type = parseFieldType(descriptor, cursor, loader);
                if (type == null || type == void.class) return null;
                params.add(type);
            }
            return params.toArray(new Class<?>[0]);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 解析方法描述符里的返回类型；含无法加载的类型时返回 null */
    static Class<?> parseReturnType(String descriptor, ClassLoader loader) {
        try {
            int close = descriptor.indexOf(')');
            if (close < 0) return null;
            int[] cursor = {close + 1};
            return parseFieldType(descriptor, cursor, loader);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 解析单个字段类型（含数组），推进游标 */
    private static Class<?> parseFieldType(String descriptor, int[] cursor, ClassLoader loader) throws Throwable {
        char c = descriptor.charAt(cursor[0]);
        switch (c) {
            case 'V': cursor[0]++; return void.class;
            case 'Z': cursor[0]++; return boolean.class;
            case 'B': cursor[0]++; return byte.class;
            case 'C': cursor[0]++; return char.class;
            case 'S': cursor[0]++; return short.class;
            case 'I': cursor[0]++; return int.class;
            case 'J': cursor[0]++; return long.class;
            case 'F': cursor[0]++; return float.class;
            case 'D': cursor[0]++; return double.class;
            case 'L': {
                int end = descriptor.indexOf(';', cursor[0]);
                String name = descriptor.substring(cursor[0] + 1, end).replace('/', '.');
                cursor[0] = end + 1;
                return loadClass(name, loader);
            }
            case '[': {
                int start = cursor[0];
                cursor[0]++;
                if (parseFieldType(descriptor, cursor, loader) == null) return null;
                return Class.forName(descriptor.substring(start, cursor[0]).replace('/', '.'), false, loader);
            }
            default:
                return null;
        }
    }

    /** 反射过滤解除所需的句柄集合（不可变，一次性解析后经 volatile 发布） */
    record FilterHandles(VarHandle methodFilter, VarHandle fieldFilter, VarHandle reflectionData) {
        boolean usable() {
            return methodFilter != null || fieldFilter != null;
        }
    }

    /** {@code null} 表示尚未解析；句柄取不到时发布一个句柄全为 {@code null} 的实例 */
    private static volatile FilterHandles filterHandles;

    private static final Object FILTER_HANDLES_LOCK = new Object();

    static MethodHandles.Lookup implLookup;

    private static final Set<Class<?>> FILTERS_LIFTED = ConcurrentHashMap.newKeySet();

    /** 把某个类从 JDK 反射黑名单里摘掉，并清掉其反射缓存（幂等；失败时静默，不影响其它层） */
    @SuppressWarnings("CatchMayIgnoreException")
    static void liftReflectiveFilters(Class<?> clazz) {
        if (clazz == null || FILTERS_LIFTED.contains(clazz)) return;
        // 先确认句柄真的可用再记账：原来的「先置标志后解析」会让「标记成已摘除、实际没摘」永久固化。
        FilterHandles handles = filterHandles();
        if (!handles.usable()) return;
        if (!FILTERS_LIFTED.add(clazz)) return;
        try {
            boolean methods = removeFilterEntry(handles.methodFilter(), clazz);
            boolean fields = removeFilterEntry(handles.fieldFilter(), clazz);
            if (!methods && !fields) return;
            // 把该缓存置空强制重算。
            boolean cleared = clearReflectionData(handles.reflectionData(), clazz);
            probeLog("反射过滤解除：{} 方法条目={} 字段条目={} 缓存重置={}", clazz.getName(), methods, fields, cleared);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
    }

    /** 双重检查 + volatile 发布：避免「已置标志但字段仍为 null」导致永久无法解除过滤 */
    private static FilterHandles filterHandles() {
        FilterHandles cached = filterHandles;
        if (cached != null) return cached;
        synchronized (FILTER_HANDLES_LOCK) {
            if (filterHandles == null) filterHandles = resolveFilterHandles();
            return filterHandles;
        }
    }

    static FilterHandles resolveFilterHandles() {
        MethodHandles.Lookup lookup = implLookup();
        if (lookup == null) {
            probeLog("反射过滤解除：IMPL_LOOKUP 取不到，无法解除");
            return new FilterHandles(null, null, null);
        }
        try {
            Class<?> reflection = Class.forName("jdk.internal.reflect.Reflection");
            return new FilterHandles(
                    lookup.findStaticVarHandle(reflection, "methodFilterMap", Map.class),
                    lookup.findStaticVarHandle(reflection, "fieldFilterMap", Map.class),
                    lookup.findVarHandle(Class.class, "reflectionData", SoftReference.class));
        } catch (Throwable t) {
            rethrowIfFatal(t);
            probeLog("反射过滤解除：找不到过滤表/缓存句柄（{}）", t.toString());
            return new FilterHandles(null, null, null);
        }
    }

    /** 置空该类在 {@code Class} 里的反射缓存，使下次 getDeclaredMethods/Fields 重新计算（此时已不含过滤条目） */
    static boolean clearReflectionData(VarHandle reflectionData, Class<?> clazz) {
        if (reflectionData == null) return false;
        try {
            reflectionData.set(clazz, null);
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 返回是否真的从表里移除了该类的条目 */
    @SuppressWarnings("unchecked")
    static boolean removeFilterEntry(VarHandle handle, Class<?> clazz) {
        if (handle == null) return false;
        try {
            Object value = handle.get();
            if (!(value instanceof Map<?, ?> raw) || !raw.containsKey(clazz)) return false;
            Map<Class<?>, Set<String>> map = (Map<Class<?>, Set<String>>) raw;
            try {
                // 原地 remove 与 JDK registerMethodsToFilter 的并发语义一致；整表复制回写会丢并发更新。
                map.remove(clazz);
                return true;
            } catch (UnsupportedOperationException immutable) {
                // 表不可变时退回「复制后整体替换」
                Map<Class<?>, Set<String>> copy = new HashMap<>(map);
                copy.remove(clazz);
                handle.set(copy);
                return true;
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** {@code MethodHandles.Lookup.IMPL_LOOKUP}：不受模块开放与反射过滤限制，用于摘除黑名单 */
    @SuppressWarnings("CatchMayIgnoreException")
    static MethodHandles.Lookup implLookup() {
        if (implLookup != null) return implLookup;
        Field field;
        try {
            field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
        Unsafe instance = getUnsafe();
        if (instance != null) {
            try {
                Object base = instance.staticFieldBase(field);
                long offset = instance.staticFieldOffset(field);
                implLookup = (MethodHandles.Lookup) instance.getObject(base, offset);
                return implLookup;
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }
        try {
            field.setAccessible(true);
            implLookup = (MethodHandles.Lookup) field.get(null);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        return implLookup;
    }

    /** 反射调用前必须排除的成员：native 方法。
     *  在非预期线程上调用 native 绑定是进程级 abort（不是 Java 异常，try/catch 兜不住），交接文档 §5.4 有
     *  一次真实事故（LWJGL 在 Server 线程被调用直接退出）；模组不可能把血量写入口做成 native。 */
    static boolean isNativeMember(Method method) {
        if (method == null) return true;
        // Modifier.getModifiers() 不抛受检异常，也不调用第三方代码：这里不需要 try/catch
        return Modifier.isNative(method.getModifiers());
    }

    /** 类的公开静态方法（非合成、非 native） */
    static List<Method> publicStaticMethods(Class<?> clazz) {
        return computeOutsideLock(PUBLIC_STATIC_METHOD_CACHE, clazz, () -> collectPublicStaticMethods(clazz));
    }

    private static List<Method> collectPublicStaticMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Method[] declared;
        try {
            declared = declaredMethodsOf(clazz);
        } catch (Throwable ignored) {
            return result;
        }
        for (Method method : declared) {
            int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && !method.isSynthetic()
                    && !Modifier.isNative(modifiers)) {
                result.add(method);
            }
        }
        return result;
    }

    /** 持有对象上的模组读方法：非 static、返回 float/double、参数不超过 2 个 */
    static List<Method> numericReturningMethods(Class<?> clazz) {
        return computeOutsideLock(NUMERIC_RETURNING_METHOD_CACHE, clazz, () -> collectNumericReturningMethods(clazz));
    }

    static List<Method> collectNumericReturningMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (!isModClass(current)) continue;
            for (Method method : declaredMethodsOf(current)) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers()) || isNativeMember(method)) continue;
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
    static List<Method> singleNumberSetters(Class<?> clazz) {
        return computeOutsideLock(SINGLE_NUMBER_SETTER_CACHE, clazz, () -> collectSingleNumberSetters(clazz));
    }

    static List<Method> collectSingleNumberSetters(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            if (!isModClass(current)) continue;
            for (Method method : declaredMethodsOf(current)) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers()) || isNativeMember(method)) continue;
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

    /** 继承链上第一个类型匹配的字段（含 static/final，如实体持有的密钥表） */
    static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> current : hierarchy(owner)) {
            for (Field field : declaredFieldsOf(current)) {
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

    /** 调用「单参 float/double」方法（实例方法，target 为持有对象）。
     *  hint 是与该写口配对的读口返回类型：写方法的数值形参若被擦除（Object/Number）就按读口类型装箱；猜成
     *  Float/Double 写进模组内部存 Integer 的槽位会让它之后强转时抛 ClassCastException，判定不出来时宁可不调用。 */
    static boolean invokeInstanceWithNumber(Method method, Object target, double value) {
        return invokeInstanceWithNumber(method, target, value, null);
    }

    static boolean invokeInstanceWithNumber(Method method, Object target, double value, Class<?> hint) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1) return false;
        Class<?> boxType = params[0];
        if (ModWriterProbe.isErasedNumericParam(boxType)) boxType = hint;
        if (boxType == null || ModWriterProbe.isErasedNumericParam(boxType)) {
            // 类型无法判定：不写，避免把强类型槽位的运行期类型写坏
            return false;
        }
        try {
            method.invoke(target, boxNumber(boxType, value));
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 单例容器字段 / 单例访问器扫描的字段数上限（与影子层一致） */
    private static final int CONTAINER_FIELDS_MAX = 32;

    /** 静态单例访问器缓存（按类，含空结果） */
    private static final Map<Class<?>, List<Object[]>> SINGLETON_ACCESSOR_CACHE = new ConcurrentHashMap<>();

    /** 容器字段缓存的键：持有类 × 容器接口 */
    private record ContainerFieldsKey(Class<?> owner, Class<?> kind) {
    }

    /** 容器字段缓存（按 持有类 × 容器接口，含空结果） */
    private static final Map<ContainerFieldsKey, List<Field>> CONTAINER_FIELDS_CACHE = new ConcurrentHashMap<>();

    /** 静态单例访问器：静态字段（类型可实现本类）或公开静态无参工厂（返回本类）；含类级缓存与逐类隔离。 */
    static List<Object[]> singletonAccessors(Class<?> clazz) {
        if (clazz == null) return List.of();
        return computeOutsideLock(SINGLETON_ACCESSOR_CACHE, clazz, () -> collectSingletonAccessors(clazz));
    }

    private static List<Object[]> collectSingletonAccessors(Class<?> clazz) {
        List<Object[]> accessors = new ArrayList<>();
        for (Field field : declaredFieldsOf(clazz)) {
            if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) continue;
            if (!clazz.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            accessors.add(new Object[]{field, null});
        }
        for (Method method : declaredMethodsOf(clazz)) {
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
        return List.copyOf(accessors);
    }

    /** 取静态单例实例；取不到返回 null */
    static Object resolveSingleton(Field field, Method factory) {
        try {
            return field != null ? field.get(null) : factory != null ? factory.invoke(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 类继承链上指定容器类型的实例字段（已 setAccessible），数量有上限。 */
    static List<Field> containerFields(Class<?> clazz, Class<?> kind) {
        if (clazz == null || kind == null) return List.of();
        return computeOutsideLock(CONTAINER_FIELDS_CACHE, new ContainerFieldsKey(clazz, kind),
                () -> collectContainerFields(clazz, kind));
    }

    private static List<Field> collectContainerFields(Class<?> clazz, Class<?> kind) {
        List<Field> found = new ArrayList<>();
        for (Class<?> current : hierarchy(clazz)) {
            for (Field field : declaredFieldsOf(current)) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                if (!kind.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                found.add(field);
                if (found.size() >= CONTAINER_FIELDS_MAX) return List.copyOf(found);
            }
        }
        return List.copyOf(found);
    }

    /** 类继承链（本类 → 父类，不含 {@code Object}），按类缓存 */
    static List<Class<?>> hierarchy(Class<?> clazz) {
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
    static final Object[] NO_ARGS = new Object[0];

    /** 反射调用并返回结果：静态方法 {@code target} 传 null；失败或返回 void 时返回 null */
    static Object invokeObject(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    static boolean invokeQuietly(Method method, Object[] args) {
        try {
            method.invoke(null, args);
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 反射调用取数值：失败或返回值不是数值时返回 null */
    static Double invokeForNumber(Method method, Object target, Object[] args) {
        return invokeObject(method, target, args) instanceof Number number ? number.doubleValue() : null;
    }

    /** 可调用的静态血量入口：统一反射 Method 与 MethodHandle 两条路径；后者用于成员被 JDK 反射过滤（getDeclaredMethods 看不到）又无法解除过滤时的兜底（G4）。 */
    interface HealthCallable {
        String name();

        Class<?>[] parameterTypes();

        Class<?> returnType();

        Object invoke(Object[] args) throws Throwable;
    }

    /** 反射实现：静态方法以 {@code null} 目标调用 */
    record ReflectCallable(Method method) implements HealthCallable {
        @Override public String name() { return method.getName(); }
        @Override public Class<?>[] parameterTypes() { return method.getParameterTypes(); }
        @Override public Class<?> returnType() { return method.getReturnType(); }
        @Override public Object invoke(Object[] args) throws Throwable { return method.invoke(null, args); }
    }

    /** MethodHandle 实现：成员来自 class 文件方法表枚举，不受反射过滤约束 */
    record HandleCallable(String name, MethodHandle handle, Class<?>[] parameterTypes,
                                  Class<?> returnType) implements HealthCallable {
        @Override public Object invoke(Object[] args) throws Throwable { return handle.invokeWithArguments(args); }
    }

    /** 绑定到具体容器实例的调用入口（自定义容器写口用） */
    record BoundCallable(Object target, Method method) implements HealthCallable {
        @Override public String name() { return method.getName(); }
        @Override public Class<?>[] parameterTypes() { return method.getParameterTypes(); }
        @Override public Class<?> returnType() { return method.getReturnType(); }
        @Override public Object invoke(Object[] args) throws Throwable { return method.invoke(target, args); }
    }

    /** 调用 {@link HealthCallable}（反射或 MethodHandle 皆可）；失败返回 false */
    static boolean invokeCallableQuietly(HealthCallable callable, Object[] args) {
        try {
            callable.invoke(args);
            return true;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return false;
        }
    }

    /** 调用 {@link ModWriterProbe.HealthCallable} 并取数值结果；失败或非数值返回 null */
    static Double invokeCallableForNumber(HealthCallable callable, Object[] args) {
        try {
            return callable.invoke(args) instanceof Number number ? number.doubleValue() : null;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 深度 3 可达模组类 + 嵌套类（「代管血量核心类」的廉价候选来源） */
    static List<Class<?>> referencedDeepNestedClasses(Class<?> entityType) {
        List<Class<?>> cached = REFERENCED_DEEP_NESTED_CACHE.get(entityType);
        if (cached != null) return cached;
        List<Class<?>> found = withNestedClasses(referencedModClassesDeep(entityType), REFERENCE_DEEP_NESTED_MAX);
        REFERENCED_DEEP_NESTED_CACHE.put(entityType, found);
        return found;
    }

    static Object readFieldObject(Field field, Object target) {
        try {
            return field.get(target);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 条目类继承链上“名为 value 的非 final 引用字段”的偏移（按类缓存一次） */
    static OptionalLong entryValueOffset(Class<?> entryClass) {
        // 用 computeOutsideLock：反射扫描不能占着 ConcurrentHashMap 的 bin 锁做
        return computeOutsideLock(ENTRY_VALUE_OFFSET_CACHE, entryClass, () -> {
            Unsafe instance = getUnsafe();
            if (instance == null) return OptionalLong.empty();
            for (Class<?> current : hierarchy(entryClass)) {
                for (Field field : declaredFieldsOf(current)) {
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
        UnsafeRef cached = unsafeRef;
        if (cached != null) return cached.value();
        synchronized (UNSAFE_LOCK) {
            if (unsafeRef == null) unsafeRef = new UnsafeRef(resolveUnsafe());
            return unsafeRef.value();
        }
    }

    private static Unsafe resolveUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception ignored) {
            // 取不到 Unsafe 时不影响其它路径。只捕获 Exception（OOM、StackOverflow 等 Error 必须继续传播）。
            return null;
        }
    }

    /** 直接写对象字段偏移处（不走反射 set，绕开 final / 访问检查） */
    static void unsafePutObject(Object holder, long offset, Object value) {
        Unsafe instance = getUnsafe();
        if (instance == null) return;
        try {
            instance.putObject(holder, offset, value);
        } catch (Throwable ignored) {
        }
    }

    static Object unsafeGetObject(Object holder, long offset) {
        Unsafe instance = getUnsafe();
        if (instance == null) return null;
        try {
            return instance.getObject(holder, offset);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 类文件常量池里的字符串字面量缓存（算子写键的候选来源） */
    private static final Map<Class<?>, List<String>> STRING_CONSTANT_CACHE = new ConcurrentHashMap<>();

    /** 读实例字段：先常规反射，失败再走 Unsafe 字段偏移 */
    static Object readFieldAny(Field field, Object target) {
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
    static Object readStaticFieldAny(Field field) {
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

    /** 类文件常量池里的字符串字面量（{@code CONSTANT_String_info} → Utf8）；解析失败返回空列表 */
    static List<String> stringConstants(Class<?> type) {
        return computeOutsideLock(STRING_CONSTANT_CACHE, type, () -> {
            byte[] bytes = readClassBytes(type);
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

    /** {@code AccessibleObject.override} 是否已打开 */
    private static boolean accessOverrideOpened;

    static Field[] declaredFieldsOf(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return new Field[0];
        }
    }

    /** 安全取声明方法：候选类可能引用本包没装的模组，解析方法表会抛 NoClassDefFoundError；此时返回空数组 = 「这个类扫不了」，而不是让整个候选收集失败。 */
    static Method[] declaredMethodsOf(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return new Method[0];
        }
    }

    static Object readFieldQuietly(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return null;
        }
    }

    /** 打开成员访问权限，打不开时返回 true */
    static boolean cannotAccess(AccessibleObject member) {
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
    static boolean cannotAccessAll(List<Method> methods) {
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

package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.registries.ForgeRegistries;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.fu_meng.more_attributes.truedamage.ReflectionToolkit.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/**
 * 战斗外预热与模组文件收割：预热只针对「覆写了 getHealth」的模组实体类，启动时按注册表枚举、进场时按实体类型触发，全部在单线程、最低优先级的后台线程执行，绝不阻塞服务端 tick。
 * 同时负责模组 jar 的种子收割与「代管血量」核心类的候选来源（影子层与权威层共用）。
 */
@Mod.EventBusSubscriber
public final class TrueDamageWarmup {

    /** 待预热的实体类（进场登记；结果按类缓存，同类只做一次） */
    private static final Set<Class<?>> PENDING_WARMUP = ConcurrentHashMap.newKeySet();

    /** 已判定过「是否覆写了 getHealth」的实体类（该结论按类稳定，避免每次进场重扫方法表） */
    private static final Set<Class<?>> SPECIAL_HEALTH_CHECKED = ConcurrentHashMap.newKeySet();

    /** 已预热过的实体类（发现结果按类缓存，同类只做一次） */
    private static final Set<Class<?>> WARMED_CLASSES = ConcurrentHashMap.newKeySet();

    /** 预热专用线程：单线程、daemon、最低优先级；只跑只读发现，绝不阻塞服务端 tick */
    private static final ExecutorService WARMUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "more_attributes-warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** 是否已做过一次性 engine 类缓存预热 */
    private static volatile boolean ENGINE_CACHE_PREWARMED;

    @SuppressWarnings("CatchMayIgnoreException")
    /* 一次性预热写入口候选，BFS 共享的 engine 类字节/嵌套类缓存。 */
    static void prewarmEngineCacheOnce() {
        if (ENGINE_CACHE_PREWARMED) return;
        if (!MAConfig.trueDamageMode.needsWarmup()) return;
        ENGINE_CACHE_PREWARMED = true;
        WARMUP_EXECUTOR.execute(() -> {
            try {
                // L0.3：首次启动时在后台采样一份原版常量池基线（落盘供后续差集）；失败静默
                VanillaBaseline.bootstrapIfAbsent();
                long t0 = System.nanoTime();
                writerCandidateClasses(LivingEntity.class);
                if (MAConfig.debugProbes) {
                    probeLog("engine 类缓存预热完成 (ms)：{}", (System.nanoTime() - t0) / 1_000_000L);
                }
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        });
    }

    /**
     * 服务端启动后，把「注册表里所有模组实体类」的发现一次性丢到后台算完。
     * 只靠 {@link #onEntityJoinLevel} 预热不够：运行期把档位切到 3/4 时，存档里已经存在的实体类从未走过进场预热，第一次被攻击就会走完整冷启动路径。
     * 启动时按注册表枚举可覆盖这种情况（枚举的是 EntityType.getBaseClass()，不会触发类的静态初始化）。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!MAConfig.trueDamageMode.needsWarmup()) return;
        WARMUP_EXECUTOR.execute(TrueDamageWarmup::prewarmRegisteredEntityClasses);
    }

    /** 启动预热一次最多处理多少个实体类（避免大型整合包把 CPU / metaspace 拖爆） */
    private static final int PREWARM_MAX_CLASSES = 512;

    @SuppressWarnings("CatchMayIgnoreException")
    static void prewarmRegisteredEntityClasses() {
        List<Class<?>> classes = new ArrayList<>();
        Map<Class<?>, Path> pathByClass = new HashMap<>();
        Map<String, Path> pathByNamespace = new HashMap<>();
        int skipped = 0;
        try {
            for (EntityType<?> entityType : ForgeRegistries.ENTITY_TYPES.getValues()) {
                Class<?> base = entityType.getBaseClass();
                if (!LivingEntity.class.isAssignableFrom(base) || base == Player.class) continue;
                if (isVanillaClass(base) || pathByClass.containsKey(base)) continue;
                if (classes.size() >= PREWARM_MAX_CLASSES) {
                    skipped++;
                    continue;
                }
                ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entityType);
                Path modPath = key == null ? null : pathByNamespace.computeIfAbsent(
                        key.getNamespace(), TrueDamageWarmup::modFilePathOfNamespace);
                classes.add(base);
                pathByClass.put(base, modPath);
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return;
        }
        long t0 = System.nanoTime();
        for (Class<?> base : classes) {
            try {
                if (!SPECIAL_HEALTH_CHECKED.add(base)) continue;
                if (!overridesSpecialHealthMethods(base)) continue;
                warmUpDiscovery(base, pathByClass.get(base), base.getClassLoader());
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        }
        if (MAConfig.debugProbes) {
            probeLog("启动预热完成：实体类 {} 个（超出上限跳过 {} 个），耗时 {} ms",
                    classes.size(), skipped, (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity living) || living instanceof Player) return;
        Class<?> type = living.getClass();
        enqueuePreDiscovery(living, type);
        if (!MAConfig.trueDamageMode.needsWarmup()) return;
        if (WARMED_CLASSES.contains(type)) return;
        if (!SPECIAL_HEALTH_CHECKED.add(type)) return;
        // 只预热「覆写了 getHealth」的特殊实体：普通生物（含原版）不预热、不入队、不记日志
        if (!overridesSpecialHealthMethods(type)) return;
        if (!PENDING_WARMUP.add(type)) return;
        // 模组归属在主线程解析（读注册表 / ModList），后台只拿 Class + Path + 类加载器
        Path modPath = modFilePath(living);
        ClassLoader loader = type.getClassLoader();
        WARMUP_EXECUTOR.execute(() -> warmUpDiscovery(type, modPath, loader));
    }


    // T12：首击前置发现（实体进入世界后的下一个服务端 tick）
    /** 每 tick 最多提前发现多少个实体类（硬编码限流：世界加载时数百实体类涌入也不至于拖死某一 tick） */
    private static final int PRE_DISCOVER_MAX_CLASSES_PER_TICK = 2;

    /** 准入检查失败时最多重试多少 tick，之后放弃（放弃后仍走首次命中的现场发现——安全网，不是降级） */
    private static final int PRE_DISCOVER_MAX_RETRY_TICKS = 20;

    /** 已经做过提前发现的实体类（按类一次性闩锁） */
    private static final Set<Class<?>> PRE_DISCOVERED = ConcurrentHashMap.newKeySet();

    /** 正在提前发现的实体类（防重入） */
    private static final Set<Class<?>> PRE_DISCOVERING = ConcurrentHashMap.newKeySet();

    /**
     * 待提前发现的实体类 → 首个实例的弱引用 + 时间戳；只保留 Class 与弱引用，处理完即丢，绝不让队列长期持有实体引用（防区块/实体泄漏）。
     */
    private static final Map<Class<?>, PendingPreDiscover> PENDING_PRE_DISCOVER = new ConcurrentHashMap<>();

    private record PendingPreDiscover(WeakReference<LivingEntity> entity, long enqueueTick, long deadlineTick) {
    }

    /** 入队：只在档 3/4、非原版类、且该类尚未做过时入队；join 当 tick 不执行 */
    private static void enqueuePreDiscovery(LivingEntity living, Class<?> type) {
        if (!MAConfig.trueDamageMode.preDiscoversOnJoin()) return;
        if (!isModClass(type) || type.isInterface() || type.isPrimitive() || type.isArray()) return;
        if (PRE_DISCOVERED.contains(type)) return;
        if (PENDING_PRE_DISCOVER.containsKey(type)) return;
        long now = SERVER_TICK.get();
        PENDING_PRE_DISCOVER.put(type, new PendingPreDiscover(
                new WeakReference<>(living), now, now + PRE_DISCOVER_MAX_RETRY_TICKS));
    }

    /**
     * 服务端 tick 末排出队列（由 StrongTrueDamageHelper.onServerTick 的 END 分支调用）。
     * 行为探针只能在服务端线程跑（native 调用错线程 = 进程级 abort），这正是本机制挂在 tick 而不是预热线程的原因。
     */
    static void drainPreDiscovery() {
        if (PENDING_PRE_DISCOVER.isEmpty()) return;
        if (!MAConfig.trueDamageMode.preDiscoversOnJoin()) {
            PENDING_PRE_DISCOVER.clear();
            return;
        }
        long now = SERVER_TICK.get();
        int processed = 0;
        for (Iterator<Map.Entry<Class<?>, PendingPreDiscover>> it =
             PENDING_PRE_DISCOVER.entrySet().iterator(); it.hasNext(); ) {
            if (processed >= PRE_DISCOVER_MAX_CLASSES_PER_TICK) break;
            Map.Entry<Class<?>, PendingPreDiscover> entry = it.next();
            PendingPreDiscover pending = entry.getValue();
            // 延后到下一服务端 tick：join 当 tick 实体可能尚未完成初始化 / 同步
            if (pending.enqueueTick() >= now) continue;
            LivingEntity victim = pending.entity().get();
            boolean usable = victim != null && !victim.isRemoved() && !victim.level().isClientSide
                    && healthOf(victim) > 0.0F;
            if (!usable) {
                if (victim == null || now >= pending.deadlineTick()) {
                    it.remove();
                }
                continue;
            }
            it.remove();
            if (!PRE_DISCOVERED.add(entry.getKey())) continue;
            processed++;
            runPreDiscovery(victim);
        }
    }

    /** 执行与级联同一批发现入口（按档位）；任一项失败只跳过该项 */
    static void runPreDiscovery(LivingEntity victim) {
        if (!PRE_DISCOVERING.add(victim.getClass())) return;
        long startNanos = System.nanoTime();
        try {
            MAConfig.TrueDamageMode mode = MAConfig.trueDamageMode;
            StrongTrueDamageHelper.attempt("提前发现-密文标定", victim,
                    () -> CipherHealthLayer.preCalibrateCipher(victim));
            StrongTrueDamageHelper.attempt("提前发现-类字段", victim,
                    () -> FieldHealthProbe.identifyHealthBackingFields(victim));
            StrongTrueDamageHelper.attempt("提前发现-外部授权表", victim,
                    () -> StaticAuthorityLayer.plans(victim));
            if (mode.searchesReferencedTables()) {
                StrongTrueDamageHelper.attempt("提前发现-静态表", victim,
                        () -> findReferencedTables(victim.getClass()));
            }
            if (mode.invokesHolderWriters()) {
                StrongTrueDamageHelper.attempt("提前发现-持有对象", victim,
                        () -> ModWriterProbe.holderPlans(victim));
            }
            if (mode.searchesModHealthCore()) {
                StrongTrueDamageHelper.attempt("提前发现-影子", victim,
                        () -> AuthorityHealthLayer.shadowPlans(victim));
                StrongTrueDamageHelper.attempt("提前发现-MAC 权威", victim,
                        () -> AuthorityHealthLayer.macAuthorityPlan(victim));
            }
            if (mode.searchesModHealthCore() || mode.correctsAuthorityAccumulator()) {
                StrongTrueDamageHelper.attempt("提前发现-模组权威", victim,
                        () -> AuthorityHealthLayer.authorityPlans(victim));
            }
            if (mode.invokesHealthWriters()) {
                StrongTrueDamageHelper.attempt("提前发现-模组写入口", victim,
                        () -> ModWriterProbe.healthWriters(victim));
            }
            if (mode.writesHiddenStateFrame()) {
                StrongTrueDamageHelper.attempt("提前发现-状态帧", victim,
                        () -> StateFrameLayer.preDiscoverStateFrame(victim));
            }
        } finally {
            PRE_DISCOVERING.remove(victim.getClass());
            if (MAConfig.debugProbes) {
                probeLog("提前发现完成：{} 耗时 {} ms",
                        victim.getClass().getName(), (System.nanoTime() - startNanos) / 1_000_000L);
            }
        }
    }

    // 预热：模组文件收割 + 常量池类展开 + 写入口候选（按类/按模组文件缓存）。
    @SuppressWarnings("CatchMayIgnoreException")
    static void warmUpDiscovery(Class<?> type, Path modPath, ClassLoader loader) {
        try {
            if (!WARMED_CLASSES.add(type)) return;
            long t0 = System.nanoTime();
            if (modPath != null) {
                warmSeedExpansion(modPath, loader, true);
            }
            long t1 = System.nanoTime();
            referencedNestedClasses(type);
            referencedModClassesDeep(type);
            referencedDeepNestedClasses(type);
            long t2 = System.nanoTime();
            // 「写入口 / 模组击杀口」的宽包名候选（深度 4 / 4096 类）只有完全体用得到，档 3 不预热
            if (MAConfig.trueDamageMode.invokesHealthWriters()) {
                referencedWriterClasses(type);
                writerCandidateClasses(type);
            }
            long t3 = System.nanoTime();
            // 诊断（默认关闭）：见 MAConfig.debugProbes
            if (MAConfig.debugProbes) {
                probeLog("预热耗时 {} (ms)：种子收割={} 嵌套类/深度={} 写入口候选={}",
                        type.getName(), (t1 - t0) / 1_000_000L, (t2 - t1) / 1_000_000L,
                        (t3 - t2) / 1_000_000L);
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
        } finally {
            PENDING_WARMUP.remove(type);
        }
    }

    /** 该实体类（沿「非原版」父类链）是否覆写了 getHealth。 */
    static boolean overridesSpecialHealthMethods(Class<?> type) {
        try {
            for (Class<?> current = type; current != null && isModClass(current); current = current.getSuperclass()) {
                for (Method method : declaredMethodsOf(current)) {
                    if (method.isSynthetic() || method.getParameterCount() != 0) continue;
                    String name = method.getName();
                    if ("getHealth".equals(name) || "m_133247_".equals(name)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 反射失败时保守按「特殊」处理，避免漏预热
            return true;
        }
        return false;
    }

    /** 模组文件路径 → 从该文件字节码里收割到的候选类名。文件只解析一次 */
    private static final Map<String, List<String>> MOD_SEED_NAME_CACHE = new ConcurrentHashMap<>();

    /** 模组文件路径 → 候选类及其嵌套类。空结果不缓存 */
    private static final Map<String, List<Class<?>>> MOD_SEED_EXPANSION_CACHE = new ConcurrentHashMap<>();

    /** 正在后台收割/展开的模组文件（single-flight：预热线程与攻击路径共用同一份结果） */
    private static final Set<String> SEED_EXPANSION_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 收割已经跑完（无论成功、为空还是失败）的模组文件：候选集此时才算「完整」 */
    private static final Set<String> SEED_HARVEST_READY = ConcurrentHashMap.newKeySet();

    /** 诊断（不参与发现本身）：每个模组文件只在收割结束时打印一次规模 */
    private static final Set<String> SEED_DIAGNOSED = ConcurrentHashMap.newKeySet();

    /** 诊断（不参与发现本身）：每个实体类只打印一次候选池规模 */
    private static final Set<Class<?>> CANDIDATE_DIAGNOSED = ConcurrentHashMap.newKeySet();

    /** 收割诊断里「相关类名采样」的条数上限 */
    private static final int SEED_DIAGNOSE_SAMPLE = 24;

    /** 收割/展开落空时的退避缓存（按文件路径） */
    static final NegativeCache<Object> SEED_EXPANSION_NEGATIVE =
            new NegativeCache<>("模组文件种子收割");

    /** 收割预算：读取的 class 字节总量 / 候选类名总数 / 目录递归深度 / 嵌套类展开上限 */
    private static final long MOD_SEED_MAX_BYTES = 64 * 1024 * 1024;

    private static final int MOD_SEED_MAX_CLASSES = 4096;

    private static final int MOD_SEED_MAX_DEPTH = 32;

    private static final int MOD_SEED_NESTED_MAX = 8192;

    /** 影子层 / 权威层共用的候选类集合：实体常量池可达类 + 模组文件收割到的类名 */
    record HealthCoreCandidates(List<Class<?>> classes, boolean complete) {
    }

    /*
      影子层 / 权威层共用的候选类集合：基础来源是「实体常量池深度 3 可达的模组类 + 它们的嵌套类」，纯 class 文件解析 + 类加载、有界（≤{@link ReflectionToolkit#REFERENCE_DEEP_NESTED_MAX} 个类）且不读模组文件，因此档 3 也能用。
      只有完全体（{@link TrueDamageMode#searchesModHealthCore()}）才额外收割实体所属模组的 jar 作为补充来源。
     */
    /** healthCoreCandidates 的缓存键：实体类 × 「模组文件收割是否已就绪」 */
    private record CandidateKey(Class<?> type, boolean harvestReady) {
    }

    /**
     * healthCoreCandidates 结果缓存（含空结果）。一次结算里影子 / MAC / 权威 / 注销口 / 密文 / 注册表共 6 个消费点各调一次，每次都要新建 LinkedHashSet + ArrayList（数千元素并集）。
     * 键里必须带「收割就绪」标志：收割未完成时的空结论是「还没跑到」，与「结构上就是没有」不是一回事，绝不能固化（与 I-2 同向）。
     */
    private static final Map<CandidateKey, HealthCoreCandidates> HEALTH_CORE_CANDIDATE_CACHE =
            new ConcurrentHashMap<>();

    static HealthCoreCandidates healthCoreCandidates(LivingEntity victim) {
        Class<?> victimType = victim.getClass();
        boolean complete = !MAConfig.trueDamageMode.searchesModHealthCore() || seedHarvestReady(victim);
        CandidateKey key = new CandidateKey(victimType, complete);
        HealthCoreCandidates cached = HEALTH_CORE_CANDIDATE_CACHE.get(key);
        if (cached != null) return cached;

        Set<Class<?>> candidates = new LinkedHashSet<>(referencedDeepNestedClasses(victimType));
        Path modPath = null;
        int seedCount = 0;
        if (MAConfig.trueDamageMode.searchesModHealthCore()) {
            modPath = modFilePath(victim);
            if (modPath != null) {
                List<Class<?>> seeds = seedClassExpansion(modPath, victimType.getClassLoader());
                candidates.addAll(seeds);
                seedCount = seeds.size();
            }
        }
        // 诊断（不改行为）：每实体类一次，用于判断「收割未跑完 / 收割到 0 类 / 路径不对 / 名字被过滤」
        if (MAConfig.debugProbes && CANDIDATE_DIAGNOSED.add(victimType)) {
            probeLog("候选池：实体={} 模组文件={} 收割就绪={} 收割类={} 合计候选={}",
                    victimType.getName(), modPath, complete, seedCount, candidates.size());
        }
        HealthCoreCandidates computed = new HealthCoreCandidates(List.copyOf(candidates), complete);
        HealthCoreCandidates raced = HEALTH_CORE_CANDIDATE_CACHE.putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }

    /**
     * 收割到的种子类 + 其嵌套类（Path 为空返回空）。
     * 绝不在服务端 tick 线程上读模组 jar：全 jar 收割最多读 64 MiB class 字节并解析每个 class 的常量池，随后还要对最多 4096 个类名做资源查找 + Class.forName；实测目标模组里最大的 jar 有 2392 个 class / 16.5 MB，同步做等于一次数百 ms～秒级的 tick 冻结。
     * 因此这里只查缓存：未就绪就把收割/展开丢给预热线程，本击按「未就绪」返回空，后续命中再取（发现本身是行为验证制，漏一次不影响正确性）。
     */
    private static List<Class<?>> seedClassExpansion(Path path, ClassLoader loader) {
        if (path == null) return List.of();
        String key = path.toString();
        List<Class<?>> cached = MOD_SEED_EXPANSION_CACHE.get(key);
        if (cached != null) return cached;
        if (SEED_EXPANSION_NEGATIVE.blocked(key)) return List.of();
        warmSeedExpansion(path, loader, false);
        return List.of();
    }

    /**
     * 后台完成「收割类名 → 解析成类 → 展开嵌套类」的完整链路；同一文件只跑一次。
     * @param onWarmupThread 调用方本身就在预热线程上。此时直接同步执行而不是再排一个队列任务：预热线程上的前序任务（引用类展开 / 写入口候选）可能很慢，排队会把「本可以在进场时准备好的收割」拖到战斗里才完成。
     */
    private static void warmSeedExpansion(Path path, ClassLoader loader, boolean onWarmupThread) {
        String key = path.toString();
        if (!SEED_EXPANSION_IN_FLIGHT.add(key)) return;
        if (onWarmupThread) {
            runSeedExpansion(key, path, loader);
            return;
        }
        SEED_EXECUTOR.execute(() -> runSeedExpansion(key, path, loader));
    }

    /**
     * 模组文件收割专用线程。与 {@link #WARMUP_EXECUTOR} 分开：进场的类集合预热会做「深度 4 / 4096 类」的常量池展开，单线程队列下可能跑很久，会把「战斗里才按需触发的收割」一直排在后面。收割本身是纯文件 IO + 常量池解析，单独一条线程既不会互相阻塞，也不会影响服务端 tick。
     */
    private static final ExecutorService SEED_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "more_attributes-seed");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    static void runSeedExpansion(String key, Path path, ClassLoader loader) {
        long startNanos = System.nanoTime();
        List<Class<?>> seeds = List.of();
        try {
            seeds = resolveSeedClasses(path, loader);
            if (seeds.isEmpty()) {
                SEED_EXPANSION_NEGATIVE.mark(key);
            } else {
                MOD_SEED_EXPANSION_CACHE.put(key, withNestedClasses(seeds, MOD_SEED_NESTED_MAX));
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            SEED_EXPANSION_NEGATIVE.mark(key);
        } finally {
            logSeedHarvest(key, seeds, (System.nanoTime() - startNanos) / 1_000_000L);
            SEED_HARVEST_READY.add(key);
            SEED_EXPANSION_IN_FLIGHT.remove(key);
        }
    }

    /** 诊断（不改行为）：每个模组文件只在收割结束时打印一次规模、相关类名与加载结果 */
    private static void logSeedHarvest(String key, List<Class<?>> seeds, long millis) {
        if (!MAConfig.debugProbes || !SEED_DIAGNOSED.add(key)) return;
        List<String> names = MOD_SEED_NAME_CACHE.getOrDefault(key, List.of());
        List<String> harvestedRelevant = new ArrayList<>();
        for (String name : names) {
            if (isSeedDiagRelevant(name) && harvestedRelevant.size() < SEED_DIAGNOSE_SAMPLE) {
                harvestedRelevant.add(name);
            }
        }
        // 收出来的类名里哪些真正加载成了 Class：区分「名字有但加载失败」与「加载成功但未被识别」
        Set<String> loadedNames = new LinkedHashSet<>();
        List<String> loadedRelevant = new ArrayList<>();
        for (Class<?> seed : seeds) {
            String name = seed.getName().replace('.', '/');
            loadedNames.add(name);
            if (isSeedDiagRelevant(name) && loadedRelevant.size() < SEED_DIAGNOSE_SAMPLE) {
                loadedRelevant.add(name);
            }
        }
        List<String> unresolvedRelevant = new ArrayList<>();
        for (String name : names) {
            if (isSeedDiagRelevant(name) && !loadedNames.contains(name)
                    && unresolvedRelevant.size() < SEED_DIAGNOSE_SAMPLE) {
                unresolvedRelevant.add(name);
            }
        }
        probeLog("种子收割完成：{} 耗时={}ms 类名={} 解析成类={} 预算用尽={}",
                key, millis, names.size(), seeds.size(), names.size() >= MOD_SEED_MAX_CLASSES);
        probeLog("种子收割相关类：已加载={} 未加载={} 收割到的相关类名采样={}",
                loadedRelevant, unresolvedRelevant, harvestedRelevant);
    }

    /** 收割诊断：类名是否与影子/授权/写口相关（不参与发现） */
    private static boolean isSeedDiagRelevant(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("authority") || lower.contains("health") || lower.contains("handler")
                || lower.contains("station") || lower.contains("vault");
    }

    /** 收割 + 解析种子类（只允许在预热线程或已就绪的缓存上执行） */
    static List<Class<?>> resolveSeedClasses(Path path, ClassLoader loader) {
        String key = path.toString();
        List<String> names = MOD_SEED_NAME_CACHE.get(key);
        if (names == null) {
            // 不用 computeIfAbsent：全 jar 收割（最多 64MiB）耗时，占着 bin 锁会阻塞调用方
            names = harvestSeedNames(path);
            MOD_SEED_NAME_CACHE.put(key, names);
        }
        if (names.isEmpty()) return List.of();
        List<Class<?>> seeds = new ArrayList<>();
        for (String name : names) {
            // 先确认类文件资源存在再加载：对不存在的名字直接跳过，避免 Class.forName 抛异常填栈的高开销
            Class<?> clazz = loadSeedClass(name, loader);
            if (clazz != null) seeds.add(clazz);
        }
        return seeds;
    }

    /** 实体类 → 所属模组文件路径（按类缓存：注册表 / ModList 结果在进程内稳定） */
    private static final Map<Class<?>, Optional<Path>> MOD_PATH_CACHE = new ConcurrentHashMap<>();

    /** 实体所属模组的文件路径；取不到返回 null */
    static Path modFilePath(LivingEntity victim) {
        return computeOutsideLock(MOD_PATH_CACHE, victim.getClass(),
                () -> Optional.ofNullable(resolveModFilePath(victim))).orElse(null);
    }

    private static Path resolveModFilePath(LivingEntity victim) {
        try {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
            return key == null ? null : modFilePathOfNamespace(key.getNamespace());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 命名空间（模组 id）对应的模组文件路径；游戏本体命名空间或取不到时返回 null */
    private static Path modFilePathOfNamespace(String namespace) {
        if (namespace == null || isBuiltinNamespace(namespace)) return null;
        try {
            IModFileInfo info = ModList.get().getModFileById(namespace);
            IModFile file = info == null ? null : info.getFile();
            return file == null ? null : file.getFilePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该实体所属模组文件的种子收割是否已经跑完；未跑完时的空结论不得进入长期退避 */
    static boolean seedHarvestReady(LivingEntity victim) {
        Path path = modFilePath(victim);
        return path == null || SEED_HARVEST_READY.contains(path.toString());
    }

    /**
     * 记录一次「某层没探到」。候选集完整时走正常的两段式退避；候选集还不完整（模组文件收割在跑）时只做一次很短的退避。
     * 既避免同一秒内反复重跑整套发现，又能让收割完成后立刻重探。
     */
    static void markDiscoveryMiss(NegativeCache<Class<?>> cache, Class<?> type, boolean complete) {
        if (complete) {
            cache.mark(type);
        } else {
            cache.markShort(type);
        }
    }

    /** 游戏本体命名空间不做收割 */
    private static boolean isBuiltinNamespace(String namespace) {
        return "minecraft".equals(namespace) || "forge".equals(namespace);
    }

    /** 读取模组文件里的所有 class 条目，收割「常量池类引用 + 类名形态的字符串常量」 */
    private static List<String> harvestSeedNames(Path path) {
        Set<String> names = new LinkedHashSet<>();
        long[] budget = {MOD_SEED_MAX_BYTES};
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path, MOD_SEED_MAX_DEPTH)) {
                    Iterator<Path> entries = walk.iterator();
                    while (entries.hasNext() && canHarvest(names, budget)) {
                        Path entry = entries.next();
                        if (!entry.toString().endsWith(".class")) continue;
                        byte[] bytes;
                        try {
                            bytes = Files.readAllBytes(entry);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        collectSeedNames(bytes, names, budget);
                    }
                }
            } else {
                // 模组文件容器（.jar / .zip）
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements() && canHarvest(names, budget)) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                        byte[] bytes;
                        try (InputStream stream = zip.getInputStream(entry)) {
                            bytes = stream.readAllBytes();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        collectSeedNames(bytes, names, budget);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 文件不可读/格式不符时该种子源整体跳过
        }
        return List.copyOf(names);
    }

    private static boolean canHarvest(Set<String> names, long[] budget) {
        return names.size() < MOD_SEED_MAX_CLASSES && budget[0] > 0;
    }

    /** 解析单个 class 文件，把其中的模组类名收进候选集（按读取字节数扣预算） */
    static void collectSeedNames(byte[] bytes, Set<String> names, long[] budget) {
        if (bytes == null || bytes.length == 0 || !canHarvest(names, budget)) return;
        budget[0] -= bytes.length;
        for (String name : parseClassNames(bytes, true)) {
            if (names.size() >= MOD_SEED_MAX_CLASSES) return;
            if (name.startsWith("[")) continue;
            if (!isModClassName(name.replace('/', '.'))) continue;
            names.add(name);
        }
    }
}

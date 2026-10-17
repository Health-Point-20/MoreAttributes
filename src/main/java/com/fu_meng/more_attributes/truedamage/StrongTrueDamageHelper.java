package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.MoreAttributesMod;
import com.fu_meng.more_attributes.MAConfig.TrueDamageMode;
import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;
import com.fu_meng.more_attributes.handler.CombatHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.fu_meng.more_attributes.AttributesRegistry.EXTRA_TRUE_DAMAGE;
import static com.fu_meng.more_attributes.truedamage.AuthorityHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.CipherHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.EncryptedHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.FieldHealthProbe.*;
import static com.fu_meng.more_attributes.truedamage.ModWriterProbe.*;
import static com.fu_meng.more_attributes.truedamage.StateFrameLayer.*;
import static com.fu_meng.more_attributes.truedamage.StaticAuthorityLayer.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageWarmup.*;

/**
 * 真伤（{@code trueDamageMode}）的强制写入结算与兜底，也供斩杀阈值复用；本类只保留事件订阅、重入闸门与
 * 结算级联的编排，各层实现、探针与反射工具都在同包其它类里。
 * 本包各层大量 catch (Throwable) 是刻意保持宽泛的：try 块几乎都在反射读写未知第三方模组对象，失败是常态；
 * 就地吞掉异常是设计要求——一次探针失败只应让这一层不生效，绝不能打断伤害事件乃至服务端 tick。底线是
 * 虚拟机级错误绝不能被吞：共享工具都调用 {@link TrueDamageRuntime#rethrowIfFatal(Throwable)} 把 {@code VirtualMachineError}
 * 原样抛出，个别 catch 已收窄成 {@code catch (Exception)}。 */
@Mod.EventBusSubscriber
public class StrongTrueDamageHelper {

    /** 玩家近战已发起、目标可能在自身 hurt() 里直接取消的“额外真伤”登记 */
    private static final Map<LivingEntity, Player> PENDING_MELEE_TRUE_DAMAGE = new ConcurrentHashMap<>();

    /**
     * 密文槽位写入成功后待复核的目标 → 目标血量。
     * 实测 iss_csw 无名术士：第一刀槽位写入当场读回正确，但该刀完全不掉血，第二刀起才正常——模组在同 tick
     * 稍晚或下一 tick 把自己的存在量重置回了满血。故写入成功后登记一次复核：下一 tick 读回发现被回滚就按缺口
     * 补写一次。只登记一次、只补一次，不做常驻守值。
     */
    private static final Map<LivingEntity, float[]> PENDING_CIPHER_REASSERT = new ConcurrentHashMap<>();

    /** 本次近战的伤害事件已正常触发，消费兜底登记 */
    public static void consumePendingMeleeTrueDamage(LivingEntity victim, LivingEntity attacker) {
        if (PENDING_MELEE_TRUE_DAMAGE.get(victim) == attacker) {
            PENDING_MELEE_TRUE_DAMAGE.remove(victim);
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onAttackEntity(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        LivingEntity victim = event.getTarget() instanceof LivingEntity living ? living : null;

        if (victim == null) return;
        if (!MAConfig.trueDamageMode.bypassesPipeline()) return;

        double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get()) * CombatHelper.getMeleeCooldownFactor(attacker);

        if (victim.isInvulnerable() || victim instanceof Player playerVictim && playerVictim.getAbilities().invulnerable) {
            if (trueDamage <= 0) return;
            DamageSource damageSource = attacker.damageSources().playerAttack(attacker);
            // 真伤直接扣血，致死时已调用 die()
            if (applyStrongerTrueDamage(victim, trueDamage, damageSource)) {
                // 伤害管线不会触发，手动补上受击表现
                playHurtFeedback(victim, damageSource);
            }
        } else if (trueDamage > 0 && !victim.level().isClientSide) {
            // 目标在 hurt() 里取消伤害时事件不触发，登记后由 tick 末兜底结算
            PENDING_MELEE_TRUE_DAMAGE.put(victim, attacker);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 负结果退避缓存与服务端 tick 同步
            SERVER_TICK.incrementAndGet();
            // 深度探针缓存持有实体引用，tick 起始清空
            DEEP_HEALTH_CACHE.clear();
            // 诊断采样（/ma truedamage watch）：观察写入是否被对方回滚
            TrueDamageDiagnostics.tickWatches();
            // 一次性把写入口 BFS 的共享冷启动开销（engine 类字节/嵌套类）固定在加载期
            prewarmEngineCacheOnce();
            return;
        }

        // tick 末兜底结算登记表里的近战真伤；被闪避的目标不结算
        if (!PENDING_MELEE_TRUE_DAMAGE.isEmpty()) {            PENDING_MELEE_TRUE_DAMAGE.forEach((victim, attacker) -> {
                UUID dodgedAttacker = CombatHelper.DODGED_MELEE.get(victim.getUUID());
                boolean wasDodged = dodgedAttacker != null && dodgedAttacker.equals(attacker.getUUID());
                if (MAConfig.debugProbes && (wasDodged || !shouldSettleTrueDamage(victim))) {
                    MoreAttributesMod.LOGGER.info(
                            "[真伤] tick 末兜底被跳过：目标={} 被闪避={} 已移除={} 客户端={} 当前血量={}",
                            victim.getName().getString(), wasDodged, victim.isRemoved(),
                            victim.level().isClientSide, healthOf(victim));
                }
                if (!wasDodged && shouldSettleTrueDamage(victim)) {
                    // 按本次挥击记录的攻击冷却比例回溯结算
                    Float attackStrength = CombatHelper.ATTACK_STRENGTH_RECORD.get(attacker.getUUID());
                    float cooldownFactor = attackStrength != null ? CombatHelper.getMeleeCooldownFactor(attackStrength) : 1.0F;
                    double trueDamage = attacker.getAttributeValue(EXTRA_TRUE_DAMAGE.get()) * cooldownFactor;
                    if (trueDamage > 0) {
                        DamageSource source = attacker.damageSources().playerAttack(attacker);
                        // 伤害管线未触发，结算后手动补上受击表现
                        if (applyStrongerTrueDamage(victim, trueDamage, source)) {
                            playHurtFeedback(victim, source);
                        }
                    } else if (MAConfig.debugProbes) {
                        MoreAttributesMod.LOGGER.info("[真伤] tick 末兜底未结算：目标={} 额外真伤={}",
                                victim.getName().getString(), trueDamage);
                    }
                }
            });
            PENDING_MELEE_TRUE_DAMAGE.clear();
        }
        // 密文槽位写入的次 tick 复核：按缺口补写一次（首刀常被模组自己回滚）；放在 tick 末，模组本 tick 的回写已发生
        reassertRevertedCipherWrites();
        // T12：实体进场后的下一个 tick 做「提前发现」，使首击当次生效（必须在服务端线程）
        TrueDamageWarmup.drainPreDiscovery();
        CombatHelper.DODGED_MELEE.clear();
    }

    /**
     * 正在结算的目标（仅服务端线程使用）。级联会反射调用模组方法（{@code writeViaMacAuthority} 甚至直接回调
     * {@code victim.hurt(...)}），可能重入伤害管线回到 {@link #applyStrongerTrueDamage}；嵌套结算会重复扣血，
     * 并让 {@link #PENDING_FORCED_DEATH} 的 add/remove 交错（{@code die()} 被调两次）。 */
    private static final ThreadLocal<Set<LivingEntity>> SETTLING = ThreadLocal.withInitial(
            () -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /** 复刻原版 hurt 的受击表现（音效/受击帧/同步标记） */
    static void playHurtFeedback(LivingEntity victim, DamageSource source) {
        LivingEntityAccessor victimMixin = (LivingEntityAccessor) victim;
        if (currentHealth(victim) > 0.0F) {
            victimMixin.playHurtSound(source);
            victim.hurtTime = 20;
            victim.hurtMarked = true;
        }
    }

    /** 是否仍应给该目标结算真伤：未移除且真实血量 > 0 */
    static boolean shouldSettleTrueDamage(LivingEntity victim) {
        if (victim.isRemoved() || victim.level().isClientSide) return false;
        // 直接用两个原始读数，不经任何假血量推断/缓存：任一读数 > 0 就说明还有血，该结算就结算
        float getter = healthOf(victim);
        float mirrored = readVanillaHealthData(victim);
        return getter > 0.0F || mirrored > 0.0F;
    }

    /** 层级耗时低于这个值不记日志（毫秒），避免刷屏 */
    private static final long LAYER_TRACE_MIN_MS = 5L;

    /**
     * 原版同步镜像像哨兵值的判定阈值：镜像低到该值以下（原版 {@code DATA_HEALTH_ID} 初始占位值就是 1.0）而
     * {@code getHealth()} 高出它 {@link #MIRROR_SENTINEL_RATIO} 倍时，判定镜像不是真值，而是对方（盾掉
     * {@code SynchedEntityData.set} 的模组）喂给原版通道的占位读数，真实血量在其授权存储里，此时必须让大搜索层照常运行。 */
    private static final float MIRROR_SENTINEL_MAX = 2.0F;

    /** 见 {@link #MIRROR_SENTINEL_MAX} */
    private static final float MIRROR_SENTINEL_RATIO = 5.0F;

    /** 单层耗时：只在 debugProbes 打开且超过阈值时记一行（用于定位首次命中的延迟来源） */
    private static void traceLayer(String layer, long startNanos, LivingEntity victim) {
        if (!MAConfig.debugProbes) return;
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (millis >= LAYER_TRACE_MIN_MS) {
            probeLog("层级耗时 {}ms：{}（{}）", millis, layer, victim.getClass().getName());
        }
    }

    /** 「结算未到位」只打一次（每 (实体类, 层)，见 D4 日志降噪） */
    private static final Set<String> SETTLE_MISS_LOGGED = ConcurrentHashMap.newKeySet();

    /** 一层写入 / 一项发现的调用体（允许抛任何 Throwable，由 {@link #attempt} 统一兜住） */
    @FunctionalInterface
    interface LayerCall {
        void run() throws Throwable;
    }

    /**
     * 执行一层写入：任何 Throwable（含 {@code NoClassDefFoundError} 这类 {@code LinkageError}）都只让这一层不生效，
     * 绝不中断整次结算，并照常记账耗时。
     * 必须在这里兜：各层反射读写未知第三方模组类，而那些类可能引用本整合包没装的模组。实测 Goety 亚波伦的静态
     * 表层扫到引用 {@code se/mickelus/tetra/...} 的候选类直接抛 {@code NoClassDefFoundError}；它是 {@code LinkageError}
     * 而非 {@code VirtualMachineError}，{@code rethrowIfFatal} 不上抛，被结算级兜底吞掉会整次结算中止，表现为「这只
     * boss 怎么打都不掉血」。类头写的该约束正是此意。 */
    static void attempt(String layer, LivingEntity victim, LayerCall body) {
        long startNanos = MAConfig.debugProbes ? System.nanoTime() : 0L;
        // C：归因快照。只多两次 getHealth()，能把「哪一层把对方触发成假满血」钉死。
        float beforeLayer = healthOf(victim);
        try {
            body.run();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            probeLog("层「{}」抛出异常，已跳过（{}）：{}", layer, victim.getClass().getName(), t);
        } finally {
            traceLayer(layer, startNanos, victim);
        }
        // C：读数从低于上限跳到恰好上限 = 对方把无效记录回退成满血的签名（GreatSageHealthManager.getHealth()
        // 在 validMac 失败时返回 maxHealth）。状态变化类日志，刻意不去重：出现就必须看见，否则会把假满血当真实满血。
        float afterLayer = healthOf(victim);
        float maxNow = maxHealthOf(victim);
        if (!Float.isNaN(maxNow) && beforeLayer > 0.0F && beforeLayer < maxNow && matches(afterLayer, maxNow)) {
            probeLog("⚠ 层「{}」写入后读数跳回上限（{} → {}，上限 {}）：疑为对方记录自校验失效（假满血），{}",
                    layer, beforeLayer, afterLayer, maxNow, victim.getClass().getName());
        }
    }

    /** 密文槽位写入成功（复读一致）后登记一次「下一 tick 复核」，见 {@link #PENDING_CIPHER_REASSERT} */
    private static void rememberCipherReassert(LivingEntity victim, float healthAfter) {
        float stored = CipherHealthLayer.storedHealth(victim);
        if (!Float.isNaN(stored) && matches(stored, healthAfter)) {
            PENDING_CIPHER_REASSERT.put(victim, new float[]{healthAfter});
        }
    }

    /** tick 末复核：密文槽位若被模组回滚，按缺口补写一次（只补一次，不做常驻守值） */
    private static void reassertRevertedCipherWrites() {
        if (PENDING_CIPHER_REASSERT.isEmpty()) return;
        PENDING_CIPHER_REASSERT.forEach((victim, target) -> {
            try {
                if (victim.isRemoved() || victim.level().isClientSide) return;
                float now = CipherHealthLayer.storedHealth(victim);
                // 没被回滚（读数仍是目标值）或读不出来 → 不需要补写
                if (Float.isNaN(now) || matches(now, target[0])) return;
                float missing = Math.max(0.0F, now - target[0]);
                if (missing <= 0.0F) return;
                // 按缺口补写：计数型槽位加 missing 正好把血量推回目标值
                CipherHealthLayer.writeViaCipherHealth(victim, target[0], missing);
                probeLog("密文同步项：写入被回滚，已按缺口 {} 补写一次（目标={}）", missing, target[0]);
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
        });
        PENDING_CIPHER_REASSERT.clear();
    }

    /**
     * 是否「仍未写到位」——所有层级共用的推进判据。优先级：模组自有存储（密文层标定后的真值）&gt; 原版同步镜像
     * （假血量/门控实体）&gt; {@code getHealth()}。
     * 只用 {@code getHealth()} 判定时，对「恒为最大血量」的实体（iss_csw 无名术士、Goety 亚波伦等）每层都认为
     * 还没到位，每一击都要把所有层的首次发现（数万次反射调用）跑一遍，实测首次命中 1~2.7 秒全砸伤害路径。 */
    private static boolean notYet(LivingEntity victim, float healthAfter, boolean mirrorTruth) {
        float stored = CipherHealthLayer.storedHealth(victim);
        if (!Float.isNaN(stored)) return !matches(stored, healthAfter);
        // 门控可能只是暂时的：顶层 setHealth 一生效 getHealth() 就恢复可读，原版镜像却还停在旧值上。
        // 若按冻结的 mirrorTruth 判定，镜像一直说未到位，后面的层会在同一段伤害上再写一次，按差值写入的
        // 持有对象层照旧差值再扣一遍（实测原血量=400 目标=300 最终落到 200）。镜像只有现在仍被门控才算真值。
        boolean gatedNow = healthOf(victim) <= 0.0F && readVanillaHealthData(victim) > 0.0F;
        return stillAboveTarget(victim, healthAfter, mirrorTruth && gatedNow);
    }

    /**
     * 本次真伤「还没扣掉的部分」——在每个按差值写入的层调用前现算，绝不复用命中前的旧基线。
     * 旧实现直接传 {@code healthBefore - healthAfter}；一旦靠前步骤已写到位（顶层 {@code setHealth} 或更便宜的
     * 层已命中），旧差值就不等于还需扣多少，那些层会照它再扣一次。现算读数优先级与基线一致：模组自有存储 &gt;
     * {@code getHealth()} &gt; 原版同步镜像，全读不到时退回旧差值。 */
    private static float remainingDamage(LivingEntity victim, float healthAfter, float fallback) {
        float live = CipherHealthLayer.storedHealth(victim);
        if (Float.isNaN(live)) live = healthOf(victim);
        if (Float.isNaN(live)) live = readVanillaHealthData(victim);
        if (Float.isNaN(live)) return Math.max(0.0F, fallback);
        return Math.max(0.0F, live - healthAfter);
    }

    /** 真伤独立结算（档 1/2/3，对所有生物，含玩家）：按档位分层依次尝试强制写入 */
    public static boolean applyStrongerTrueDamage(LivingEntity victim, double amount, DamageSource source) {
        return applyStrongerTrueDamage(victim, amount, source, true);
    }

    /**
     * 真伤独立结算的重载：{@code callDie} 为 {@code false} 时只强制扣血、不调用 {@code die()}。
     * 供伤害管线（{@code handler} 包的 {@code AttributesHandler}）用于自定义 NPC 等「要扣血但不要本模组接管
     * 死亡流程」的目标，避免双倍掉落。 */
public static boolean applyStrongerTrueDamage(LivingEntity victim, double amount, DamageSource source,
                                           boolean callDie) {
        if (amount <= 0.0 || victim.level().isClientSide) return false;
        // 重入闸门：级联会反射调用模组方法（writeViaMacAuthority 甚至回调 victim.hurt），可能重入伤害管线回到这里；嵌套结算会重复扣血，并让 PENDING_FORCED_DEATH 的 add/remove 交错（die() 被调两次）。
        Set<LivingEntity> settling = SETTLING.get();
        if (!settling.add(victim)) return false;
        try {
            return settleTrueDamage(victim, amount, source, callDie);
        } catch (Throwable t) {
            // 兜底：模组自定义 getHealth / 写口抛出的异常不应中断整个伤害事件的处理
            rethrowIfFatal(t);
            probeLog("真伤结算异常已拦截：{}（{}）", victim.getClass().getName(), t);
            return false;
        } finally {
            settling.remove(victim);
        }
    }

    /** 真伤强制写入的实际实现（由 {@link #applyStrongerTrueDamage} 包重入闸门与异常兜底） */
    static boolean settleTrueDamage(LivingEntity victim, double amount, DamageSource source, boolean callDie) {
        // 诊断计时：debugProbes 关闭时不产生任何开销
        long startNanos = MAConfig.debugProbes ? System.nanoTime() : 0L;
        TrueDamageMode mode = MAConfig.trueDamageMode;
        // 档 0（管线内）不走强制扣血路径
        if (!mode.bypassesPipeline()) return false;

        // L0.3 常量池指纹情报层（默认开；只做情报/排序/门控规避，自身从不写血）。读取实体继承链的运行时常量池，
        // 即 Mixin/CoreMod/Agent 变换后的真实类内容引用视图，在进入昂贵级联前拿到门控预判与候选优先级；读取失败整体静默。
        GateReport gateReport = MAConfig.trueDamageMode.usesPoolIntel() ? GateReport.forEntity(victim.getClass()) : GateReport.EMPTY;
        if (gateReport.needsFilterLift()) {
            // 目标可能把自己/父类成员登记进了 JDK 反射过滤黑名单：提前按继承链摘除
            for (Class<?> hierarchyClass : ReflectionToolkit.hierarchy(victim.getClass())) {
                ReflectionToolkit.liftReflectiveFilters(hierarchyClass);
            }
        }
        // 池里含调用栈类门控签名时，即使 getHealth() 当前 > 0 也要把镜像当兜底真值
        boolean poolPredictsGate = gateReport.entitySignatures().stream()
                .anyMatch(kind -> kind == GateSignatureDetector.GateKind.STACK_WALKER
                        || kind == GateSignatureDetector.GateKind.CALLER_CLASS
                        || kind == GateSignatureDetector.GateKind.GET_STACK_TRACE);
        if (gateReport.hiddenClassSuspected() && MAConfig.debugProbes) {
            probeLog("L0.3 隐藏类迹象：{}（后续层穿透力上限降低）", victim.getClass().getName());
        }

        float getterHealth = healthOf(victim);
        float mirrored = readVanillaHealthData(victim);
        // 只有硬证据才不信 getHealth()：门控 getHealth（恒 <= 0、镜像 > 0）时改用原版同步镜像；模组自有存储
        // 可读时按优先级取用；其余以 getHealth() 为准。不做固定值/假血量推断，该推断不可靠，曾让 omni 铁傀儡这类实体退化。
        boolean gatedGetter = (getterHealth <= 0.0F && mirrored > 0.0F);
        boolean detachedGetter = healthGetterDetached(victim, getterHealth, mirrored);
        // 原版同步镜像只有在 getHealth() 被【硬门控】（恒 <= 0）时才是真值
        boolean mirrorTruth = gatedGetter;
        // L0.3：池指纹预判到调用栈门控时，把镜像追加为「是否写到位」的判据（notYet 同时看镜像与 getHealth，
        // 两者都没到位才继续）。这里刻意不改写血量基线：曾用「池里含 GET_STACK_TRACE」直接判 getHealth() 是假值，
        // 把读数正当的模组判错——Mbt_New 守卫 getHealth()=349.35 真实且随 tick 变化，原版镜像却被盾成恒 1.0，
        // 基线取 1.0 后每击都算目标=0，真伤完全无效。镜像若不像血量读数（1 点上下而 getHealth() 几十点以上量级），
        // 说明是对方喂给原版通道的哨兵值，真实血量在其授权存储里；此时必须让大搜索层照常运行（授权表层就在那里写入）。
        boolean mirrorIsSentinel = mirrored < MIRROR_SENTINEL_MAX
                && getterHealth > mirrored * MIRROR_SENTINEL_RATIO;
        if (poolPredictsGate && !gatedGetter && !mirrorIsSentinel
                && mirrored > 0.0F && !matches(getterHealth, mirrored)) {
            probeLog("L0.3 池预判门控，无镜像判据：getHealth={} 镜像={}", getterHealth, mirrored);
            mirrorTruth = true;
        }
        // 血量基线优先级：模组自有存储（密文 / 持有对象）> 门控时取镜像 > getHealth()
        float healthBefore;
        float storedHealthNow = CipherHealthLayer.storedHealth(victim);
        float holderHealthNow = ModWriterProbe.holderHealth(victim);
        if (!Float.isNaN(storedHealthNow) && storedHealthNow >= 0.0F) {
            healthBefore = storedHealthNow;
        } else if (!Float.isNaN(holderHealthNow) && holderHealthNow >= 0.0F) {
            // 持有对象层读到的模组自有血量（亚波伦 MegaCapability/FloatWrapped）
            healthBefore = holderHealthNow;
        } else if (mirrorIsSentinel) {
            // 镜像明显是哨兵值：真实血量在对方授权存储里，以 getHealth() 的量级做基线
            healthBefore = getterHealth;
        } else if (gatedGetter) {
            healthBefore = mirrored;
        } else {
            // getHealth() 就是模组自己的真实读数（可能低于最大血量）
            healthBefore = getterHealth;
        }
        // NaN（模组覆写的 getHealth 抛异常）与 <= 0 都视为「本次不结算」
        if (!(healthBefore > 0.0F)) return false;
        // 命中前的权威读数快照（取法同下面的 authoritativeNow）：区分「本模组打到 0」与「读数本来就是 0」。
        // 对方 coremod 常把 DATA_HEALTH_ID / getHealth() 盾成恒 0；不区分会把满血目标误判为致死并直接删除。
        float authoritativeBefore = !Float.isNaN(storedHealthNow) ? storedHealthNow
                : mirrorTruth ? mirrored : getterHealth;
        // 档 1+ 恒定足额结算，不参与原版无敌帧
        float healthAfter = Math.max(0.0F, healthBefore - (float) amount);
        if (MAConfig.debugProbes) {
            probeLog("===== 结算开始 {} 原血量={} 目标={} 档位={}{} =====",
                    victim.getClass().getName(), healthBefore, healthAfter, mode,
                    gatedGetter ? "（门控 getHealth，按镜像判定）" : (detachedGetter ? "（getHealth 不读镜像，仍以 getHealth 为准）" : ""));
        }

        victim.setHealth(healthAfter);
        // 档 3/4：致死目标被写口拒收 0、只能停在最小正血量时，仍需按致死处理（见下方死亡块）
        boolean lethalWriterStuck = false;
        // 门控实体的 getHealth() 恒为 0，只能用持有对象读写口的结果判成败
        boolean holderWrote = false;
        boolean searchable = mode.probesHealthBacking() && !gatedGetter;

        // 便宜且高命中的模组自有存储层：放在原版镜像之前。它们写的是模组自己的权威值（密文串/编码字段/
        // 数值编解码），先写可避免模组 tick 用权威值把原版镜像回灌成满血；也最便宜，是假血量 boss 的主命中路径。
        if (searchable && mode.rewritesEncryptedHealth() && notYet(victim, healthAfter, mirrorTruth)) {
            float damageNow = remainingDamage(victim, healthAfter, healthBefore - healthAfter);
            attempt("密文同步项", victim, () -> writeViaCipherHealth(victim, healthAfter, damageNow));
            rememberCipherReassert(victim, healthAfter);
        }
        if (searchable && mode.rewritesEncryptedHealth() && notYet(victim, healthAfter, mirrorTruth)) {
            float damageNow = remainingDamage(victim, healthAfter, healthBefore - healthAfter);
            attempt("加密字符串", victim, () -> writeEncryptedHealth(victim, damageNow));
        }
        if (searchable && mode.rewritesEncodedHealth() && notYet(victim, healthAfter, mirrorTruth)) {
            attempt("编解码字段", victim, () -> writeViaEncodedHealth(victim, healthAfter));
        }
        if (searchable && mode.rewritesEncodedHealth() && notYet(victim, healthAfter, mirrorTruth)) {
            attempt("数值编解码", victim, () -> writeViaNumericCodec(victim, healthAfter));
        }

        // 未到位时直写原版同步血量数据，绕过覆写 setHealth：假血量实体的 setHealth 常是空实现，镜像没动，必须直写
        boolean wroteVanilla = false;
        if (!gatedGetter && stillAboveTarget(victim, healthAfter, mirrorTruth)) {
            attempt("原版同步镜像", victim, () -> writeVanillaHealthData(victim, healthAfter));
            wroteVanilla = true;
        }

        // 大范围搜索层：首次发现通常是秒级（数万次反射/扫模组类/扫静态容器），只在前面便宜层都没写到位时才运行
        if (mode.probesHealthBacking()) {
            if (!gatedGetter) {
                // 类字段探针（档 2/3）：只扰动模组自定义类的少量字段，较便宜
                if (notYet(victim, healthAfter, mirrorTruth)) {
                    attempt("类字段探针", victim, () -> forceSetHealthFields(victim, healthBefore, healthAfter));
                }
                // 外部授权表（档 2+）：血量真值在模组自己的静态/单例容器里（纯静态工具类也能覆盖）
                if (notYet(victim, healthAfter, mirrorTruth)) {
                    float damageNow = remainingDamage(victim, healthAfter, healthBefore - healthAfter);
                    attempt("外部授权表", victim, () -> writeViaStaticAuthority(victim, healthAfter, damageNow));
                }
                // 引用类静态表（档 3/4）
                if (notYet(victim, healthAfter, mirrorTruth) && mode.searchesReferencedTables()) {
                    attempt("静态表", victim, () -> writeReferencedStaticTables(victim, healthAfter));
                }
                // 深度对象图遍历（档 2/3/4）
                if (notYet(victim, healthAfter, mirrorTruth)) {
                    attempt("深度对象图", victim, () -> deepWriteHealth(victim, healthBefore, healthAfter));
                }
                // 影子血量单例（档 4，便宜）
                if (notYet(victim, healthAfter, mirrorTruth) && mode.searchesModHealthCore()) {
                    attempt("影子单例", victim, () -> writeViaShadowStore(victim, healthAfter));
                }
                // 校验(MAC)保护的静态权威记录（档 4，便宜且命中率高：村庄 bosses 走这条）
                if (notYet(victim, healthAfter, mirrorTruth) && mode.searchesModHealthCore()) {
                    attempt("MAC 权威记录", victim, () -> writeViaMacAuthority(victim, healthAfter, source));
                }
                // 模组权威值直写（致死）与权威累计值按量写入（非致死）（档 4，便宜）
                // 判据不能只看 notYet()：影子层写的是影子表，getHealth() 跟着变，notYet() 会认为已到位，
                // 但模组的权威累计值 acc 没动，而它的死亡判据（acc >= 上限-1）与聊天/调试读数都读 acc，
                // 于是「以为写好了，对方既不掉读数也不死」（实测 Mbt_New NetheriteGuardian 停在 499.4 且永不死）。
                // 故额外用 acc 口径判一次（authorityStillAbove）：只有它也仍未到位才多给本层一次机会，
                // 真正权威已写好的实体返回 false 不会白跑（致死路径的密文互换不可逆，绝不能对已写好的实体乱做）。
                // 档 4：按原判据（notYet）或 acc 补判出手；档 3：只在 acc 补判成立时出手。
                boolean authorityDue = false;
                if (mode.searchesModHealthCore()) {
                    authorityDue = notYet(victim, healthAfter, mirrorTruth)
                            || authorityStillAbove(victim, healthAfter);
                } else if (mode.correctsAuthorityAccumulator()) {
                    authorityDue = authorityStillAbove(victim, healthAfter);
                }
                if (authorityDue) {
                    boolean lethalTarget = healthAfter <= 0.0F;
                    boolean deltaDue = !lethalTarget && shouldWriteModAuthority(victim, healthAfter);
                    if (lethalTarget || deltaDue) {
                        attempt("模组权威", victim, () -> {
                            if (lethalTarget) {
                                writeViaModAuthority(victim);
                            } else {
                                writeViaModAuthorityDelta(victim, healthAfter);
                            }
                        });
                    }
                }
                // 模组自己的血量读写口（档 4）：最贵的一层（深度 4 / 4096 类宽扫描 + 自定义容器写口），
                // 实测单次 2 秒，所以放在所有便宜且高命中的权威层之后。
                if (notYet(victim, healthAfter, mirrorTruth) && mode.invokesHealthWriters()) {
                    boolean[] stuck = new boolean[1];
                    attempt("模组写入口", victim, () -> stuck[0] = writeViaHealthWriter(victim, healthAfter));
                    if (stuck[0]) lethalWriterStuck = true;
                }
                // 密文串血量算子写口（档 4，最多 4096 次现场标定）
                if (notYet(victim, healthAfter, mirrorTruth) && mode.calibratesCipherOperator()) {
                    attempt("算子写口", victim, () -> writeViaOperatorEndpoint(victim, healthAfter));
                }
            }
            // 持有对象读写口（档 3/4，门控实体也走这条）
            if (mode.invokesHolderWriters() && needsHolderWrite(gatedGetter, victim, healthAfter)) {
                float damageNow = remainingDamage(victim, healthAfter, healthBefore - healthAfter);
                boolean[] wrote = new boolean[1];
                attempt("持有对象", victim, () -> wrote[0] = writeViaHolderWriter(victim, healthAfter, damageNow));
                holderWrote = wrote[0];
            }
            // 隐藏状态帧直写（档 4）
            if (mode.writesHiddenStateFrame() && !gatedGetter && notYet(victim, healthAfter, mirrorTruth)) {
                attempt("状态帧", victim, () -> writeViaStateFrame(victim, healthAfter));
            }
        }

        // 诊断：整条级联走完仍未写到位
        if (MAConfig.debugProbes && !gatedGetter && mode.probesHealthBacking()
                && stillAboveTarget(victim, healthAfter)) {
            probeLog("级联走完仍未写到位：{}（{}）目标={} 当前={} 档位={} 能力[字段探针={} 外部授权表={} 静态表={} 密文={} 编码={} 计数={} 写入口={} 权威核心={} 算子={} 持有对象={} 状态帧={} 绕过死亡保护={}]",
                    victim.getName().getString(), victim.getClass().getName(), healthAfter, healthOf(victim), mode,
                    mode.probesHealthBacking(), mode.probesHealthBacking(), mode.searchesReferencedTables(), mode.rewritesEncryptedHealth(),
                    mode.rewritesEncodedHealth(), mode.invokesCounterWriters(), mode.invokesHealthWriters(),
                    mode.searchesModHealthCore(), mode.calibratesCipherOperator(), mode.invokesHolderWriters(),
                    mode.writesHiddenStateFrame(), mode.ignoresDeathProtection());
            // L0.3 门控报告摘要：解释「为什么这些层可能被目标拒收」
            if (!gateReport.gatedClasses().isEmpty() || !gateReport.poolDelta().isEmpty()
                    || gateReport.hiddenClassSuspected() || gateReport.encryptedStorageSuspected()
                    || gateReport.macAuthoritySuspected()) {
                probeLog("L0.3 门控报告：签名={} 命中类={} 池差集类={} 解过滤={} 隐藏类={} 密文={} MAC={}",
                        gateReport.entitySignatures(), gateReport.gatedClasses().keySet(),
                        gateReport.poolDelta().keySet(), gateReport.needsFilterLift(),
                        gateReport.hiddenClassSuspected(), gateReport.encryptedStorageSuspected(),
                        gateReport.macAuthoritySuspected());
            }
        }

        // 致死判定：getHealth() 可信时看它，假血量/门控实体看原版同步镜像。镜像被本模组写成 0 也算致死，否则
        // getHealth() 返回恒定假值的实体（SuperSteve 的 SafePlayer、accelerator 的 *PlayerShadow、村庄常量怪等）血被写 0 也不进死亡流程。
        float mirroredNow = readVanillaHealthData(victim);
        float storedNow = CipherHealthLayer.storedHealth(victim);
        float authoritativeNow = !Float.isNaN(storedNow) ? storedNow
                : (mirrorTruth && !Float.isNaN(mirroredNow) ? mirroredNow : healthOf(victim));
        // 致死必须是本次命中把权威读数从 > 0 打到 <= 0。命中前已 <= 0（对方 coremod 把 getHealth /
        // DATA_HEALTH_ID 盾成恒 0）不能作致死依据：那只是读数被屏蔽，不代表真没血，否则满血目标会被一击删除。
        boolean reachedZero = authoritativeNow <= 0.0F && authoritativeBefore > 0.0F;
        // 镜像被本模组写成 0 也算致死，但要求写入前镜像为正值，否则「镜像本来就是 0」会被误算成「我们写到了 0」。
        if (wroteVanilla && healthAfter <= 0.0F && mirrored > 0.0F
                && !Float.isNaN(mirroredNow) && mirroredNow <= 0.0F) {
            reachedZero = true;
        }
        // 档 3：写口拒收 0 时血量会停在极小正数，但致死意图明确，仍按致死处理
        boolean lethalStuck = !gatedGetter && healthAfter <= 0.0F && lethalWriterStuck;
        if (reachedZero || lethalStuck) {
            DamageSource deathSource = source != null ? source : victim.damageSources().generic();
            if (!mode.ignoresDeathProtection()
                    && ((LivingEntityAccessor) victim).invokeCheckTotemDeathProtection(deathSource)) {
                traceSlowSettlement(startNanos, victim, healthAfter, mode);
                return true;
            }
            if (callDie) {
                // 档 4：先把所有已验证的绝对血量写入口写 0，再走 die() 与击杀入口兜底。防御纵深：三段都必须「写入侧
                // 异常不阻止死亡流程」——zeroAuthorityHealth 裸调用时若触发整套写入口发现并抛 UOE / LinkageError，die() 不执行。
                if (mode.invokesHealthWriters()) {
                    attempt("致死-权威清零", victim, () -> zeroAuthorityHealth(victim));
                }
                attempt("致死-die", victim, () -> {
                    if (mode.ignoresDeathProtection()) {
                        // 登记本次死亡，使其不被死亡事件取消
                        PENDING_FORCED_DEATH.add(victim);
                        try {
                            victim.die(deathSource);
                        } finally {
                            PENDING_FORCED_DEATH.remove(victim);
                        }
                    } else {
                        victim.die(deathSource);
                    }
                });
                if (mode.invokesHealthWriters()) {
                    // 血量停在最小正数（致死被写口拒收）时，击杀入口不因 getHealth() > 0 被跳过
                    attempt("致死-击杀入口", victim, () -> forceModKillIfNeeded(victim, deathSource, lethalStuck));
                }
            }
        }
        // 门控实体的 getHealth() 恒为 0，不能用它判成败，只能用持有对象读写口的结果
        if (gatedGetter) {
            traceSlowSettlement(startNanos, victim, healthAfter, mode);
            return holderWrote;
        }
        // 安全网：任何层都不应让血量高于命中前的血量。一旦发生（探针回滚失败、写口把探针/最大生命当成
        // 血量），这里记日志并尽力压回目标值：宁可没扣掉，也绝不能把目标打成回血。
        float storedAfterAll = CipherHealthLayer.storedHealth(victim);
        float afterAll = !Float.isNaN(storedAfterAll) ? storedAfterAll
                : (mirrorTruth ? readVanillaHealthData(victim) : healthOf(victim));
        if (afterAll > healthBefore + PROBE_EPSILON) {
            probeLog("⚠ 结算后血量高于命中前（{} → {}，目标 {}），疑有层写反，已压回：{}",
                    healthBefore, afterAll, healthAfter, victim.getClass().getName());
            try {
                victim.setHealth(healthAfter);
            } catch (Throwable t) {
                rethrowIfFatal(t);
            }
            writeVanillaHealthData(victim, healthAfter);
        }
        // 只有把血量写到位才算结算成功：模组自有存储能读时以它为准，其次门控实体按原版镜像。
        float storedFinally = CipherHealthLayer.storedHealth(victim);
        float holderFinally = ModWriterProbe.holderHealth(victim);
        boolean settled = matches(storedFinally, healthAfter)
                || matches(holderFinally, healthAfter)
                || !stillAboveTarget(victim, healthAfter, mirrorTruth);
        if (!settled) {
            // 注意「当前」取自假 getHealth()，对假血量实体恒为最大值，不能单独当失败结论
            if (oncePer(SETTLE_MISS_LOGGED, victim.getClass(), "结算未到位")) {
                probeLog("结算未到位：目标={} 存储={} 持有={} getHealth={}",
                        healthAfter, storedFinally, holderFinally, healthOf(victim));
            }
        }
        traceSlowSettlement(startNanos, victim, healthAfter, mode);
        return settled;
    }

    /** 诊断：单次结算的结束标记与耗时（debugProbes 下总是打结束标记，方便把一次命中的输出切成块） */
    static void traceSlowSettlement(long startNanos, LivingEntity victim, float healthAfter,
                                            TrueDamageMode mode) {
        if (!MAConfig.debugProbes) return;
        probeLog("===== 结算结束 当前={} =====", healthOf(victim));
        if (startNanos == 0L) return;
        long micros = (System.nanoTime() - startNanos) / 1000L;
        if (micros < SETTLEMENT_TRACE_MIN_MICROS) return;
        probeLog("结算耗时 {}us：{}（{}）目标={} 当前={} 档位={}",
                micros, victim.getName().getString(), victim.getClass().getName(),
                healthAfter, healthOf(victim), mode);
    }

    /** 档 3：让本模组真伤/斩杀造成的死亡不被取消（仅限登记过的目标） */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (PENDING_FORCED_DEATH.remove(event.getEntity())) {
            event.setCanceled(false);
        }
    }

    /** 结算耗时诊断的打印阈值（微秒）：低于该值的命中不记耗时日志，避免刷屏 */
    private static final long SETTLEMENT_TRACE_MIN_MICROS = 1000L;
}

package com.fu_meng.more_attributes.truedamage;

import com.fu_meng.more_attributes.MAConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

import static com.fu_meng.more_attributes.truedamage.AuthorityHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.CipherHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.EncryptedHealthLayer.*;
import static com.fu_meng.more_attributes.truedamage.FieldHealthProbe.*;
import static com.fu_meng.more_attributes.truedamage.ModWriterProbe.*;
import static com.fu_meng.more_attributes.truedamage.StateFrameLayer.*;
import static com.fu_meng.more_attributes.truedamage.StrongTrueDamageHelper.*;
import static com.fu_meng.more_attributes.truedamage.TrueDamageRuntime.*;

/**
 * 真伤诊断：直接打出实体能否被真伤打穿、走哪一层、写入会不会被回滚。
 * 各层成败依赖运行时行为验证，光看代码无法确定当前版本哪层还生效，故提供两条指令：
 * /ma truedamage diagnose [目标] 体检；/ma truedamage watch [目标] [tick] 写入 1 点后连续采样。
 * 诊断会真的按级联顺序把血量写到目标值（默认扣 1 点，结算不触发 die()），结束后尽力还原原版
 * 通道（setHealth + 同步血量）；模组自有存储（影子表 / 权威记录 / 密文串等）改写后不会逐层
 * 回滚，相当于挨一次 1 点真伤，否则测不出哪层真能写进去。首次运行可能触发模组 jar 收割（最多
 * 读 64 MiB class 字节），之后命中走缓存。
 */
public final class TrueDamageDiagnostics {

    private TrueDamageDiagnostics() {
    }

    /** 一次体检：默认扣 1 点 */
    private static final float TEST_AMOUNT = 1.0F;

    /** 逐层实测的结果行 */
    private record LayerResult(String name, boolean reached, long micros, String detail) {
    }

    public static int diagnose(CommandSourceStack source, Entity targetEntity) {
        return diagnose(source, targetEntity, false);
    }

    /**
     * @param deep false 只做只读体检（血量读取可信度 / 实体状态 / 档位能力）；
     *             true 才逐层真写一次（会扣 1 点真伤，并跑档 4 的算子/写口层）。
     *             默认只读是刻意的：否则会在没有档位护栏的情况下触发最激进的写入路径。
     */
    public static int diagnose(CommandSourceStack source, Entity targetEntity, boolean deep) {
        if (!(targetEntity instanceof LivingEntity target)) {
            source.sendFailure(Component.literal("需要指定一个生物目标（LivingEntity）"));
            return 0;
        }
        if (target.level().isClientSide) {
            source.sendFailure(Component.literal("真伤诊断只能在服务端执行"));
            return 0;
        }
        List<Component> out = new ArrayList<>();
        out.add(head("真伤诊断", target));

        float getter = healthOf(target);
        float mirrored = readVanillaHealthData(target);
        boolean gated = getter <= 0.0F && mirrored > 0.0F;
        boolean detached = healthGetterDetached(target, getter, mirrored);
        out.add(line("血量读取", String.format(
                "getHealth=%s 原版镜像=%s maxHealth=%s → %s",
                fmt(getter), fmt(mirrored), fmt(maxHealthOf(target)),
                gated ? "§e门控（getHealth 恒 ≤0）" : detached
                        ? "§c假血量（getHealth 不跟原版同步通道）" : "§a可信（走原版通道）")));
        out.add(line("实体状态", String.format("isAlive=%s isDeadOrDying=%s isRemoved=%s invulnerable=%s",
                target.isAlive(), target.isDeadOrDying(), target.isRemoved(), target.isInvulnerable())));
        var mode = MAConfig.trueDamageMode;
        out.add(line("当前档位", String.format("%d（%s）能力[字段探针=%s 静态表=%s 加密=%s 编码=%s 计数=%s 写入口=%s 权威核心=%s 算子=%s 持有对象=%s 状态帧=%s 绕过死亡保护=%s]",
                mode.id(), mode.name(), mode.probesHealthBacking(), mode.searchesReferencedTables(),
                mode.rewritesEncryptedHealth(), mode.rewritesEncodedHealth(), mode.invokesCounterWriters(),
                mode.invokesHealthWriters(), mode.searchesModHealthCore(), mode.calibratesCipherOperator(),
                mode.invokesHolderWriters(), mode.writesHiddenStateFrame(), mode.ignoresDeathProtection())));

        if (!deep) {
            out.add(line("说明", "§7本次为只读体检，未写入任何数据。"
                    + "要逐层实测可用：§f/ma truedamage diagnose deep <目标>§7"
                    + "（会真的扣 1 点真伤，且会调用档 4 的模组写口/算子层，先备份存档）。"));
            out.add(line("建议", "§7先用 §f/ma truedamage watch <目标> 40 §7看当前档位能不能写进去、会不会被回滚。"));
            flush(source, out);
            return 0;
        }

        // 静态发现：先列出找到的位点，再实战。
        float baseline = gated || detached ? (Float.isNaN(mirrored) ? getter : Math.min(getter, mirrored)) : getter;
        if (!(baseline > 0.0F)) {
            out.add(line("结论", "§c血量读不出来或已 ≤0，本次无法结算（先用别的伤害打一下再看）"));
            flush(source, out);
            return 0;
        }
        float healthAfter = Math.max(0.0F, baseline - TEST_AMOUNT);

        long t0 = System.nanoTime();
        var backing = identifyHealthBackingFields(target);
        out.add(line("类字段位点", String.format(
                "直接血量=%s 累计伤害=%s 同步项=%s/%s（%.0f ms）",
                fieldNames(backing.directHealth()), fieldNames(backing.damageTaken()),
                backing.directData().size(), backing.takenData().size(), ms(t0))));
        t0 = System.nanoTime();
        out.add(line("持有对象读口", String.format("方案=%d（%.0f ms）", holderPlans(target).size(), ms(t0))));
        t0 = System.nanoTime();
        out.add(line("影子单例", String.format("方案=%d（%.0f ms）", shadowPlans(target).size(), ms(t0))));
        t0 = System.nanoTime();
        out.add(line("MAC 权威", String.format("方案=%s（%.0f ms）",
                macAuthorityPlan(target) == null ? "无" : "有", ms(t0))));
        t0 = System.nanoTime();
        out.add(line("模组权威", String.format("方案=%d（%.0f ms）", authorityPlans(target).size(), ms(t0))));
        t0 = System.nanoTime();
        out.add(line("模组写入口", String.format("数量=%d（%.0f ms）", healthWriters(target).size(), ms(t0))));

        // 逐层实测：按级联顺序写血，看哪层真能写进去。
        DamageSource src = target.damageSources().generic();
        List<LayerResult> results = new ArrayList<>();
        DEEP_HEALTH_CACHE.clear();
        tryLayer(results, "setHealth", target, healthAfter, () -> target.setHealth(healthAfter));
        tryLayer(results, "原版同步项", target, healthAfter, () -> writeVanillaHealthData(target, healthAfter));
        tryLayer(results, "模组写入口(档4)", target, healthAfter, () -> writeViaHealthWriter(target, healthAfter));
        tryLayer(results, "类字段/同步项探针", target, healthAfter,
                () -> forceSetHealthFields(target, baseline, healthAfter));
        tryLayer(results, "引用类静态表", target, healthAfter, () -> writeReferencedStaticTables(target, healthAfter));
        tryLayer(results, "深度对象图", target, healthAfter, () -> deepWriteHealth(target, baseline, healthAfter));
        tryLayer(results, "加密字符串", target, healthAfter,
                () -> writeEncryptedHealth(target, baseline - healthAfter));
        tryLayer(results, "数值↔字符串编解码", target, healthAfter, () -> writeViaEncodedHealth(target, healthAfter));
        tryLayer(results, "数值编解码(long/int)", target, healthAfter, () -> writeViaNumericCodec(target, healthAfter));
        tryLayer(results, "影子单例写入", target, healthAfter, () -> writeViaShadowStore(target, healthAfter));
        tryLayer(results, "MAC 权威记录", target, healthAfter, () -> writeViaMacAuthority(target, healthAfter, src));
        tryLayer(results, "模组权威直写", target, healthAfter, () -> {
            if (healthAfter <= 0.0F) {
                writeViaModAuthority(target);
            } else {
                writeViaModAuthorityDelta(target, healthAfter);
            }
        });
        tryLayer(results, "密文串算子", target, healthAfter, () -> writeViaHealthOperator(target, healthAfter));
        tryLayer(results, "持有对象读写口", target, healthAfter, () -> writeViaHolderWriter(target, healthAfter, 1.0F));
        tryLayer(results, "隐藏状态帧", target, healthAfter, () -> writeViaStateFrame(target, healthAfter));
        // 「状态帧行为发现」属激进兜底：拿活帧对候选类真调用且没有快照可还原，已随激进兜底硬关（I-16）。
        if (MAConfig.trueDamageMode.allowsAggressiveDiscovery()) {
            tryLayer(results, "状态帧行为发现", target, healthAfter, () -> writeViaBehavioralFrame(target, healthAfter));
        }

        String hit = null;
        out.add(line("逐层实测", "（§7目标 " + fmt(healthAfter) + "§7，实际扣 1 点）"));
        for (LayerResult r : results) {
            if (r.reached() && hit == null) hit = r.name();
            out.add(Component.literal(String.format("  %s %-22s %5.1f ms %s",
                    r.reached() ? "§a✔" : "§7✘", r.name(), r.micros() / 1000.0,
                    r.detail().isEmpty() ? "" : "§7" + r.detail())));
        }

        // 还原原版通道。
        try {
            target.setHealth(baseline);
        } catch (Throwable t) {
            rethrowIfFatal(t);
        }
        writeVanillaHealthData(target, baseline);

        out.add(line("结论", hit == null
                ? "§c没有任何一层写到位：该实体血量不在可探到的通道里（多半是 agent/ASM 改写或堆外/native 存储）"
                : "§a命中通道：" + hit + "§7（按级联顺序，后面的层不必再看）"));
        out.add(line("下一步", "§7若怀疑被回滚（写入后血量自己涨回去），用 §f/ma truedamage watch "
                + target.getName().getString() + " 40 §7连续采样"));
        flush(source, out);
        return hit == null ? 0 : 1;
    }

    /** 跑一层：记录耗时，并判断它是否把「权威血量」写到了目标值 */
    private static void tryLayer(List<LayerResult> out, String name, LivingEntity victim, float target,
                                 Runnable action) {
        float before = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        if (matches(before, target)) {
            out.add(new LayerResult(name, true, 0L, "（上一层已到位，跳过）"));
            return;
        }
        long t0 = System.nanoTime();
        String detail = "";
        try {
            action.run();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            detail = "异常：" + t.getClass().getSimpleName();
        }
        long micros = (System.nanoTime() - t0) / 1000L;
        float after = authoritativeHealth(victim, healthOf(victim), readVanillaHealthData(victim));
        boolean reached = !Float.isNaN(after) && matches(after, target);
        if (!reached && !Float.isNaN(after) && after < before - PROBE_EPSILON) {
            detail = "只写到位一部分（" + fmt(before) + " → " + fmt(after) + "）";
        }
        out.add(new LayerResult(name, reached, micros, detail));
    }

    /** 一次 watch 的采样状态 */
    private static final class WatchTask {
        final CommandSourceStack source;
        final LivingEntity target;
        final float baseline;
        final float expected;
        final int totalTicks;
        int remaining;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float last;
        boolean rollbackSeen;
        boolean reported;

        WatchTask(CommandSourceStack source, LivingEntity target, float baseline, float expected, int ticks) {
            this.source = source;
            this.target = target;
            this.baseline = baseline;
            this.expected = expected;
            this.totalTicks = ticks;
            this.remaining = ticks;
            this.last = baseline;
        }
    }

    private static final List<WatchTask> WATCHES = new ArrayList<>();

    public static int watch(CommandSourceStack source, Entity targetEntity, int ticks) {
        if (!(targetEntity instanceof LivingEntity target)) {
            source.sendFailure(Component.literal("需要指定一个生物目标（LivingEntity）"));
            return 0;
        }
        if (target.level().isClientSide) {
            source.sendFailure(Component.literal("真伤采样只能在服务端执行"));
            return 0;
        }
        float getter = healthOf(target);
        float mirrored = readVanillaHealthData(target);
        boolean gated = getter <= 0.0F && mirrored > 0.0F;
        boolean detached = healthGetterDetached(target, getter, mirrored);
        float baseline = gated || detached ? (Float.isNaN(mirrored) ? getter : Math.min(getter, mirrored)) : getter;
        if (!(baseline > 0.0F)) {
            source.sendFailure(Component.literal("血量读不出来或已 ≤0，先打一下再看"));
            return 0;
        }
        float expected = Math.max(0.0F, baseline - TEST_AMOUNT);
        source.sendSuccess(() -> Component.literal("§7[真伤采样] " + target.getName().getString()
                + "：写入 " + fmt(expected) + "（原 " + fmt(baseline) + "），采样 " + ticks + " tick…"), false);

        // 真正走一次强制写入（不调用 die，避免直接打死）
        applyStrongerTrueDamage(target, TEST_AMOUNT, target.damageSources().generic(), false);
        synchronized (WATCHES) {
            WatchTask task = new WatchTask(source, target, baseline, expected, ticks);
            task.last = authoritativeHealth(target, healthOf(target), readVanillaHealthData(target));
            task.min = task.last;
            task.max = task.last;
            WATCHES.add(task);
        }
        return 1;
    }

    /** 服务端 tick 采样：观察写入是否被对方回写（每 tick 由真伤主类调用） */
    static void tickWatches() {
        List<WatchTask> done = null;
        synchronized (WATCHES) {
            if (WATCHES.isEmpty()) return;
            var it = WATCHES.iterator();
            while (it.hasNext()) {
                WatchTask task = it.next();
                String report = sample(task);
                if (report != null) {
                    if (done == null) done = new ArrayList<>();
                    done.add(task);
                    it.remove();
                }
            }
        }
        if (done != null) {
            for (WatchTask task : done) {
                finish(task);
            }
        }
    }

    /** 返回非 null 表示这次采样发现了结论（提前结束） */
    private static String sample(WatchTask task) {
        LivingEntity target = task.target;
        if (target.isRemoved() || !target.isAlive()) {
            return "实体已死亡/被移除";
        }
        float now = authoritativeHealth(target, healthOf(target), readVanillaHealthData(target));
        if (Float.isNaN(now)) return "血量读不出来";
        task.min = Math.min(task.min, now);
        task.max = Math.max(task.max, now);
        task.last = now;
        // 写入后血量自己涨回去 → 被回滚（对方在 tick 里回写权威存储）
        if (now > task.expected + Math.max(PROBE_EPSILON, 0.05F) && now > task.min + 0.05F) {
            task.rollbackSeen = true;
            return "血量被写回（回滚）";
        }
        if (--task.remaining <= 0) return "采样结束";
        return null;
    }

    private static void finish(WatchTask task) {
        if (task.reported) return;
        task.reported = true;
        String verdict;
        if (task.rollbackSeen) {
            verdict = "§c写入被回滚：对方在这几 tick 内把血量写了回去（自带回写/校验层）";
        } else if (matches(task.last, task.expected)) {
            verdict = "§a写入保持住了：真伤在这只实体上生效";
        } else if (task.last < task.expected - 0.05F) {
            verdict = "§a写入生效且继续下降（可能有其它伤害在掉血）";
        } else {
            verdict = "§e写入既没保持也没回滚，值=" + fmt(task.last) + "（自己判断一下）";
        }
        task.source.sendSuccess(() -> Component.literal(String.format(
                "§7[真伤采样] %s 结束：起始=%s 期望=%s 最低=%s 最高=%s 结束=%s | %s",
                task.target.getName().getString(), fmt(task.baseline), fmt(task.expected),
                fmt(task.min), fmt(task.max), fmt(task.last), verdict)), false);
    }

    private static Component head(String title, LivingEntity target) {
        return Component.literal("§6===== " + title + "：" + target.getName().getString()
                + " §7(" + target.getClass().getName() + ") §6=====");
    }

    private static Component line(String label, String value) {
        return Component.literal("§b" + label + "§7：§f" + value);
    }

    private static void flush(CommandSourceStack source, List<Component> lines) {
        for (Component c : lines) {
            source.sendSuccess(() -> c, false);
        }
    }

    private static String fieldNames(List<java.lang.reflect.Field> fields) {
        if (fields.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.size() && i < 8; i++) {
            if (i > 0) sb.append(' ');
            sb.append(fields.get(i).getName());
        }
        if (fields.size() > 8) sb.append(" …共").append(fields.size());
        return sb.append(']').toString();
    }

    private static float ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0F;
    }

    private static String fmt(float value) {
        if (Float.isNaN(value)) return "NaN";
        return String.format("%.2f", value);
    }
}

package com.fu_meng.more_attributes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.fu_meng.more_attributes.truedamage.TrueDamageDiagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * 模组指令：在游戏内查看/切换真伤档位，以及对具体实体做真伤诊断。
 * <ul>
 *   <li>{@code /more_attributes truedamage} / {@code /ma truedamage} —— 查询当前档位</li>
 *   <li>{@code /more_attributes truedamage <0-4>} —— 仅本次游戏生效</li>
 *   <li>{@code /more_attributes truedamage <0-4> permanent} —— 写入配置文件，永久生效</li>
 *   <li>{@code /more_attributes truedamage diagnose [目标]} —— 逐层实测这只实体能被哪一层打穿
 *       （不指定目标时取视线前方 16 格内的生物；会真的扣 1 点真伤作为测试）</li>
 *   <li>{@code /more_attributes truedamage watch [目标] [tick]} —— 写入 1 点后连续采样，
 *       判断写入是否被对方回滚</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = MoreAttributesMod.MODID)
public class MACommands {

    /** 指令主名与所有别名 */
    private static final String[] ROOT_NAMES = {"more_attributes", "ma"};

    private MACommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 构建子分支
        LiteralArgumentBuilder<CommandSourceStack> trueDamage = Commands.literal("truedamage")
                .executes(context -> query(context.getSource()))
                .then(Commands.argument("mode", IntegerArgumentType.integer(0, 4))
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                new String[]{"0", "1", "2", "3", "4"}, builder))
                        .executes(context -> set(context.getSource(),
                                IntegerArgumentType.getInteger(context, "mode"), false))
                        .then(Commands.literal("permanent")
                                .executes(context -> set(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "mode"), true))))
                // 真伤诊断：不指定目标时自动取视线前方的生物
                .then(Commands.literal("diagnose")
                        .executes(context -> TrueDamageDiagnostics.diagnose(
                                context.getSource(), lookAt(context.getSource()), false))
                        .then(Commands.literal("deep")
                                .executes(context -> TrueDamageDiagnostics.diagnose(
                                        context.getSource(), lookAt(context.getSource()), true))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(context -> TrueDamageDiagnostics.diagnose(
                                                context.getSource(),
                                                EntityArgument.getEntity(context, "target"), true))))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> TrueDamageDiagnostics.diagnose(
                                        context.getSource(), EntityArgument.getEntity(context, "target"), false))))
                .then(Commands.literal("watch")
                        .executes(context -> TrueDamageDiagnostics.watch(
                                context.getSource(), lookAt(context.getSource()), 40))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> TrueDamageDiagnostics.watch(
                                        context.getSource(), EntityArgument.getEntity(context, "target"), 40))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 600))
                                        .executes(context -> TrueDamageDiagnostics.watch(
                                                context.getSource(),
                                                EntityArgument.getEntity(context, "target"),
                                                IntegerArgumentType.getInteger(context, "ticks"))))));

        for (String root : ROOT_NAMES) {
            dispatcher.register(Commands.literal(root)
                    .requires(source -> source.hasPermission(2))
                    .then(trueDamage));
        }
    }

    /** 未指定目标时：取视线前方 16 格内命中的生物（没有则返回执行者自己） */
    private static Entity lookAt(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return null;
        EntityHitResult hit = player.pick(16.0D, 1.0F, false) instanceof EntityHitResult result ? result : null;
        return hit != null && hit.getEntity() instanceof LivingEntity ? hit.getEntity() : player;
    }

    /** 查询：当前生效档位 + 配置文件中的档位（便于区分临时改动） */
    private static int query(CommandSourceStack source) {
        MAConfig.TrueDamageMode current = MAConfig.trueDamageMode;
        MAConfig.TrueDamageMode configured = MAConfig.SPEC.isLoaded()
                ? MAConfig.TrueDamageMode.byId(MAConfig.TRUE_DAMAGE_MODE.get())
                : current;
        source.sendSuccess(() -> Component.translatable("more_attributes.command.true_damage.query",
                describe(current), describe(configured)), false);
// 说明：返回值用档位编号（而不是枚举序号），避免枚举声明顺序变化时指令返回值跟着变
        return current.id();
    }

    private static int set(CommandSourceStack source, int id, boolean permanent) {
        MAConfig.TrueDamageMode mode = MAConfig.setTrueDamageMode(id, permanent);
        source.sendSuccess(() -> Component.translatable(
                permanent ? "more_attributes.command.true_damage.set_permanent"
                        : "more_attributes.command.true_damage.set_temp",
                describe(mode)), true);
        return mode.id();
    }

    /** 档位描述：数字 + 译名，例如「1（中）」 */
    private static Component describe(MAConfig.TrueDamageMode mode) {
        return Component.translatable(
                "more_attributes.command.true_damage.mode." + mode.name().toLowerCase(Locale.ROOT));
    }
}
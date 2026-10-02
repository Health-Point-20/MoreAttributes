package com.fu_meng.more_attributes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * 模组指令：在游戏内查看/切换真伤档位。
 * <ul>
 *   <li>{@code /more_attributes truedamage} / {@code /ma truedamage} —— 查询当前档位</li>
 *   <li>{@code /more_attributes truedamage <0-3>} —— 仅本次游戏生效</li>
 *   <li>{@code /more_attributes truedamage <0-3> permanent} —— 写入配置文件，永久生效</li>
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
                .then(Commands.argument("mode", IntegerArgumentType.integer(0, 3))
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                new String[]{"0", "1", "2", "3"}, builder))
                        .executes(context -> set(context.getSource(),
                                IntegerArgumentType.getInteger(context, "mode"), false))
                        .then(Commands.literal("permanent")
                                .executes(context -> set(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "mode"), true))));

        for (String root : ROOT_NAMES) {
            dispatcher.register(Commands.literal(root)
                    .requires(source -> source.hasPermission(2))
                    .then(trueDamage));
        }
    }

    /** 查询：当前生效档位 + 配置文件中的档位（便于区分临时改动） */
    private static int query(CommandSourceStack source) {
        MAConfig.TrueDamageMode current = MAConfig.trueDamageMode;
        MAConfig.TrueDamageMode configured = MAConfig.SPEC.isLoaded()
                ? MAConfig.TrueDamageMode.byId(MAConfig.TRUE_DAMAGE_MODE.get())
                : current;
        source.sendSuccess(() -> Component.translatable("more_attributes.command.true_damage.query",
                describe(current), describe(configured)), false);
        return current.ordinal();
    }

    private static int set(CommandSourceStack source, int id, boolean permanent) {
        MAConfig.TrueDamageMode mode = MAConfig.setTrueDamageMode(id, permanent);
        source.sendSuccess(() -> Component.translatable(
                permanent ? "more_attributes.command.true_damage.set_permanent"
                        : "more_attributes.command.true_damage.set_temp",
                describe(mode)), true);
        return mode.ordinal();
    }

    /** 档位描述：数字 + 译名，例如「1（中）」 */
    private static Component describe(MAConfig.TrueDamageMode mode) {
        return Component.translatable(
                "more_attributes.command.true_damage.mode." + mode.name().toLowerCase(Locale.ROOT));
    }
}
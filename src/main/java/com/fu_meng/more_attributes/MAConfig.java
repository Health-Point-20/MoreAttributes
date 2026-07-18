package com.fu_meng.more_attributes;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = MoreAttributesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MAConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue HUNGER_RATE_AFFECT_REGENERATION;
    public static final ForgeConfigSpec.BooleanValue STRONGER_TRUE_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue UNLIMITED_LIFT_STEAL;

    public static boolean hungerRateAffectRegeneration;
    public static boolean strongerTrueDamage;
    public static boolean unlimitedLiftSteal;

    static {
        BUILDER.push("More Attributes Config");
        HUNGER_RATE_AFFECT_REGENERATION = BUILDER.comment("Whether hunger rate affects regeneration rate.").comment("饥饿速率是否影响回血时的消耗").define("hungerRateAffectRegeneration", false);
        STRONGER_TRUE_DAMAGE = BUILDER.comment("Whether stronger true damage.").comment("是否启用更强硬的真伤").define("strongerTrueDamage", false);
        UNLIMITED_LIFT_STEAL = BUILDER.comment("Can life-steal absorb more health points than causing damage?").comment("生命窃取能否吸取比造成伤害更多的血量").define("unlimitedLiftSteal", false);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "more_attributes_config.toml");
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        loadConfig();
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        loadConfig();
    }

    private static void loadConfig() {
        hungerRateAffectRegeneration = HUNGER_RATE_AFFECT_REGENERATION.get();
        strongerTrueDamage = STRONGER_TRUE_DAMAGE.get();
        unlimitedLiftSteal = UNLIMITED_LIFT_STEAL.get();
    }
}
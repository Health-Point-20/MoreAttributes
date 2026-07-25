package com.fu_meng.more_attributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = MoreAttributesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MAConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue HUNGER_RATE_AFFECT_REGENERATION;
    public static final ForgeConfigSpec.BooleanValue STRONGER_TRUE_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue UNLIMITED_LIFT_STEAL;
    public static final ForgeConfigSpec.BooleanValue DISPLAY_MAGIC_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CONSIDERED_MAGIC_DAMAGE;

    public static boolean hungerRateAffectRegeneration;
    public static boolean strongerTrueDamage;
    public static boolean unlimitedLiftSteal;
    public static boolean displayMagicArmor;
    public static Set<ResourceLocation> consideredMagicDamage;

    static {
        BUILDER.push("More Attributes Config");
        HUNGER_RATE_AFFECT_REGENERATION = BUILDER.comment("Whether hunger rate affects regeneration rate.").comment("饥饿速率是否影响回血时的消耗").define("hungerRateAffectRegeneration", false);
        STRONGER_TRUE_DAMAGE = BUILDER.comment("Whether stronger true damage.").comment("是否启用更强硬的真伤").define("strongerTrueDamage", false);
        UNLIMITED_LIFT_STEAL = BUILDER.comment("Can life-steal absorb more health points than causing damage?").comment("生命窃取能否吸取比造成伤害更多的血量").define("unlimitedLiftSteal", false);
        DISPLAY_MAGIC_ARMOR = BUILDER.comment("Whether to display the magic armor bar in the HUD like vanilla armor.").comment("是否像原版护甲一样在HUD中显示魔法护甲条").define("displayMagicArmor", true);
        CONSIDERED_MAGIC_DAMAGE = BUILDER.comment("What kind of damage is considered magical damage").comment("什么伤害属于魔法伤害").defineList("consideredMagicDamage", List.of(
                "minecraft:magic",
                "minecraft:indirect_magic",
                "irons_spellbooks:fire_magic",
                "irons_spellbooks:ice_magic",
                "irons_spellbooks:lightning_magic",
                "irons_spellbooks:holy_magic",
                "irons_spellbooks:ender_magic",
                "irons_spellbooks:blood_magic",
                "irons_spellbooks:evocation_magic",
                "irons_spellbooks:poison_magic",
                "botania:magic"
        ), o -> o instanceof String);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "more_attributes_config.toml");
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        hungerRateAffectRegeneration = HUNGER_RATE_AFFECT_REGENERATION.get();
        strongerTrueDamage = STRONGER_TRUE_DAMAGE.get();
        unlimitedLiftSteal = UNLIMITED_LIFT_STEAL.get();
        displayMagicArmor = DISPLAY_MAGIC_ARMOR.get();
        consideredMagicDamage = parseResourceLocations(CONSIDERED_MAGIC_DAMAGE.get());
    }

    private static Set<ResourceLocation> parseResourceLocations(List<? extends String> list) {
        Set<ResourceLocation> result = new HashSet<>();
        for (String s : list) {
            ResourceLocation loc = ResourceLocation.tryParse(s);
            if (loc != null) {
                result.add(loc);
            } else {
                // 记录错误日志，方便玩家排查配置问题
                MoreAttributesMod.LOGGER.error("Invalid ResourceLocation format in config: '{}'. Skipping.", s);
            }
        }
        return result;
    }
}
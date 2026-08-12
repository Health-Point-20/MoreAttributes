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
    public static final ForgeConfigSpec.BooleanValue UNLIMITED_LIFE_STEAL;
    public static final ForgeConfigSpec.BooleanValue UNLIMITED_SPLASH_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue APOTHEOSIS_CRIT_COMPATIBILITY;
    public static final ForgeConfigSpec.BooleanValue DISPLAY_MAGIC_ARMOR;
    public static final ForgeConfigSpec.IntValue MAGIC_ARMOR_DISPLAY_MODE;
    public static final ForgeConfigSpec.IntValue MAGIC_ARMOR_NUMBER_POSITION;
    public static final ForgeConfigSpec.IntValue MAGIC_ARMOR_BAR_STYLE;
    public static final ForgeConfigSpec.IntValue MAGIC_ARMOR_BAR_MAX_VALUE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CONSIDERED_MAGIC_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue AGGRO_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MULTISHOT_SPREAD_PER_POINT;

    public static boolean hungerRateAffectRegeneration;
    public static boolean strongerTrueDamage;
    public static boolean unlimitedLifeSteal;
    public static boolean unlimitedSplashDamage;
    public static boolean ApotheosisCritCompatibility;
    public static boolean displayMagicArmor;
    public static int magicArmorDisplayMode;
    public static int magicArmorNumberPosition;
    public static int magicArmorBarStyle;
    public static int magicArmorBarMaxValue;
    public static Set<ResourceLocation> consideredMagicDamage;
    public static boolean aggroEnabled;
    public static double multishotSpreadPerPoint;

    static {
        BUILDER.push("More Attributes Config");
        HUNGER_RATE_AFFECT_REGENERATION = BUILDER.comment("Whether hunger rate affects regeneration rate.").comment("饥饿速率是否影响回血时的消耗").define("hungerRateAffectRegeneration", false);
        STRONGER_TRUE_DAMAGE = BUILDER.comment("Whether stronger true damage.").comment("是否启用更强硬的真伤").define("strongerTrueDamage", false);
        UNLIMITED_LIFE_STEAL = BUILDER.comment("Can life-steal absorb more health points than causing damage?").comment("生命窃取能否吸取比造成伤害更多的血量").define("unlimitedLiftSteal", false);
        UNLIMITED_SPLASH_DAMAGE = BUILDER.comment("Can melee splash damage more than causing damage?").comment("近战伤害溅射能溅射比造成伤害更高的伤害").define("unlimitedSplashDamage", false);
        APOTHEOSIS_CRIT_COMPATIBILITY = BUILDER.comment("Whether to stack this mod's crit chance and crit damage onto Apotheosis's corresponding attributes.").comment("是否将本模组的暴击率和暴击伤害属性值叠加到神化模组的对应属性上").define("apotheosisCritCompatibility", false);

        DISPLAY_MAGIC_ARMOR = BUILDER.comment("Whether to display the magic armor bar in the HUD like vanilla armor.").comment("是否像原版护甲一样在HUD中显示魔法护甲条").define("displayMagicArmor", true);
        MAGIC_ARMOR_DISPLAY_MODE = BUILDER.comment("Magic armor HUD display mode: 0 = icon strip (like vanilla armor), 1 = bar with number").comment("魔法护甲HUD显示模式：0 = 图标条（类似原版护甲），1 = 条状+数字").defineInRange("magicArmorDisplayMode", 0, 0, 1);
        MAGIC_ARMOR_NUMBER_POSITION = BUILDER.comment("Number position in bar mode: 0 = left side, 1 = center inside bar").comment("条状模式下数值位置：0 = 左侧，1 = 条正中").defineInRange("magicArmorNumberPosition", 0, 0, 1);
        MAGIC_ARMOR_BAR_STYLE = BUILDER.comment("Bar style in bar mode: 0 = vanilla style, 1 = smooth gradient style").comment("条状模式下条形风格：0 = 原版风格，1 = 平滑渐变风格").defineInRange("magicArmorBarStyle", 1, 0, 1);
        MAGIC_ARMOR_BAR_MAX_VALUE = BUILDER.comment("The magic armor value at which the bar is full in bar mode.").comment("条状模式下条形满格对应的魔法护甲值").defineInRange("magicArmorBarMaxValue", 20, 1, 10000);
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

        AGGRO_ENABLED = BUILDER.comment("Whether the aggro (threat) attribute is enabled.").comment("是否启用仇恨值属性").define("aggroEnabled", true);

        MULTISHOT_SPREAD_PER_POINT = BUILDER.comment("Spread angle per multishot point in degrees. Total spread grows with the attribute value.").comment("每点多重射击属性的散布角度（度），总散布角度随属性值增长").defineInRange("multishotSpreadPerPoint", 5.0, 0.1, 180.0);

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
        unlimitedSplashDamage = UNLIMITED_SPLASH_DAMAGE.get();
        unlimitedLifeSteal = UNLIMITED_LIFE_STEAL.get();
        ApotheosisCritCompatibility = APOTHEOSIS_CRIT_COMPATIBILITY.get();
        displayMagicArmor = DISPLAY_MAGIC_ARMOR.get();
        magicArmorDisplayMode = MAGIC_ARMOR_DISPLAY_MODE.get();
        magicArmorNumberPosition = MAGIC_ARMOR_NUMBER_POSITION.get();
        magicArmorBarStyle = MAGIC_ARMOR_BAR_STYLE.get();
        magicArmorBarMaxValue = MAGIC_ARMOR_BAR_MAX_VALUE.get();
        consideredMagicDamage = parseResourceLocations(CONSIDERED_MAGIC_DAMAGE.get());
        aggroEnabled = AGGRO_ENABLED.get();
        multishotSpreadPerPoint = MULTISHOT_SPREAD_PER_POINT.get();
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
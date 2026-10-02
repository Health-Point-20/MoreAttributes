package com.fu_meng.more_attributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = MoreAttributesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MAConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue HUNGER_RATE_AFFECT_REGENERATION;
    public static final ForgeConfigSpec.BooleanValue REGEN_PER_TICK;
    public static final ForgeConfigSpec.IntValue TRUE_DAMAGE_MODE;
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
    public static final ForgeConfigSpec.BooleanValue MULTISHOT_CLONE_INHERITS_BOUNCE;
    public static final ForgeConfigSpec.BooleanValue HEALTH_DAMAGE_PLAYER_ONLY;
    public static final ForgeConfigSpec.BooleanValue HEALTH_DAMAGE_ONCE_PER_TICK;
    public static final ForgeConfigSpec.DoubleValue DAMAGE_SHARING_RANGE;
    public static final ForgeConfigSpec.DoubleValue DAMAGE_SHARING_DEFAULT_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue ARROW_BOUNCE_SEARCH_RANGE;
    public static final ForgeConfigSpec.DoubleValue ARROW_BOUNCE_DAMAGE_FALLOFF;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXECUTE_THRESHOLD_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_ATTRIBUTES;
    public static final ForgeConfigSpec.DoubleValue FLAT_DAMAGE_REDUCTION_MIN_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue FLAT_DAMAGE_REDUCTION_AFFECTS_ENVIRONMENTAL;
    public static final ForgeConfigSpec.BooleanValue DEBUG_PROBES;

    public static boolean hungerRateAffectRegeneration;
    public static boolean regenPerTick;
    public static TrueDamageMode trueDamageMode = TrueDamageMode.DEFAULT;
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
    public static boolean multishotCloneInheritsBounce;
    public static boolean healthDamagePlayerOnly;
    public static boolean healthDamageOncePerTick;
    public static double damageSharingRange;
    public static double damageSharingDefaultWeight;
    public static double arrowBounceSearchRange;
    public static double arrowBounceDamageFalloff;
    public static Set<ResourceLocation> executeThresholdBlacklist;
    private static volatile Set<ResourceLocation> disabledAttributes;
    /** 固定减伤属性减伤后至少保留的伤害值 */
    public static double flatDamageReductionMinDamage;
    /** 固定减伤属性是否同样减少环境伤害 */
    public static boolean flatDamageReductionAffectsEnvironmental;
    /** 真伤强制写入的行为探针诊断日志开关（排查“扣血无效”时打开） */
    public static boolean debugProbes;

    static {
        BUILDER.push("More Attributes Config");
        HUNGER_RATE_AFFECT_REGENERATION = BUILDER.comment("Whether hunger rate affects regeneration rate.").comment("饥饿速率是否影响回血时的消耗").define("hungerRateAffectRegeneration", false);
        REGEN_PER_TICK = BUILDER.comment("When true, the regeneration attribute heals 1/20 of its value every tick (smoothed across the second, current default behavior). When false, the full attribute value is healed once per second (in a single burst every 20 ticks).").comment("再生属性回血模式。true时每tick恢复属性值的1/20（平摊到整秒，即当前默认行为）；false时每秒按完整属性值集中恢复一次（每20tick触发一次）").define("regenPerTick", true);
        TRUE_DAMAGE_MODE = BUILDER.comment(
                "True damage tier: higher tiers cover more mobs.",
                "0 (low): most mobs; tops up the true damage.",
                "1 (medium): almost all mobs; rewrites health via reflection.",
                "2 (high): nearly every mob; also rewrites custom health.",
                "3 (highest): every mob whose health is not fixed."
        ).comment(
                "真伤档位：档位越高，生效范围越广。",
                "0（低）：对大部分生物生效，尝试补足真伤部分的伤害。",
                "1（中）：对绝大部分生物生效，用反射改写生命值来附加真伤。",
                "2（高）：对基本所有生物生效，会尝试改写生物的自定义血量。",
                "3（最高）：对所有生命值非固定的生物生效。"
        ).defineInRange("trueDamageMode", 1, 0, 3);
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
                "irons_spellbooks:nature_magic",
                "irons_spellbooks:eldritch_magic",
                "botania:magic"
        ), o -> o instanceof String);

        AGGRO_ENABLED = BUILDER.comment("Whether the aggro (threat) attribute is enabled.").comment("是否启用仇恨值属性").define("aggroEnabled", true);

        MULTISHOT_SPREAD_PER_POINT = BUILDER.comment("Spread angle per multishot point in degrees. Total spread grows with the attribute value.").comment("每点多重射击属性的散布角度（度），总散布角度随属性值增长").defineInRange("multishotSpreadPerPoint", 5.0, 0.1, 180.0);
        MULTISHOT_CLONE_INHERITS_BOUNCE = BUILDER.comment("Whether multishot clones inherit the arrow bounce state from the original arrow. When true, each clone carries the same remaining bounce count as the original (bounce charges are effectively multiplied by the number of clones); when false, multishot clones never bounce.").comment("多重射击克隆箭是否继承原箭的弹射状态。true 时每支克隆箭携带与原箭相同的剩余弹射次数（弹射次数随克隆数量成倍放大）；false 时多重射击克隆箭不触发弹射").define("multishotCloneInheritsBounce", true);

        HEALTH_DAMAGE_PLAYER_ONLY = BUILDER.comment("Whether health-based damage bonus (max health damage / current health damage) is only triggered by the player's direct melee attacks.").comment("是否仅玩家直接攻击（近战）才能触发生命值相关伤害提升（最大生命值伤害与当前生命值伤害）").define("healthDamagePlayerOnly", false);
        HEALTH_DAMAGE_ONCE_PER_TICK = BUILDER.comment("Whether each attacker can only trigger health-based damage bonus once per tick.").comment("每tick每个攻击者仅能触发一次生命值相关的伤害提升").define("healthDamageOncePerTick", false);

        DAMAGE_SHARING_RANGE = BUILDER.comment("Search radius (in blocks) for allies when sharing damage via the damage_sharing attribute.").comment("伤害分摊属性生效时搜索友方的半径（方块距离）").defineInRange("damageSharingRange", 16.0, 0.0, 1024.0);
        DAMAGE_SHARING_DEFAULT_WEIGHT = BUILDER.comment("Default weight added to every participant's damage sharing weight. Guarantees the victim always retains a non-zero share.").comment("每个分摊参与者的默认权重（加到属性值上），保证受害者始终保留非零份额").defineInRange("damageSharingDefaultWeight", 1.0, 0.0, Double.MAX_VALUE);
        ARROW_BOUNCE_SEARCH_RANGE = BUILDER.comment("Search radius (in blocks) for the next target when an arrow bounces.").comment("箭矢弹射时搜索下一个目标的半径（方块距离）").defineInRange("arrowBounceSearchRange", 16.0, 0.0, 1024.0);
        ARROW_BOUNCE_DAMAGE_FALLOFF = BUILDER.comment("Damage multiplier applied per arrow bounce (0.8 = each bounce deals 80% of previous damage).").comment("每次箭矢弹射的伤害衰减系数（0.8 = 每次弹射造成上一次80%的伤害）").defineInRange("arrowBounceDamageFalloff", 0.8, 0.0, 1.0);
        EXECUTE_THRESHOLD_BLACKLIST = BUILDER.comment("Entity types that can never be executed by the execute_threshold attribute, e.g. bosses. Uses entity type IDs like 'minecraft:ender_dragon'.").comment("永远无法被斩杀阈值属性处决的生物类型列表（如Boss）。使用实体类型ID，如 'minecraft:ender_dragon'").defineList("executeThresholdBlacklist", List.of(
                "minecraft:ender_dragon",
                "minecraft:wither"
        ), o -> o instanceof String);

        DISABLED_ATTRIBUTES = BUILDER.comment("Attributes that should be disabled. A disabled attribute always returns its default value, produces no effect, and is hidden from item tooltips.").comment("需要禁用的属性列表。被禁用的属性在获取时始终返回默认值、不产生任何效果，且不在物品提示（tooltip）中显示。").defineList("disabledAttributes", List.of(), o -> o instanceof String);

        FLAT_DAMAGE_REDUCTION_MIN_DAMAGE = BUILDER.comment("The minimum damage that remains after the flat_damage_reduction attribute reduces incoming damage. It never reduces damage below this value.").comment("固定减伤属性减少受到的伤害后，至少保留的伤害值。固定减伤不会使伤害低于此值").defineInRange("flatDamageReductionMinDamage", 0.0, 0.0, Double.MAX_VALUE);

        FLAT_DAMAGE_REDUCTION_AFFECTS_ENVIRONMENTAL = BUILDER.comment("Whether the flat_damage_reduction attribute also reduces environmental damage (damage whose source has no entity, e.g. fire, fall, drowning).").comment("固定减伤属性是否同样减少环境伤害（无来源实体的伤害，如着火、摔落、溺水）").define("flatDamageReductionAffectsEnvironmental", false);

        DEBUG_PROBES = BUILDER.comment(
                "Whether to log the behaviour probes of the forced true-damage health writes (which layer wrote the health, why a probe was skipped or rolled back).",
                "Only for troubleshooting 'true damage does nothing'; verbose, leave off in normal play."
        ).comment(
                "是否输出真伤强制写入的行为探针诊断日志（哪一层写入了血量、某次探针为何被跳过或还原）。",
                "仅在排查“真伤打不动某个怪”时开启；输出较啰嗦，正常游玩请保持关闭。"
        ).define("debugProbes", false);

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
        regenPerTick = REGEN_PER_TICK.get();
        trueDamageMode = TrueDamageMode.byId(TRUE_DAMAGE_MODE.get());
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
        multishotCloneInheritsBounce = MULTISHOT_CLONE_INHERITS_BOUNCE.get();
        healthDamagePlayerOnly = HEALTH_DAMAGE_PLAYER_ONLY.get();
        healthDamageOncePerTick = HEALTH_DAMAGE_ONCE_PER_TICK.get();
        damageSharingRange = DAMAGE_SHARING_RANGE.get();
        damageSharingDefaultWeight = DAMAGE_SHARING_DEFAULT_WEIGHT.get();
        arrowBounceSearchRange = ARROW_BOUNCE_SEARCH_RANGE.get();
        arrowBounceDamageFalloff = ARROW_BOUNCE_DAMAGE_FALLOFF.get();
        executeThresholdBlacklist = parseResourceLocations(EXECUTE_THRESHOLD_BLACKLIST.get());
        disabledAttributes = parseResourceLocations(DISABLED_ATTRIBUTES.get());
        flatDamageReductionMinDamage = FLAT_DAMAGE_REDUCTION_MIN_DAMAGE.get();
        flatDamageReductionAffectsEnvironmental = FLAT_DAMAGE_REDUCTION_AFFECTS_ENVIRONMENTAL.get();
        debugProbes = DEBUG_PROBES.get();
    }

    /**
     * 判断属性是否被禁用
     */
    public static boolean isAttributeDisabled(Attribute attribute) {
        if (disabledAttributes.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        return key != null && disabledAttributes.contains(key);
    }

    /**
     * 运行时设置真伤档位（供游戏内指令调用）。
     *
     * @param id        目标档位（0-3，越界时回退到默认档）
     * @param permanent true 时同时写入配置文件，重启后仍生效；false 仅本次游戏生效
     * @return 实际生效的档位
     */
    public static TrueDamageMode setTrueDamageMode(int id, boolean permanent) {
        TrueDamageMode mode = TrueDamageMode.byId(id);
        trueDamageMode = mode;
        if (permanent && SPEC.isLoaded()) {
            TRUE_DAMAGE_MODE.set(mode.ordinal());
            SPEC.save();
        }
        return mode;
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

    /**
     * 真伤强制写入深度分级。每一档在上一档基础上增加一层兜底，覆盖更广、开销更大：
     * <ul>
     *   <li>{@link #LOW} 档 0：真伤并入事件伤害，走原版管线（原版兼容）。</li>
     *   <li>{@link #DEFAULT} 档 1：脱离管线强制写入，仅 {@code setHealth} + 原版同步血量。</li>
     *   <li>{@link #HIGH} 档 2：档 1 再加类字段探针与深度对象图遍历（纯反射，不加载引用类、不用 Unsafe）。</li>
     *   <li>{@link #HIGHEST} 档 3：档 2 再加引用类静态表查找（字节码 + Unsafe）与加密血量字符串改写，
     *       并绕过死亡保护。</li>
     * </ul>
     * 无敌帧不再是分档维度：档 1 及以上恒定足额结算、不参与原版无敌帧
     * （档 0 走原版管线，自然受其约束）。
     */
    public enum TrueDamageMode {
        LOW,
        DEFAULT,
        HIGH,
        HIGHEST;

        /** 按配置 ID 取档位，越界或非法值回退到默认档 {@link #DEFAULT} */
        public static TrueDamageMode byId(int id) {
            TrueDamageMode[] values = values();
            return id >= 0 && id < values.length ? values[id] : DEFAULT;
        }

        /** 是否脱离伤害管线独立结算真伤 */
        public boolean bypassesPipeline() {
            return this != LOW;
        }

        /** 是否做类字段探针与深度对象图遍历（纯反射兜底） */
        public boolean probesHealthBacking() {
            return this == HIGH || this == HIGHEST;
        }

        /** 是否解析实体字节码常量池、在可达模组的静态表里查找并直写血量（依赖 Unsafe） */
        public boolean searchesReferencedTables() {
            return this == HIGHEST;
        }

        /** 是否改写「加密字符串血量」（用实体自己的加/解密方法还原明文后重写密文） */
        public boolean rewritesEncryptedHealth() {
            return this == HIGHEST;
        }

        /** 是否调用「模组自己的血量写入口」（血量存在混淆位池 / 堆外内存等无法直接写的存储里时） */
        public boolean invokesHealthWriters() {
            return this == HIGHEST;
        }

        /** 是否绕过死亡保护（不死图腾 + 他模组对死亡事件的取消） */
        public boolean ignoresDeathProtection() {
            return this == HIGHEST;
        }
    }
}
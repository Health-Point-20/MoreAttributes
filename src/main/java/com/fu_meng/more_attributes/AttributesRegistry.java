package com.fu_meng.more_attributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Arrays;
import java.util.List;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class AttributesRegistry {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, MoreAttributesMod.MODID);
    private static final double MIN = -Double.MAX_VALUE;
    private static final double MAX = Double.MAX_VALUE;

    public static final RegistryObject<Attribute> INCOMING_DAMAGE_CAP = ATTRIBUTES.register("incoming_damage_cap",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".incoming_damage_cap", 0.0, -1.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EVASION = ATTRIBUTES.register("evasion",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".evasion", 0.0, 0.0, 1.0).setSyncable(true));

    public static final RegistryObject<Attribute> HIT_RATE = ATTRIBUTES.register("hit_rate",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".hit_rate", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> FINAL_HIT_RATE = ATTRIBUTES.register("final_hit_rate",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".final_hit_rate", 0.0, MIN, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> FINAL_EVASION = ATTRIBUTES.register("final_evasion",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".final_evasion", 0.0, MIN, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> IGNORE_INVINCIBLE_TIME = ATTRIBUTES.register("ignore_invincible_time",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".ignore_invincible_time", 0.0, MIN, 1).setSyncable(true));

    public static final RegistryObject<Attribute> INVINCIBLE_TIME = ATTRIBUTES.register("invincible_time",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".invincible_time", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> PHYSICAL_DAMAGE_REDUCTION = ATTRIBUTES.register("physical_damage_reduction",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".physical_damage_reduction", 0.0, MIN, 1).setSyncable(true));

    public static final RegistryObject<Attribute> ENVIRONMENTAL_DAMAGE_REDUCTION = ATTRIBUTES.register("environmental_damage_reduction",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".environmental_damage_reduction", 0.0, MIN, 1).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_DAMAGE_REDUCTION = ATTRIBUTES.register("magic_damage_reduction",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_damage_reduction", 0.0, MIN, 1).setSyncable(true));

    public static final RegistryObject<Attribute> DAMAGE_REDUCTION = ATTRIBUTES.register("damage_reduction",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".damage_reduction", 0.0, MIN, 1).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_ARMOR = ATTRIBUTES.register("magic_armor",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_armor", 0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_ARMOR_TOUGHNESS = ATTRIBUTES.register("magic_armor_toughness",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_armor_toughness", 0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EATING_SPEED = ATTRIBUTES.register("eating_speed",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".eating_speed", 1.0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> DRAW_SPEED = ATTRIBUTES.register("draw_speed",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".draw_speed", 1.0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> ARROW_DAMAGE = ATTRIBUTES.register("arrow_damage",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".arrow_damage", 1.0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> ARROW_VELOCITY = ATTRIBUTES.register("arrow_velocity",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".arrow_velocity", 1.0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> CRITICAL_CHANCE = ATTRIBUTES.register("critical_chance",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".critical_chance", 0.0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> CRITICAL_DAMAGE = ATTRIBUTES.register("critical_damage",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".critical_damage", 1.5, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> HEALTH_REGEN = ATTRIBUTES.register("health_regen",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".health_regen", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> LIFE_STEAL = ATTRIBUTES.register("life_steal",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".life_steal", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MELEE_LIFE_STEAL = ATTRIBUTES.register("melee_life_steal",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".melee_life_steal", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> RANGED_LIFE_STEAL = ATTRIBUTES.register("ranged_life_steal",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".ranged_life_steal", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_LIFE_STEAL = ATTRIBUTES.register("magic_life_steal",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_life_steal", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> HUNGER_RATE = ATTRIBUTES.register("hunger_rate",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".hunger_rate", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> OXYGEN_CONSUMPTION_RATE = ATTRIBUTES.register("oxygen_consumption_rate",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".oxygen_consumption_rate", 1.0, MIN, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> TRUE_DAMAGE_RATIO = ATTRIBUTES.register("true_damage_ratio",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".true_damage_ratio", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EXTRA_TRUE_DAMAGE = ATTRIBUTES.register("extra_true_damage",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".extra_true_damage", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_DAMAGE_RATIO = ATTRIBUTES.register("magic_damage_ratio",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_damage_ratio", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EXTRA_MAGIC_DAMAGE = ATTRIBUTES.register("extra_magic_damage",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".extra_magic_damage", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_DAMAGE_MULTIPLIER = ATTRIBUTES.register("magic_damage_multiplier",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".magic_damage_multiplier", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MINING_SPEED = ATTRIBUTES.register("mining_speed",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".mining_speed", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> HEALING_EFFICIENCY = ATTRIBUTES.register("healing_efficiency",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".healing_efficiency", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> PERCENT_HEALTH_REGEN = ATTRIBUTES.register("percent_health_regen",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".percent_health_regen", 0, 0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MINIMUM_INJURY_INTERVAL = ATTRIBUTES.register("minimum_injury_interval",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".minimum_injury_interval", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> DAMAGE_MULTIPLIER = ATTRIBUTES.register("damage_multiplier",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".damage_multiplier", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> SCALE = ATTRIBUTES.register("scale",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".scale", 1.0, 0.0, 100).setSyncable(true));

    public static final RegistryObject<Attribute> COOLDOWN_REDUCTION = ATTRIBUTES.register("cooldown_reduction",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".cooldown_reduction", 0.0, MIN, 1.0).setSyncable(true));

    public static final RegistryObject<Attribute> DAMAGE_REFLECTION = ATTRIBUTES.register("damage_reflection",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".damage_reflection", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EXECUTE_THRESHOLD = ATTRIBUTES.register("execute_threshold",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".execute_threshold", 0.0, -1.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> BACKSTAB_DAMAGE = ATTRIBUTES.register("backstab_damage",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".backstab_damage", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> BACKSTAB_RESISTANCE = ATTRIBUTES.register("backstab_resistance",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".backstab_resistance", 0.0, 0.0, 1.0).setSyncable(true));

    public static final RegistryObject<Attribute> AGGRO = ATTRIBUTES.register("aggro",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".aggro", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> CLIMBING_SPEED = ATTRIBUTES.register("climbing_speed",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".climbing_speed", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> EXPERIENCE_GAIN_RATIO = ATTRIBUTES.register("experience_gain_ratio",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".experience_gain_ratio", 1.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> PICKUP_RADIUS = ATTRIBUTES.register("pickup_radius",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".pickup_radius", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MELEE_SPLASH_RANGE = ATTRIBUTES.register("melee_splash_range",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".melee_splash_range", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MELEE_SPLASH_RATIO = ATTRIBUTES.register("melee_splash_ratio",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".melee_splash_ratio", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> MULTISHOT_AMOUNT = ATTRIBUTES.register("multishot_amount",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".multishot_amount", 0.0, 0.0, MAX).setSyncable(true));

    public static final RegistryObject<Attribute> ARROW_PIERCE = ATTRIBUTES.register("arrow_pierce",
            () -> new RangedAttribute("attribute." + MoreAttributesMod.MODID + ".arrow_pierce", 0.0, 0.0, MAX).setSyncable(true));

    @SubscribeEvent
    public static void addAttributes(EntityAttributeModificationEvent event) {
        List<RegistryObject<Attribute>> attributesForLiving = Arrays.asList(
                INCOMING_DAMAGE_CAP,
                EVASION,
                HIT_RATE,
                FINAL_EVASION,
                FINAL_HIT_RATE,
                INVINCIBLE_TIME,
                IGNORE_INVINCIBLE_TIME,
                PHYSICAL_DAMAGE_REDUCTION,
                MAGIC_DAMAGE_REDUCTION,
                ENVIRONMENTAL_DAMAGE_REDUCTION,
                DAMAGE_REDUCTION,
                MAGIC_ARMOR_TOUGHNESS,
                MAGIC_ARMOR,
                ARROW_DAMAGE,
                ARROW_VELOCITY,
                CRITICAL_CHANCE,
                CRITICAL_DAMAGE,
                HEALTH_REGEN,
                LIFE_STEAL,
                MELEE_LIFE_STEAL,
                RANGED_LIFE_STEAL,
                MAGIC_LIFE_STEAL,
                TRUE_DAMAGE_RATIO,
                EXTRA_TRUE_DAMAGE,
                MAGIC_DAMAGE_RATIO,
                EXTRA_MAGIC_DAMAGE,
                MAGIC_DAMAGE_MULTIPLIER,
                HEALING_EFFICIENCY,
                PERCENT_HEALTH_REGEN,
                DAMAGE_MULTIPLIER,
                MINIMUM_INJURY_INTERVAL,
                OXYGEN_CONSUMPTION_RATE,
                SCALE,
                DAMAGE_REFLECTION,
                EXECUTE_THRESHOLD,
                BACKSTAB_DAMAGE,
                BACKSTAB_RESISTANCE,
                CLIMBING_SPEED,
                MELEE_SPLASH_RANGE,
                MELEE_SPLASH_RATIO,
                ARROW_PIERCE,
                MULTISHOT_AMOUNT
        );

        List<RegistryObject<Attribute>> attributesForPlayer = Arrays.asList(
                EATING_SPEED,
                DRAW_SPEED,
                HUNGER_RATE,
                MINING_SPEED,
                COOLDOWN_REDUCTION,
                AGGRO,
                EXPERIENCE_GAIN_RATIO,
                PICKUP_RADIUS
        );

        for (RegistryObject<Attribute> attribute : attributesForLiving) {
            event.getTypes().stream()
                    .filter(type -> type.getBaseClass().isAssignableFrom(LivingEntity.class))
                    .forEach(type -> event.add(type, attribute.get()));
        }

        for (RegistryObject<Attribute> attribute : attributesForPlayer) {
            event.getTypes().stream()
                    .filter(type -> type.getBaseClass().isAssignableFrom(Player.class))
                    .forEach(type -> event.add(type, attribute.get()));
        }
    }

    // ================= 神化模组兼容 =================
    private static Attribute apotheosisCritChance = null;
    private static Attribute apotheosisCritDamage = null;
    private static boolean apotheosisAttrsResolved = false;

    /**
     * 通过 ForgeRegistries 懒加载查找神化模组的暴击率属性。
     * 若神化未安装则返回 null。
     */
    public static Attribute getApotheosisCritChance() {
        resolveApotheosisAttributes();
        return apotheosisCritChance;
    }

    /**
     * 通过 ForgeRegistries 懒加载查找神化模组的暴击伤害属性。
     * 若神化未安装则返回 null。
     */
    public static Attribute getApotheosisCritDamage() {
        resolveApotheosisAttributes();
        return apotheosisCritDamage;
    }

    private static void resolveApotheosisAttributes() {
        if (apotheosisAttrsResolved) return;
        apotheosisAttrsResolved = true;
        if (ModList.get().isLoaded("attributeslib")) {
            apotheosisCritChance = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("attributeslib:crit_chance"));
            apotheosisCritDamage = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("attributeslib:crit_damage"));
        }
    }
}
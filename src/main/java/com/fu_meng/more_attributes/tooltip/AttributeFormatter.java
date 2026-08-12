package com.fu_meng.more_attributes.tooltip;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

import static com.fu_meng.more_attributes.AttributesRegistry.*;

public class AttributeFormatter {

    public static MutableComponent formatAttribute(Attribute attribute, AttributeModifier modifier, TooltipFlag flag) {
        double itemValue = modifier.getAmount();

        if (itemValue == 0) {
            return null;
        }

        String attributeName = getLocalizedAttributeName(attribute);

        if (attribute.equals(INCOMING_DAMAGE_CAP.get())) {
            return formatIncomingDamageCap(itemValue, attributeName, modifier, flag);
        }

        if (attribute.equals(EXECUTE_THRESHOLD.get())) {
            return formatExecuteThreshold(itemValue, attributeName, modifier, flag);
        }

        // 非 ADDITION 操作追加"效力"后缀以示区分（空格由语言文件控制，适配中英文排版差异）
        if (modifier.getOperation() != AttributeModifier.Operation.ADDITION) {
            attributeName += Component.translatable("attribute.modifier.potency").getString();
        }

        return formatPercentage(itemValue, attributeName, modifier, flag);
    }

    private static MutableComponent formatIncomingDamageCap(double itemValue, String attributeName, AttributeModifier modifier, TooltipFlag flag) {
        MutableComponent comp;
        if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
            String formattedValue = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(itemValue);
            comp = Component.literal(attributeName + ": " + formattedValue).withStyle(ChatFormatting.BLUE);
        } else {
            double percentage = (1 + itemValue) * 100;
            String formattedValue = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(percentage);
            comp = Component.literal(attributeName + ": " + formattedValue + "%").withStyle(ChatFormatting.BLUE);
        }

        if (flag.isAdvanced()) {
            comp.append(Component.literal(" " + formatAdvancedTooltip(itemValue, modifier)).withStyle(ChatFormatting.GRAY));
        }
        return comp;
    }

    /**
     * 斩杀阈值的 tooltip 格式化：
     * ADDITION 显示固定数值，MULTIPLY 显示 amount×100% 的最大生命值百分比。
     */
    private static MutableComponent formatExecuteThreshold(double itemValue, String attributeName, AttributeModifier modifier, TooltipFlag flag) {
        MutableComponent comp;
        if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
            String formattedValue = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(itemValue);
            comp = Component.literal(attributeName + ": " + formattedValue).withStyle(ChatFormatting.BLUE);
        } else {
            double percentage = itemValue * 100;
            String formattedValue = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(percentage);
            comp = Component.literal(attributeName + ": " + formattedValue + "%").withStyle(ChatFormatting.BLUE);
        }

        if (flag.isAdvanced()) {
            comp.append(Component.literal(" " + formatAdvancedTooltip(itemValue, modifier)).withStyle(ChatFormatting.GRAY));
        }
        return comp;
    }

    private static final List<Attribute> percentageAttributes = List.of(
            EVASION.get(), HIT_RATE.get(),
            FINAL_EVASION.get(), FINAL_HIT_RATE.get(),
            IGNORE_INVINCIBLE_TIME.get(), INVINCIBLE_TIME.get(),
            PHYSICAL_DAMAGE_REDUCTION.get(), MAGIC_DAMAGE_REDUCTION.get(),
            DAMAGE_REDUCTION.get(), ENVIRONMENTAL_DAMAGE_REDUCTION.get(),
            TRUE_DAMAGE_RATIO.get(), MAGIC_DAMAGE_RATIO.get(),
            EATING_SPEED.get(), DRAW_SPEED.get(),
            ARROW_DAMAGE.get(), ARROW_VELOCITY.get(),
            CRITICAL_CHANCE.get(), CRITICAL_DAMAGE.get(),
            MINING_SPEED.get(), HUNGER_RATE.get(),
            PERCENT_HEALTH_REGEN.get(), HEALING_EFFICIENCY.get(),
            MAGIC_DAMAGE_MULTIPLIER.get(), DAMAGE_MULTIPLIER.get(),
            OXYGEN_CONSUMPTION_RATE.get(), SCALE.get(),
            BACKSTAB_DAMAGE.get(), BACKSTAB_RESISTANCE.get(),
            CLIMBING_SPEED.get(), EXPERIENCE_GAIN_RATIO.get(),
            AGGRO.get()
    );

    public static boolean isPercentageAttribute(Attribute attribute) {
        return percentageAttributes.contains(attribute);
    }

    /**
     * 判断该属性是否需要用自定义格式替换原版提示行（百分比属性、伤害上限与斩杀阈值）。
     */
    public static boolean isCustomFormattedAttribute(Attribute attribute) {
        return isPercentageAttribute(attribute)
                || attribute.equals(INCOMING_DAMAGE_CAP.get())
                || attribute.equals(EXECUTE_THRESHOLD.get());
    }

    private static String getLocalizedAttributeName(Attribute attribute) {
        // 获取本地化的属性名称
        return Component.translatable(attribute.getDescriptionId()).getString();
    }

    private static MutableComponent formatPercentage(double itemValue, String name, AttributeModifier modifier, TooltipFlag flag) {
        double percentage = itemValue * 100;
        String formattedValue = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(percentage);

        MutableComponent comp;
        if (itemValue > 0) {
            comp = Component.literal("+" + formattedValue + "% " + name).withStyle(ChatFormatting.BLUE);
        } else {
            comp = Component.literal(formattedValue + "% " + name).withStyle(ChatFormatting.RED);
        }

        if (flag.isAdvanced()) {
            comp.append(Component.literal(" " + formatAdvancedTooltip(itemValue, modifier)).withStyle(ChatFormatting.GRAY));
        }
        return comp;
    }

    private static String formatAdvancedTooltip(double value, AttributeModifier modifier) {
        String formatted = ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(value);
        return switch (modifier.getOperation()) {
            case ADDITION -> value > 0 ? "[+" + formatted + "]" : "[" + formatted + "]";
            case MULTIPLY_BASE -> value > 0 ? "[+" + formatted + "x]" : "[" + formatted + "x]";
            case MULTIPLY_TOTAL -> "[x" + formatted + "]";
        };
    }
}

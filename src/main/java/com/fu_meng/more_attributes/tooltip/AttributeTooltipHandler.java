package com.fu_meng.more_attributes.tooltip;

import com.google.common.collect.Multimap;
import dev.shadowsoffire.attributeslib.api.client.AddAttributeTooltipsEvent;
import dev.shadowsoffire.attributeslib.api.client.GatherSkippedAttributeTooltipsEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

import static com.fu_meng.more_attributes.MAConfig.isAttributeDisabled;

/**
 * Tooltip 处理器。
 * <p>
 * attributeslib 相关的监听器在 {@link ApophicTooltipCompatibility} 中，仅在 attributeslib 存在时注册。
 */
@Mod.EventBusSubscriber
public class AttributeTooltipHandler {

    /**
     * 无 attributeslib 时的处理：监听 ItemTooltipEvent，删除原版行并插入自定义格式行。
     * 有 attributeslib 时跳过（由 ApophicTooltipCompatibility 接管）。
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (ModList.get().isLoaded("attributeslib")) return;

        ItemStack stack = event.getItemStack();
        List<Component> tooltips = event.getToolTip();
        TooltipFlag flag = event.getFlags();
        Player player = event.getEntity();

        if (player == null) return;

        if (stack.getTag() != null && (stack.getTag().getInt("HideFlags") & 2) != 0) return;

        // 处理所有装备槽位的属性
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            Multimap<Attribute, AttributeModifier> attributes = stack.getAttributeModifiers(slot);

            for (Map.Entry<Attribute, AttributeModifier> entry : attributes.entries()) {
                Attribute attribute = entry.getKey();
                AttributeModifier modifier = entry.getValue();

                // 被禁用的属性：删除原版属性行，且不显示自定义格式行
                if (isAttributeDisabled(attribute)) {
                    removeAttributeLineAndGetIndex(tooltips, attribute);
                    continue;
                }

                if (AttributeFormatter.isCustomFormattedAttribute(attribute)) {
                    int insertIndex = removeAttributeLineAndGetIndex(tooltips, attribute);
                    MutableComponent formatted = AttributeFormatter.formatAttribute(attribute, modifier, flag);
                    if (formatted != null) {
                        if (insertIndex >= 0 && insertIndex <= tooltips.size()) {
                            tooltips.add(insertIndex, formatted);
                        } else {
                            tooltips.add(formatted);
                        }
                    }
                }
            }
        }
    }

    private static int removeAttributeLineAndGetIndex(List<Component> tooltips, Attribute attribute) {
        String attributeKey = attribute.getDescriptionId();
        for (int i = 0; i < tooltips.size(); i++) {
            String text = tooltips.get(i).getContents().toString();
            if (text.contains(attributeKey)) {
                tooltips.remove(i);
                return i;
            }
        }
        return -1;
    }

    /**
     * attributeslib 相关的监听器（仅在 attributeslib 存在时手动注册）。
     * <p>
     * 在 MoreAttributesMod 中条件注册。
     */
    public static class ApophicTooltipCompatibility {

        /**
         * 第一步：跳过本模组处理的属性的 modifier。
         * <p>
         * 被跳过的 modifier 在 attributeslib 的合并逻辑执行前就被移除，因此不参与合并。
         * 这避免了两个 +90% 闪避被合并为 +180% 的问题（实际是乘法叠加）。
         * 被禁用的属性同样被跳过，即完全不显示。
         */
        @SubscribeEvent
        public static void onGatherSkippedAttributeTooltips(GatherSkippedAttributeTooltipsEvent event) {
            ItemStack stack = event.getStack();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                Multimap<Attribute, AttributeModifier> attributes = stack.getAttributeModifiers(slot);
                for (Map.Entry<Attribute, AttributeModifier> entry : attributes.entries()) {
                    Attribute attribute = entry.getKey();
                    if (isAttributeDisabled(attribute) || AttributeFormatter.isCustomFormattedAttribute(attribute)) {
                        event.skipUUID(entry.getValue().getId());
                    }
                }
            }
        }

        /**
         * 第二步：在 attributeslib 生成属性段之后，添加本模组的格式。
         * <p>
         * 本模组的 modifier 已在 GatherSkippedAttributeTooltipsEvent 中被跳过（不参与合并、不显示），
         * 此处用原始 AttributeFormatter 逐个 modifier 重新生成，保持百分比格式和 INCOMING_DAMAGE_CAP 特殊格式。
         */
        @SubscribeEvent
        public static void onAddAttributeTooltips(AddAttributeTooltipsEvent event) {
            ItemStack stack = event.getStack();
            TooltipFlag flag = event.getFlags();
            var it = event.getAttributeTooltipIterator();

            // iterator 指向属性段末尾，在此处追加本模组的属性行
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                Multimap<Attribute, AttributeModifier> attributes = stack.getAttributeModifiers(slot);
                for (Map.Entry<Attribute, AttributeModifier> entry : attributes.entries()) {
                    Attribute attribute = entry.getKey();
                    AttributeModifier modifier = entry.getValue();
                    // 被禁用的属性不显示
                    if (isAttributeDisabled(attribute)) continue;
                    if (AttributeFormatter.isCustomFormattedAttribute(attribute)) {
                        MutableComponent formatted = AttributeFormatter.formatAttribute(attribute, modifier, flag);
                        if (formatted != null) {
                            it.add(formatted);
                        }
                    }
                }
            }
        }
    }
}

package com.fu_meng.more_attributes.tooltip;

import com.google.common.collect.Multimap;
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
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber
public class AttributeTooltipHandler {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
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
}
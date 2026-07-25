package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.gui.MagicArmorOverlay;
import com.fu_meng.more_attributes.MoreAttributesMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 仅客户端加载的事件处理器，负责将自定义 HUD 覆盖层注册到 Forge 的渲染管线中。
 * 使用 {@link RegisterGuiOverlaysEvent} 而非 Mixin 修改 Gui 类，从而与其它修改 UI 的模组兼容。
 */
@Mod.EventBusSubscriber(modid = MoreAttributesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        // 注册在原版护甲条(ARMOR_LEVEL)之后渲染，使魔法护甲条紧贴原版护甲条上方显示，
        // 与原版护甲、血量等共同参与 leftHeight 堆栈，兼容其它修改 UI 的模组。
        event.registerAbove(VanillaGuiOverlay.ARMOR_LEVEL.id(), "magic_armor", MagicArmorOverlay.INSTANCE);
    }
}

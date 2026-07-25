package com.fu_meng.more_attributes.gui;

import com.fu_meng.more_attributes.AttributesRegistry;
import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.MoreAttributesMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 在 HUD 中以原版护甲条的形式显示玩家当前的魔法护甲值。
 * 该覆盖层通过 Forge 的 {@link IGuiOverlay} 机制注册，避免使用 Mixin 修改 Gui 类，
 * 从而与其它修改 UI 的模组保持良好的兼容性。
 */
public class MagicArmorOverlay implements IGuiOverlay {
    public static final MagicArmorOverlay INSTANCE = new MagicArmorOverlay();

    /** 魔法护甲图标纹理：左上角依次为 空、半、满 三个 9x9 图标，画布为 256x256。 */
    private static final ResourceLocation MAGIC_ARMOR_ICONS =
            ResourceLocation.tryParse(MoreAttributesMod.MODID + ":textures/gui/magic_armor.png");

    private static final int ICON_SIZE = 9;
    private static final int ICON_SPACING = 8;
    private static final int MAX_ICONS = 10;
    private static final int LINE_HEIGHT = 10;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // 配置关闭时不渲染，也不占用 leftHeight，避免在 HUD 中留出空隙
        if (!MAConfig.displayMagicArmor) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;
        if (!gui.shouldDrawSurvivalElements()) return;

        double magicArmor = minecraft.player.getAttributeValue(AttributesRegistry.MAGIC_ARMOR.get());
        int value = (int) Math.round(magicArmor);
        if (value <= 0) return;

        // 与原版护甲条使用相同的水平起点，并接入 leftHeight 堆栈以正确纵向排列
        int x = screenWidth / 2 - 91;
        int y = screenHeight - gui.leftHeight;

        RenderSystem.enableBlend();
        // 重置着色器颜色
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        for (int i = 0; i < MAX_ICONS; i++) {
            int iconX = x + i * ICON_SPACING;
            if (i * 2 + 1 < value) {
                // 满
                guiGraphics.blit(MAGIC_ARMOR_ICONS, iconX, y, 18, 0, ICON_SIZE, ICON_SIZE);
            } else if (i * 2 < value) {
                // 半
                guiGraphics.blit(MAGIC_ARMOR_ICONS, iconX, y, 9, 0, ICON_SIZE, ICON_SIZE);
            }
        }
        RenderSystem.disableBlend();

        gui.leftHeight += LINE_HEIGHT;
    }
}

package com.fu_meng.more_attributes.gui;

import com.fu_meng.more_attributes.AttributesRegistry;
import com.fu_meng.more_attributes.MAConfig;
import com.fu_meng.more_attributes.MoreAttributesMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class MagicArmorOverlay implements IGuiOverlay {
    public static final MagicArmorOverlay INSTANCE = new MagicArmorOverlay();

    private static final ResourceLocation MAGIC_ARMOR_ICONS =
            ResourceLocation.tryParse(MoreAttributesMod.MODID + ":textures/gui/magic_armor.png");

    private static final int ICON_SIZE = 9;
    private static final int ICON_SPACING = 8;
    private static final int MAX_ICONS = 10;
    private static final int ICON_HALF_U = ICON_SIZE;
    private static final int ICON_FULL_U = ICON_SIZE * 2;

    // 条形内部填充区宽度，与原版护甲条一致：10 图标 × 8px 间距 = 80px
    private static final int BAR_INNER_WIDTH = 80;
    // 内部填充区高度
    private static final int BAR_INNER_HEIGHT = 5;
    // 每侧边框总宽：外层 1px + 内层 1px = 2px
    private static final int BAR_BORDER_SIZE = 2;
    private static final int BAR_TOTAL_WIDTH = BAR_INNER_WIDTH + BAR_BORDER_SIZE * 2;
    private static final int BAR_TOTAL_HEIGHT = BAR_INNER_HEIGHT + BAR_BORDER_SIZE * 2;
    private static final int BAR_NUMBER_GAP = 4;
    private static final int LINE_HEIGHT = 10;

    // === 共享边框配色 ===
    // 上部外浅内深，下部外深内浅，对角对称
    private static final int BORDER_TOP = 0xFFA0A0A0;
    private static final int BORDER_BOTTOM = 0xFF373737;
    private static final int BORDER_INNER_TOP = 0xFF202020;
    private static final int BORDER_INNER_BOTTOM = 0xFF606060;

    // === 平滑渐变风格（style=1）填充配色 ===
    private static final int BAR_FILL_TOP = 0xFFC966FF;
    private static final int BAR_FILL_BOTTOM = 0xFF7A00CC;
    private static final int BAR_BG_TOP = 0xFF5A5A5A;
    private static final int BAR_BG_BOTTOM = 0xFF3A3A3A;

    // === 原版风格（style=0）填充配色：三段相近色阶 ===
    private static final int VANILLA_FILL_TOP = 0xFFB350FF;
    private static final int VANILLA_FILL_MID = 0xFFA033DD;
    private static final int VANILLA_FILL_BOTTOM = 0xFF8A1CCB;
    private static final int VANILLA_BG_TOP = 0xFF525252;
    private static final int VANILLA_BG_MID = 0xFF484848;
    private static final int VANILLA_BG_BOTTOM = 0xFF3E3E3E;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!MAConfig.displayMagicArmor) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;
        if (!gui.shouldDrawSurvivalElements()) return;

        double magicArmor = minecraft.player.getAttributeValue(AttributesRegistry.MAGIC_ARMOR.get());
        int value = (int) Math.round(magicArmor);
        if (value <= 0) return;

        int x = screenWidth / 2 - 91;
        int y = screenHeight - gui.leftHeight;

        if (MAConfig.magicArmorDisplayMode == 1) {
            renderBarMode(guiGraphics, x, y, value, magicArmor);
        } else {
            renderIconMode(guiGraphics, x, y, value);
        }

        gui.leftHeight += LINE_HEIGHT;
    }

    private void renderIconMode(GuiGraphics guiGraphics, int x, int y, int value) {
        // 如果 value 为 0，意味着所有格子都是空的，直接结束方法
        if (value <= 0) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < MAX_ICONS; i++) {
            // 如果当前格子是全空的，跳过渲染
            if (i * 2 >= value) {
                continue;
            }

            int iconX = x + i * ICON_SPACING;
            // 因为排除了空格子的情况，这里只需要判断是满的还是半满的即可
            int uOffset = (i * 2 + 1 < value) ? ICON_FULL_U : ICON_HALF_U;
            guiGraphics.blit(MAGIC_ARMOR_ICONS, iconX, y, uOffset, 0, ICON_SIZE, ICON_SIZE);
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }


    private void renderBarMode(GuiGraphics guiGraphics, int x, int y, int intValue, double rawValue) {
        Font font = Minecraft.getInstance().font;

        String valueText = (rawValue == Math.floor(rawValue)) ? String.valueOf(intValue) : String.format("%.1f", rawValue);
        int textWidth = font.width(valueText);
        int textY = y + (BAR_TOTAL_HEIGHT - font.lineHeight) / 2;

        drawArmorBar(guiGraphics, x, textY, intValue);

        if (MAConfig.magicArmorNumberPosition == 1) {
            int textX = x + (BAR_TOTAL_WIDTH - textWidth) / 2;
            guiGraphics.drawString(font, valueText, textX, textY, 0xFFFFFF, true);
        } else {
            int textX = x - textWidth - BAR_NUMBER_GAP;
            guiGraphics.drawString(font, valueText, textX, textY, 0xFFFFFF, true);
        }
    }

    /**
     * 绘制护甲条。两种风格共享相同的两层渐变边框，仅填充/背景的渐变方式不同。
     * 几何关系：外层边框 1px + 内层边框 1px = 每侧 2px，内部填充区宽 = BAR_INNER_WIDTH。
     */
    private void drawArmorBar(GuiGraphics guiGraphics, int x, int y, int value) {
        int totalW = BAR_TOTAL_WIDTH;
        int totalH = BAR_TOTAL_HEIGHT;

        // 1. 外层边框：顶/底纯色，左右垂直渐变
        guiGraphics.fill(x, y, x + totalW, y + 1, BORDER_TOP);
        guiGraphics.fill(x, y + totalH - 1, x + totalW, y + totalH, BORDER_BOTTOM);
        guiGraphics.fillGradient(x, y, x + 1, y + totalH, BORDER_TOP, BORDER_BOTTOM);
        guiGraphics.fillGradient(x + totalW - 1, y, x + totalW, y + totalH, BORDER_TOP, BORDER_BOTTOM);

        // 2. 内层边框（反向渐变）：顶深底浅
        int ix = x + 1, iy = y + 1;
        int ix2 = x + totalW - 1, iy2 = y + totalH - 1;
        guiGraphics.fill(ix, iy, ix2, iy + 1, BORDER_INNER_TOP);
        guiGraphics.fill(ix, iy2 - 1, ix2, iy2, BORDER_INNER_BOTTOM);
        guiGraphics.fillGradient(ix, iy, ix + 1, iy2, BORDER_INNER_TOP, BORDER_INNER_BOTTOM);
        guiGraphics.fillGradient(ix2 - 1, iy, ix2, iy2, BORDER_INNER_TOP, BORDER_INNER_BOTTOM);

        // 3. 内部填充区：外层1px + 内层1px = 2px 偏移
        int fillX = x + BAR_BORDER_SIZE;
        int fillY = y + BAR_BORDER_SIZE;
        int fillX2 = fillX + BAR_INNER_WIDTH;
        int fillY2 = fillY + BAR_INNER_HEIGHT;

        // 按配置的满格值计算填充比例
        int maxValue = MAConfig.magicArmorBarMaxValue;
        int fillWidth;
        if (value >= maxValue) {
            fillWidth = BAR_INNER_WIDTH;
        } else {
            fillWidth = (int) ((double) value / maxValue * BAR_INNER_WIDTH);
        }

        // 绘制背景
        if (MAConfig.magicArmorBarStyle == 0) {
            drawSegmentedVerticalGradient(guiGraphics, fillX, fillY, fillX2, fillY2,
                    VANILLA_BG_TOP, VANILLA_BG_MID, VANILLA_BG_BOTTOM);
        } else {
            guiGraphics.fillGradient(fillX, fillY, fillX2, fillY2, BAR_BG_TOP, BAR_BG_BOTTOM);
        }

        // 绘制填充
        if (fillWidth > 0) {
            if (MAConfig.magicArmorBarStyle == 0) {
                drawSegmentedVerticalGradient(guiGraphics, fillX, fillY, fillX + fillWidth, fillY2,
                        VANILLA_FILL_TOP, VANILLA_FILL_MID, VANILLA_FILL_BOTTOM);
            } else {
                guiGraphics.fillGradient(fillX, fillY, fillX + fillWidth, fillY2, BAR_FILL_TOP, BAR_FILL_BOTTOM);
            }
        }
    }

    /**
     * 绘制三段色阶垂直渐变（原版风格）：上 1px / 中 2px / 下 1px
     */
    private void drawSegmentedVerticalGradient(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2,
                                                int topColor, int midColor, int bottomColor) {
        int height = y2 - y1;
        if (height <= 0) return;
        int third = height / 3;
        int remainder = height % 3;
        int midH = third + remainder;
        int topEnd = y1 + third;
        int midEnd = topEnd + midH;

        guiGraphics.fill(x1, y1, x2, topEnd, topColor);
        guiGraphics.fill(x1, topEnd, x2, midEnd, midColor);
        guiGraphics.fill(x1, midEnd, x2, y2, bottomColor);
    }
}

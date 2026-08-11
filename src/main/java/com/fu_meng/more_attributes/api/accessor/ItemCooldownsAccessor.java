package com.fu_meng.more_attributes.api.accessor;

import net.minecraft.world.entity.player.Player;

/**
 * ItemCooldowns 的反向引用接口。
 * 由 ItemCooldownsMixin 实现，允许从 ItemCooldowns 反向访问其所属的 Player。
 */
public interface ItemCooldownsAccessor {
    void more_attributes$setPlayer(Player player);
}

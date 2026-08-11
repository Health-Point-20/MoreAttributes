package com.fu_meng.more_attributes.api.accessor;

import net.minecraft.world.entity.ai.attributes.AttributeMap;

/**
 * AttributeInstance 的反向引用接口。
 * 由 AttributeInstanceMixin 实现，允许从 AttributeInstance 反向访问其所属的 AttributeMap。
 */
public interface AttributeInstanceAccessor {
    void more_attributes$setMap(AttributeMap map);
}

package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.api.AttributeCalculationHelper.getProbability;
import static com.fu_meng.more_attributes.api.AttributeCalculationHelper.getDamageReductionProbability;
import static com.fu_meng.more_attributes.api.AttributeCalculationHelper.getIncomingDamageCapValue;
import static com.fu_meng.more_attributes.MAConfig.isAttributeDisabled;

import com.fu_meng.more_attributes.api.accessor.AttributeInstanceAccessor;


@Mixin(AttributeMap.class)
public abstract class AttributeMapMixin {

    @Final
    @Shadow
    private Map<Attribute, AttributeInstance> attributes;

    /** 需要覆盖为概率值的属性集合 */
    @Unique
    private static final Set<Attribute> PROBABILITY_ATTRIBUTES = Set.of(
            EVASION.get(),
            IGNORE_INVINCIBLE_TIME.get(),
            BACKSTAB_RESISTANCE.get()
    );

    /** 减伤类属性集合：正负分离模型（正值强度叠加，负值线性叠加），单独走对应概率转换 */
    @Unique
    private static final Set<Attribute> DAMAGE_REDUCTION_ATTRIBUTES = Set.of(
            PHYSICAL_DAMAGE_REDUCTION.get(),
            MAGIC_DAMAGE_REDUCTION.get(),
            ENVIRONMENTAL_DAMAGE_REDUCTION.get(),
            DAMAGE_REDUCTION.get()
    );

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void onGetValue(Attribute pAttribute, CallbackInfoReturnable<Double> cir) {
        // 被禁用的属性：直接返回默认值（等同属性不存在），不参与概率转换与伤害上限计算
        if (isAttributeDisabled(pAttribute)) {
            cir.setReturnValue(pAttribute.getDefaultValue());
            return;
        }

        AttributeInstance instance = attributes.get(pAttribute);
        if (instance == null) return;

        if (DAMAGE_REDUCTION_ATTRIBUTES.contains(pAttribute)) {
            cir.setReturnValue(getDamageReductionProbability(instance));
        } else if (PROBABILITY_ATTRIBUTES.contains(pAttribute)) {
            cir.setReturnValue(getProbability(instance));
        } else if (pAttribute == INCOMING_DAMAGE_CAP.get()) {
            Attribute maxHealthAttribute = Attributes.MAX_HEALTH;

            if (attributes.containsKey(maxHealthAttribute)) {
                double maxHealth = attributes.get(maxHealthAttribute).getValue();
                cir.setReturnValue((double) getIncomingDamageCapValue(instance, maxHealth));
            }
        }
    }

    // 神化兼容：在 getInstance 返回时设置反向引用，使 AttributeInstance 能访问所属 AttributeMap
    @Inject(method = "getInstance*", at = @At("RETURN"))
    private void onGetInstance(Attribute pAttribute, CallbackInfoReturnable<AttributeInstance> cir) {
        AttributeInstance instance = cir.getReturnValue();
        if (instance instanceof AttributeInstanceAccessor accessor) {
            accessor.more_attributes$setMap((AttributeMap) (Object) this);
        }
    }
}

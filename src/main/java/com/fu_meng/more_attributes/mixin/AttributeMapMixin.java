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
import static com.fu_meng.more_attributes.handler.AttributesHandler.getIncomingDamageCapValue;


@Mixin(AttributeMap.class)
public abstract class AttributeMapMixin {

    @Final
    @Shadow
    private Map<Attribute, AttributeInstance> attributes;

    /** 需要覆盖为概率值的属性集合 */
    @Unique
    private static final Set<Attribute> PROBABILITY_ATTRIBUTES = Set.of(
            PHYSICAL_DAMAGE_REDUCTION.get(),
            MAGIC_DAMAGE_REDUCTION.get(),
            ENVIRONMENTAL_DAMAGE_REDUCTION.get(),
            DAMAGE_REDUCTION.get(),
            EVASION.get(),
            IGNORE_INVINCIBLE_TIME.get()
    );

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void onGetValue(Attribute pAttribute, CallbackInfoReturnable<Double> cir) {
        AttributeInstance instance = attributes.get(pAttribute);
        if (instance == null) return;

        if (PROBABILITY_ATTRIBUTES.contains(pAttribute)) {
            cir.setReturnValue(getProbability(instance));
        } else if (pAttribute == INCOMING_DAMAGE_CAP.get()) {
            Attribute maxHealthAttribute = Attributes.MAX_HEALTH;

            if (attributes.containsKey(maxHealthAttribute)) {
                double maxHealth = attributes.get(maxHealthAttribute).getValue();
                cir.setReturnValue((double) getIncomingDamageCapValue(instance, maxHealth));
            }
        }
    }
}

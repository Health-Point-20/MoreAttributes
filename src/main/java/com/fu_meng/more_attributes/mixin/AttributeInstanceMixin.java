package com.fu_meng.more_attributes.mixin;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.fu_meng.more_attributes.AttributesRegistry.CRITICAL_CHANCE;
import static com.fu_meng.more_attributes.AttributesRegistry.CRITICAL_DAMAGE;
import static com.fu_meng.more_attributes.AttributesRegistry.getApotheosisCritChance;
import static com.fu_meng.more_attributes.AttributesRegistry.getApotheosisCritDamage;
import static com.fu_meng.more_attributes.MAConfig.ApotheosisCritCompatibility;

import com.fu_meng.more_attributes.api.AttributeInstanceAccessor;

@Mixin(AttributeInstance.class)
public class AttributeInstanceMixin implements AttributeInstanceAccessor {

    @Final
    @Shadow
    private Attribute attribute;

    @Unique
    private AttributeMap more_attributes$map;

    @Override
    public void more_attributes$setMap(AttributeMap map) {
        more_attributes$map = map;
    }

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void onGetValue(CallbackInfoReturnable<Double> cir) {
        if (!ApotheosisCritCompatibility) return;
        if (more_attributes$map == null) return;

        Attribute apoChance = getApotheosisCritChance();
        Attribute apoDamage = getApotheosisCritDamage();

        if (apoChance != null && attribute == apoChance) {
            AttributeInstance ourInst = more_attributes$map.getInstance(CRITICAL_CHANCE.get());
            if (ourInst != null) {
                cir.setReturnValue(cir.getReturnValue() + ourInst.getValue());
            }
        } else if (apoDamage != null && attribute == apoDamage) {
            AttributeInstance ourInst = more_attributes$map.getInstance(CRITICAL_DAMAGE.get());
            if (ourInst != null) {
                cir.setReturnValue(cir.getReturnValue() + ourInst.getValue());
            }
        }
    }
}

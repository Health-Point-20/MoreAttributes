package com.fu_meng.more_attributes.handler;

import com.fu_meng.more_attributes.mixin.LivingEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.fu_meng.more_attributes.AttributesRegistry.DROP_MULTIPLIER;

/**
 * 掉落物倍率属性（按「独立重复摇奖」语义实现，仅针对 >1 的倍率）。
 */
@Mod.EventBusSubscriber
public class LootDropHandler {
    /**
     * 实体死亡时，在原版战利品表正常抽取 1 次的基础上，额外独立重抽若干次：
     * - 整数倍率 m = floor(属性值)，额外必定重抽 (m - 1) 次（如 2.0 → 共 2 次抽取，掉落翻倍）；
     * - 小数部分 p = 属性值 - m，以概率 p 额外再重抽 1 次（如 1.5 → 50% 概率触发一次额外掉落）。
     * 每次重抽都构造独立的战利品上下文并采用新的随机种子，使多次抽取结果真正相互独立。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;

        double dropMultiplier = entity.getAttributeValue(DROP_MULTIPLIER.get());
        if (dropMultiplier <= 1.0) return;

        // 与原版 dropAllDeathLoot 的判定一致：婴儿实体或关闭生物掉落时不做额外抽取
        LivingEntityAccessor accessor = (LivingEntityAccessor) entity;
        if (!accessor.shouldDropLoot() || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) return;

        int guaranteed = (int) Math.floor(dropMultiplier);
        double fraction = dropMultiplier - guaranteed;
        int extraRolls = guaranteed - 1;
        if (fraction > 0.0 && entity.getRandom().nextDouble() < fraction) {
            extraRolls++;
        }
        if (extraRolls <= 0) return;

        DamageSource source = event.getSource();
        boolean hitByPlayer = accessor.getLastHurtByPlayerTime() > 0;
        Player lastHurtByPlayer = accessor.getLastHurtByPlayer();
        for (int i = 0; i < extraRolls; i++) {
            rollExtraDeathLoot(entity, level, source, hitByPlayer, lastHurtByPlayer);
        }
    }

    /**
     * 复刻 LivingEntity.dropFromLootTable 的抽取逻辑，但每次传入新的随机种子以保证各次抽取独立。
     * 生成物通过 spawnAtLocation 直接生成到世界中（此时 captureDrops 尚未激活，
     * 不会与后续 dropAllDeathLoot 的基础掉落流程合并或冲突）。
     */
    private static void rollExtraDeathLoot(LivingEntity entity, ServerLevel level, DamageSource source,
                                           boolean hitByPlayer, Player lastHurtByPlayer) {
        LootTable lootTable = level.getServer().getLootData().getLootTable(entity.getLootTable());
        LootParams.Builder builder = (new LootParams.Builder(level))
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                .withOptionalParameter(LootContextParams.KILLER_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, source.getDirectEntity());
        if (hitByPlayer && lastHurtByPlayer != null) {
            builder = builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, lastHurtByPlayer)
                    .withLuck(lastHurtByPlayer.getLuck());
        }
        LootParams params = builder.create(LootContextParamSets.ENTITY);
        // 使用独立随机种子，避免多次抽取基于同一 seed 产生相同结果
        lootTable.getRandomItems(params, level.random.nextLong(), entity::spawnAtLocation);
    }
}

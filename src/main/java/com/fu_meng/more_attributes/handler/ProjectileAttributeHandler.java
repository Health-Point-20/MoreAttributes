package com.fu_meng.more_attributes.handler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.fu_meng.more_attributes.AttributesRegistry.*;
import static com.fu_meng.more_attributes.MAConfig.*;
import static com.fu_meng.more_attributes.MoreAttributesMod.queueServerWork;

/**
 * 弹射物相关属性：箭矢伤害/速度/穿透/精准度、多重射击、箭矢弹射，以及弹射物的闪避判定。
 */
@Mod.EventBusSubscriber
public class ProjectileAttributeHandler {
    // 多重射击复制品标记键
    private static final String MULTISHOT_CLONE_KEY = "more_attributes$multishot_clone";
    // 多重射击已克隆标记：防止弹射物重复加入世界时（区块卸载重载）重复克隆
    private static final String MULTISHOT_SPAWNED_KEY = "more_attributes$multishot_spawned";

    // 箭矢弹射 PersistentData 键
    private static final String ARROW_BOUNCE_REMAINING_KEY = "more_attributes$bounce_remaining";
    private static final String ARROW_BOUNCE_HIT_LIST_KEY = "more_attributes$bounce_hit_list";
    private static final String ARROW_BOUNCE_CLONE_KEY = "more_attributes$bounce_clone";
    // 箭矢属性已应用标记：防止箭矢重复加入世界时（区块卸载重载）重复应用箭矢属性
    private static final String ARROW_ATTRS_APPLIED_KEY = "more_attributes$arrow_attrs_applied";

    private static boolean isMultishotClone(Entity entity) {
        return entity.getPersistentData().getBoolean(MULTISHOT_CLONE_KEY);
    }

    private static boolean isBounceClone(Entity entity) {
        return entity.getPersistentData().getBoolean(ARROW_BOUNCE_CLONE_KEY);
    }

    /** 是否为已处理过的克隆弹射物（多重射击或弹射），需跳过属性重复应用与再次克隆 */
    private static boolean isProcessedClone(Entity entity) {
        return isMultishotClone(entity) || isBounceClone(entity);
    }

    private static void addBounceHitEntity(CompoundTag tag, UUID uuid) {
        ListTag list = tag.getList(ARROW_BOUNCE_HIT_LIST_KEY, Tag.TAG_STRING);
        list.add(StringTag.valueOf(uuid.toString()));
        tag.put(ARROW_BOUNCE_HIT_LIST_KEY, list);
    }

    private static boolean isBounceHitEntity(CompoundTag tag, UUID uuid) {
        ListTag list = tag.getList(ARROW_BOUNCE_HIT_LIST_KEY, Tag.TAG_STRING);
        String uuidStr = uuid.toString();
        for (int i = 0; i < list.size(); i++) {
            if (uuidStr.equals(list.getString(i))) return true;
        }
        return false;
    }

    /** 通过 NBT 序列化/反序列化克隆实体（兼容模组弹射物）；失败返回 null，create() 会分配新 UUID */
    @Nullable
    private static Entity cloneByNbt(Entity original) {
        CompoundTag nbt = original.saveWithoutId(new CompoundTag());
        nbt.remove("UUID"); // 移除 UUID 以避免冲突
        Entity clone = original.getType().create(original.level());
        if (clone == null) return null;
        clone.load(nbt);
        return clone;
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();

        // 处理多重射击复制品的击中销毁
        if (isMultishotClone(projectile)) {
            handleCloneImpact(projectile, event);
            return;
        }

        // 弹射链已命中过的实体不再被这支箭命中：
        // 覆盖克隆箭从上一受害者体表射出时，下一 tick raycast 立即命中该受害者的边界情况（防止重复伤害与多消耗弹射次数）
        if (projectile instanceof AbstractArrow bounceArrow) {
            HitResult bounceHitResult = event.getRayTraceResult();
            if (bounceHitResult instanceof EntityHitResult bounceEhr
                    && bounceEhr.getEntity() instanceof LivingEntity bounceHitEntity
                    && isBounceHitEntity(bounceArrow.getPersistentData(), bounceHitEntity.getUUID())) {
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
                return;
            }
        }

        // 被击中实体需从命中结果获取（event.getEntity() 返回的是弹射物自身，不能用作受害者）
        LivingEntity victim = null;
        if (event.getRayTraceResult() instanceof EntityHitResult hitEhr && hitEhr.getEntity() instanceof LivingEntity living) {
            victim = living;
        }
        LivingEntity attacker = projectile.getOwner() instanceof LivingEntity living2 ? living2 : null;

        // 闪避判定与命中记录仅服务端执行：ProjectileImpactEvent 双端触发，
        // 客户端写入 PROJECTILE_RECORD 后无清理路径（LivingAttackEvent 不在客户端触发），会导致内存泄漏
        boolean dodged = false;
        if (victim != null && attacker != null && !projectile.level().isClientSide()) {
            dodged = CombatHelper.rollDodge(attacker, victim);
            if (dodged) {
                event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
            } else {
                CombatHelper.PROJECTILE_RECORD.put(projectile.getUUID(), victim.getUUID());
            }
        }

        // 处理箭矢弹射：在闪避判定之后，被闪避（未实际命中）的箭不触发弹射
        if (!dodged && projectile instanceof AbstractArrow arrow) {
            handleArrowBounce(arrow, event);
        }

        // 弹射克隆箭命中后销毁（链式弹射已在上方完成），防止残留仅创造拾取的箭矢
        // 被闪避的克隆箭不销毁：箭被躲开后继续飞行，后续撞墙/命中时自然销毁
        if (!dodged && isBounceClone(projectile)) {
            handleCloneImpact(projectile, event);
        }
    }

    /**
     * 克隆弹射物（多重射击复制品/弹射克隆箭）的击中销毁：
     * 击中方块立即销毁（防止插地、三叉戟滞留等）；击中实体允许造成伤害，下一 tick 销毁
     * （防止穿透继续飞行、拾取、忠诚回归等副作用）。
     */
    private static void handleCloneImpact(Projectile projectile, ProjectileImpactEvent event) {
        HitResult hitResult = event.getRayTraceResult();
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
        } else if (hitResult.getType() == HitResult.Type.ENTITY) {
            queueServerWork(() -> {
                if (!projectile.isRemoved()) {
                    projectile.discard();
                }
            });
        }
    }

    /**
     * 箭矢属性统一应用入口（穿透/伤害/速度/精准度/弹射次数）。
     * 合并原先两个同优先级的 EntityJoinLevelEvent 处理器，保证属性应用顺序确定；
     * 多重射击克隆在 LOW 优先级的 onMultishotProjectileJoin 中处理，确保克隆时继承的是已修改的弹射物状态。
     */
    @SubscribeEvent
    public static void onArrowJoinLevel(EntityJoinLevelEvent event) {
        // 仅服务端处理：客户端箭矢不携带 PersistentData（克隆标记无法同步，isProcessedClone 恒为 false），
        // 且箭矢属性均为服务端结算，客户端重复应用会造成弹射箭视觉速度与实际不符
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (isProcessedClone(arrow)) return; // 跳过克隆弹射物（已从原弹射物继承属性）
        if (!(arrow.getOwner() instanceof LivingEntity shooter)) return;
        CompoundTag arrowTag = arrow.getPersistentData();
        if (arrowTag.getBoolean(ARROW_ATTRS_APPLIED_KEY)) return; // 已应用过（区块卸载重载后重新加入世界）
        arrowTag.putBoolean(ARROW_ATTRS_APPLIED_KEY, true);

        // 穿透
        double pierceCount = shooter.getAttributeValue(ARROW_PIERCE.get());
        arrow.setPierceLevel((byte) (arrow.getPierceLevel() + pierceCount));

        // 伤害与速度
        double arrowDamage = shooter.getAttributeValue(ARROW_DAMAGE.get());
        double arrowVelocity = shooter.getAttributeValue(ARROW_VELOCITY.get());
        arrow.setBaseDamage(arrow.getBaseDamage() * arrowDamage);
        arrow.setDeltaMovement(arrow.getDeltaMovement().scale(arrowVelocity));

        // 箭矢精准度：负值时对原箭矢施加随机角度偏移
        double arrowAccuracy = shooter.getAttributeValue(ARROW_ACCURACY.get());
        if (arrowAccuracy < 0) {
            applyAccuracyOffset(arrow, arrowAccuracy);
        }

        // 箭矢弹射：写入剩余弹射次数（衰减系数为全局配置，弹射时直接读取，不冗余存储）
        double arrowBounce = shooter.getAttributeValue(ARROW_BOUNCE.get());
        if (arrowBounce > 0) {
            arrowTag.putInt(ARROW_BOUNCE_REMAINING_KEY, (int) arrowBounce);
        }
    }

    /**
     * 多重射击：弹射物加入世界时，根据发射者的 MULTISHOT_AMOUNT 属性克隆额外弹射物。
     * 使用 LOW 优先级确保在 ARROW_DAMAGE/VELOCITY/PIERCE 等属性处理之后执行，
     * 使复制品能从已修改的原弹射物状态继承正确数值。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMultishotProjectileJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        if (isProcessedClone(projectile)) return;

        Entity owner = projectile.getOwner();
        if (!(owner instanceof LivingEntity shooter)) return;

        double multishotAmount = shooter.getAttributeValue(MULTISHOT_AMOUNT.get());
        int extraCount = (int) multishotAmount;
        if (extraCount <= 0) return;

        // 已克隆过的不再重复克隆
        CompoundTag projectileTag = projectile.getPersistentData();
        if (projectileTag.getBoolean(MULTISHOT_SPAWNED_KEY)) return;
        projectileTag.putBoolean(MULTISHOT_SPAWNED_KEY, true);

        spawnMultishotClones(projectile, shooter, extraCount);
    }

    /**
     * 以扇形分布克隆额外弹射物。
     * 采用交替左右分布：i=0→左侧第1档，i=1→右侧第1档，i=2→左侧第2档，依此类推。
     * 每档间隔 multishotSpreadPerPoint 度（默认10°，与原版弩多重射击一致）。
     * 方向旋转使用四元数绕发射者上方向轴，与原版 CrossbowItem 实现一致。
     */
    private static void spawnMultishotClones(Projectile original, LivingEntity shooter, int extraCount) {
        Vec3 originalMotion = original.getDeltaMovement();
        double speed = originalMotion.length();
        if (speed < 1.0E-6) return;

        Vec3 dir = originalMotion.normalize();
        Vec3 up = shooter.getUpVector(1.0F);

        double arrowAccuracy = shooter.getAttributeValue(ARROW_ACCURACY.get());
        // 正值精准度减少多重射击扩散度：每点精准度降低 1 度散布角，最低为 0
        float effectiveSpread = (float) Math.max(0, multishotSpreadPerPoint - Math.max(0, arrowAccuracy));

        for (int i = 0; i < extraCount; i++) {
            int side = (i % 2 == 0) ? -1 : 1;
            int step = i / 2 + 1;
            float angleDeg = side * step * effectiveSpread;
            float angleRad = angleDeg * ((float) Math.PI / 180.0F);

            // 通过 NBT 序列化/反序列化创建同类型弹射物副本，兼容模组弹射物
            if (!(cloneByNbt(original) instanceof Projectile cloneProjectile)) continue;

            // 标记为复制品
            CompoundTag cloneTag = cloneProjectile.getPersistentData();
            cloneTag.putBoolean(MULTISHOT_CLONE_KEY, true);
            // 按配置决定是否继承弹射状态：弹射剩余次数/已命中列表会随 ForgeData 克隆，
            // 继承时总弹射次数随克隆数量成倍放大（multishotCloneInheritsBounce=false 时不继承、克隆箭不弹射）
            if (!multishotCloneInheritsBounce) {
                cloneTag.remove(ARROW_BOUNCE_REMAINING_KEY);
                cloneTag.remove(ARROW_BOUNCE_HIT_LIST_KEY);
            }

            // 确保所有者正确
            cloneProjectile.setOwner(shooter);

            // 用四元数绕上方向轴旋转原始方向向量，实现扇形分布
            Quaternionf quaternion = new Quaternionf().setAngleAxis(angleRad, (float) up.x, (float) up.y, (float) up.z);
            Vector3f rotated = dir.toVector3f().rotate(quaternion);

            // 设置克隆体的方向和速度（inaccuracy=0 保证精确角度，无随机偏移）
            cloneProjectile.shoot(rotated.x(), rotated.y(), rotated.z(), (float) speed, 0.0F);

            // 负值精准度：对每个复制品施加独立的随机角度偏移
            if (arrowAccuracy < 0) {
                applyAccuracyOffset(cloneProjectile, arrowAccuracy);
            }

            // 箭矢类复制品标记为仅创造模式可拾取，防止刷箭
            if (cloneProjectile instanceof AbstractArrow cloneArrow) {
                cloneArrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            original.level().addFreshEntity(cloneProjectile);
        }
    }

    /**
     * 对弹射物施加随机角度偏移（仅当 arrow_accuracy < 0 时调用）。
     * 水平方向（偏航）偏移范围：±|accuracy|/2 度
     * 竖直方向（俯仰）偏移范围：±|accuracy|/20 度
     */
    private static void applyAccuracyOffset(Projectile projectile, double accuracy) {
        double magnitude = -accuracy;
        float yawRange = (float) (magnitude * 0.5);    // 水平 ±度
        float pitchRange = (float) (magnitude * 0.05);  // 竖直 ±度

        float yawDeg = (projectile.level().random.nextFloat() * 2.0F - 1.0F) * yawRange;
        float pitchDeg = (projectile.level().random.nextFloat() * 2.0F - 1.0F) * pitchRange;

        Vec3 motion = projectile.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-6) return;

        Vec3 dir = motion.normalize();

        // 建立局部正交基：dir(前)、right(右)、up(上)
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(worldUp);
        if (right.lengthSqr() < 1.0E-10) {
            right = new Vec3(0, 0, 1); // 方向与世界 Y 轴平行时改用 Z 轴
        }
        right = right.normalize();
        Vec3 up = right.cross(dir).normalize();

        float yawRad = yawDeg * ((float) Math.PI / 180.0F);
        float pitchRad = pitchDeg * ((float) Math.PI / 180.0F);

        // 先绕 up 轴旋转（水平偏航），再绕 right 轴旋转（竖直俯仰）
        Vector3f rotated = dir.toVector3f();
        rotated.rotate(new Quaternionf().setAngleAxis(yawRad, (float) up.x, (float) up.y, (float) up.z));
        rotated.rotate(new Quaternionf().setAngleAxis(pitchRad, (float) right.x, (float) right.y, (float) right.z));

        projectile.setDeltaMovement(new Vec3(rotated.x(), rotated.y(), rotated.z()).scale(speed));
    }

    /**
     * 箭矢弹射：箭矢命中生物时，若仍有弹射次数，克隆一支新箭射向最近的未命中敌人。
     * 每次弹射后原箭矢剩余次数 -1（兼容穿透：穿透箭每命中一个目标可各触发一次弹射）。
     */
    private static void handleArrowBounce(AbstractArrow arrow, ProjectileImpactEvent event) {
        // 仅服务端处理：客户端预测 tick 也会触发本事件，客户端克隆会产生幽灵箭导致不同步
        if (arrow.level().isClientSide()) return;

        CompoundTag tag = arrow.getPersistentData();
        int remaining = tag.getInt(ARROW_BOUNCE_REMAINING_KEY);
        if (remaining <= 0) return;

        // 仅在命中生物时触发弹射
        HitResult hitResult = event.getRayTraceResult();
        if (hitResult.getType() != HitResult.Type.ENTITY) return;
        if (!(hitResult instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof LivingEntity currentVictim)) return;

        LivingEntity shooter = arrow.getOwner() instanceof LivingEntity living ? living : null;
        if (shooter == null) return;

        // 将当前受害者加入已命中列表（避免克隆箭回头命中）
        addBounceHitEntity(tag, currentVictim.getUUID());

        // 搜索下一目标
        LivingEntity nextTarget = findBounceTarget(arrow, shooter, currentVictim);
        if (nextTarget == null) return;

        // 克隆一支新箭射向下一目标，并递减原箭矢剩余次数
        spawnBounceClone(arrow, shooter, nextTarget, remaining);
        tag.putInt(ARROW_BOUNCE_REMAINING_KEY, remaining - 1);
    }

    /**
     * 在弹射搜索范围内寻找最近的有效目标：非发射者、非当前命中链中的实体、非友方，
     * 且为「与当前受害者同种的实体」或「敌对实体」。
     * 射击非敌对生物（如村民、动物等测试实体）时，弹射会在同种实体间传导。
     */
    private static LivingEntity findBounceTarget(AbstractArrow arrow, LivingEntity shooter, LivingEntity currentVictim) {
        Vec3 origin = arrow.position();
        AABB box = new AABB(origin, origin).inflate(arrowBounceSearchRange);
        CompoundTag tag = arrow.getPersistentData();
        List<LivingEntity> candidates = arrow.level().getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive()
                        && entity != shooter
                        && entity != currentVictim
                        && !CombatHelper.isPartner(shooter, entity)
                        && !isBounceHitEntity(tag, entity.getUUID())
                        && (entity instanceof Enemy || entity.getType() == currentVictim.getType()));
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
        return candidates.get(0);
    }

    /**
     * 通过 NBT 序列化克隆一支新箭，从命中点射向下一目标。
     * 复用多重射击的克隆范式：标记为弹射克隆以跳过属性重复应用与再次克隆。
     */
    private static void spawnBounceClone(AbstractArrow original, LivingEntity shooter,
                                         LivingEntity nextTarget, int remaining) {
        if (!(cloneByNbt(original) instanceof AbstractArrow clone)) return;

        CompoundTag cloneTag = clone.getPersistentData();
        // 标记为弹射克隆（跳过多重射击/箭矢属性重复处理）
        cloneTag.putBoolean(ARROW_BOUNCE_CLONE_KEY, true);
        // 剩余次数 -1；已命中列表随 ForgeData 克隆自动继承，无需显式复制
        cloneTag.putInt(ARROW_BOUNCE_REMAINING_KEY, remaining - 1);

        // 伤害衰减（衰减系数为全局配置，直接读取）
        clone.setBaseDamage(clone.getBaseDamage() * arrowBounceDamageFalloff);

        // 清除穿透等级：弹射链跳数由剩余次数控制，防止克隆箭穿透造成不可预测的连锁分支与残留
        clone.setPierceLevel((byte) 0);

        // 确保所有者正确
        clone.setOwner(shooter);

        // 从命中点射向下一目标躯干中心
        Vec3 start = original.position();
        Vec3 targetCenter = nextTarget.position().add(0, nextTarget.getBbHeight() * 0.5, 0);
        Vec3 dir = targetCenter.subtract(start).normalize();
        clone.setPos(start.x, start.y, start.z);
        // 最低发射速度 1.0 保证弹射箭能飞抵目标（过慢的箭会被重力拖偏坠地）；
        // 原版箭矢伤害 = 命中速度 × 基础伤害，速度被下限提升时按比例下调基础伤害，
        // 保证弹射伤害仍严格按 falloff 衰减，避免远程慢箭出现越弹射伤害越高
        double impactSpeed = original.getDeltaMovement().length();
        double speed = Math.max(impactSpeed, 1.0);
        if (speed > impactSpeed && impactSpeed > 0.0) {
            clone.setBaseDamage(clone.getBaseDamage() * (impactSpeed / speed));
        }
        clone.shoot(dir.x, dir.y, dir.z, (float) speed, 0.0F);

        // 防刷箭
        clone.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        original.level().addFreshEntity(clone);
    }
}

package com.cheatbreaker.client.render.afterimage;

import java.lang.ref.SoftReference;
import net.minecraft.entity.EntityLivingBase;

/**
 * 一次受击瞬间的姿态快照。
 *
 * <p>残影不做实时跟随：实体被打的那一帧把位置、身体朝向、头部角度、四肢摆动、挥臂进度、
 * 潜行 / 骑乘 / 幼年状态全部抄下来，之后每一帧都用这份固定数据重新画模型，
 * 所以看起来是"停在原地慢慢淡掉的白色影子"。
 *
 * <p>快照同时保留一个实体的软引用：实体死亡后会被移出世界，
 * 光靠 entityId 查不到人，残影就会在淡出到一半时突然消失（看起来像是缺了一块）。
 * 渲染时优先用世界里的实体，找不到再退回这个引用，残影就能完整播完。
 */
public class HitAfterimage {
    public final int entityId;
    public final double posX;
    public final double posY;
    public final double posZ;
    public final float renderYawOffset;
    public final float rotationPitch;
    public final float rotationYawHead;
    public final float limbSwing;
    public final float limbSwingAmount;
    public final float ticksExisted;
    public final float swingProgress;
    public final boolean sneaking;
    public final boolean riding;
    public final boolean child;
    private final long createTime;
    private final SoftReference<EntityLivingBase> entityReference;

    public HitAfterimage(EntityLivingBase entity) {
        this.entityId = entity.getEntityId();
        this.posX = entity.posX;
        this.posY = entity.posY;
        this.posZ = entity.posZ;
        this.renderYawOffset = entity.renderYawOffset;
        this.rotationPitch = entity.rotationPitch;
        this.rotationYawHead = entity.rotationYawHead;
        this.limbSwing = entity.limbSwing;
        this.limbSwingAmount = entity.limbSwingAmount;
        this.ticksExisted = entity.ticksExisted;
        this.swingProgress = entity.getSwingProgress(1.0F);
        this.sneaking = entity.isSneaking();
        this.riding = entity.isRiding();
        this.child = entity.isChild();
        this.createTime = System.currentTimeMillis();
        this.entityReference = new SoftReference<EntityLivingBase>(entity);
    }

    /** 实体被移出世界后仍然可以拿它来取模型 / 渲染器。 */
    public EntityLivingBase getEntityReference() {
        return this.entityReference.get();
    }

    /** 1.0 → 0.0 的线性淡出。 */
    public float getAlpha(long lifetimeMillis) {
        if (lifetimeMillis <= 0L) {
            return 0.0F;
        }

        float alpha = 1.0F - (float)this.getAge() / (float)lifetimeMillis;

        if (alpha < 0.0F) {
            return 0.0F;
        }

        return alpha > 1.0F ? 1.0F : alpha;
    }

    public boolean isExpired(long lifetimeMillis) {
        return this.getAge() > lifetimeMillis;
    }

    public long getAge() {
        return System.currentTimeMillis() - this.createTime;
    }

    /** 0.0（刚生成）→ 1.0（寿命结束）的进度，几个样式用它做时间曲线。 */
    public float getProgress(long lifetimeMillis) {
        if (lifetimeMillis <= 0L) {
            return 1.0F;
        }

        float progress = (float)this.getAge() / (float)lifetimeMillis;
        return progress < 0.0F ? 0.0F : (progress > 1.0F ? 1.0F : progress);
    }
}

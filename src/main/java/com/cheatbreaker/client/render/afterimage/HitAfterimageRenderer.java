package com.cheatbreaker.client.render.afterimage;

import com.cheatbreaker.client.util.Logging;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * 受击残影的绘制器。
 *
 * <p>思路是「反相外壳 + 多层柔晕」：
 * <ol>
 *   <li>先关掉颜色写入、只写深度，把残影的轮廓压进深度缓冲，避免后面的外壳被自身遮挡；</li>
 *   <li>再用同一个模型按原尺寸画一层低 alpha 的白色核心；</li>
 *   <li>最后按 10 个递增的缩放系数、反相剔除，逐层画逐渐变淡的白色外壳，
 *       叠出接近「烟雾轮廓」的柔光边缘。</li>
 * </ol>
 *
 * <p>整段绘制不用贴图、不用 FBO、不用 shader，也不读像素，所以不会和 OptiFine 的
 * 材质或 shader 管线打架；进入前后都完整保存 / 还原 GL 状态。
 */
public final class HitAfterimageRenderer {
    /** Minecraft 模型单位 → 方块。 */
    private static final float MODEL_SCALE = 0.0625F;
    /** 与 RendererLivingEntity 里 0, -24 * 0.0625 - 0.0078125 一致，让模型贴着脚底。 */
    private static final float MODEL_Y_ALIGN = -1.5078125F;
    /**
     * 各样式的外壳缩放曲线（由内到外）。
     * <ul>
     *   <li>0 Smoke：贴身的柔晕，越外越淡，像烟。</li>
     *   <li>1 Ring：拉远成均匀的环带。</li>
     *   <li>2 Pulse：起点更外，配合时间收缩做呼吸。</li>
     *   <li>3 Flat：不画外壳，只留实心剪影。</li>
     * </ul>
     */
    private static final float[][] STYLE_SCALES = new float[][]{
        {1.03F, 1.08F, 1.14F, 1.21F, 1.29F, 1.39F, 1.51F, 1.64F, 1.78F, 1.95F},
        {1.25F, 1.40F, 1.55F, 1.70F, 1.85F, 2.00F, 2.15F, 2.30F, 2.45F, 2.60F},
        {1.10F, 1.22F, 1.35F, 1.50F, 1.66F, 1.84F, 2.04F, 2.26F, 2.50F, 2.76F},
        {}
    };

    /** 与 STYLE_SCALES 对应的外壳透明度。 */
    private static final float[][] STYLE_ALPHAS = new float[][]{
        {0.30F, 0.255F, 0.215F, 0.18F, 0.15F, 0.125F, 0.103F, 0.084F, 0.066F, 0.05F},
        {0.34F, 0.33F, 0.32F, 0.30F, 0.28F, 0.26F, 0.24F, 0.21F, 0.18F, 0.15F},
        {0.22F, 0.21F, 0.20F, 0.19F, 0.18F, 0.17F, 0.16F, 0.15F, 0.14F, 0.13F},
        {}
    };

    public static final int STYLE_SMOKE = 0;
    public static final int STYLE_RING = 1;
    public static final int STYLE_PULSE = 2;
    public static final int STYLE_FLAT = 3;

    /** 渲染参数打包：颜色、透明度、强度、样式、上浮、闪烁。 */
    public static final class Options {
        public float opacity = 1.0F;
        public float intensity = 1.0F;
        public float red = 1.0F;
        public float green = 1.0F;
        public float blue = 1.0F;
        public int style = STYLE_SMOKE;
        /** 0..1，残影在寿命内向上飘的格数除以 1.5。 */
        public float rise = 0.0F;
        /** 0..1，亮度抖动幅度。 */
        public float flicker = 0.0F;
    }

    /** 实心核心的透明度系数。 */
    private static final float CORE_ALPHA = 0.22F;

    private static final ModelBiped FALLBACK_MODEL = new ModelBiped(0.0F);
    private static final int[] TRACKED_STATES = new int[]{
        GL11.GL_TEXTURE_2D,
        GL11.GL_LIGHTING,
        GL11.GL_BLEND,
        GL11.GL_DEPTH_TEST,
        GL11.GL_CULL_FACE,
        GL11.GL_FOG,
        GL11.GL_ALPHA_TEST,
        GL11.GL_COLOR_MATERIAL,
        GL11.GL_NORMALIZE
    };

    private HitAfterimageRenderer() {
    }

    /**
     * 画一批残影，partialTicks 用于把相机位置插值到当前帧。
     *
     * @param options 颜色 / 透明度 / 强度 / 样式 / 上浮 / 闪烁
     */
    public static void render(List<HitAfterimage> afterimages, float partialTicks, long lifetimeMillis, Options options) {
        if (afterimages == null || afterimages.isEmpty() || options == null || options.opacity <= 0.0F) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        double[] camera = cameraPosition(partialTicks);
        boolean[] previousStates = new boolean[TRACKED_STATES.length];
        for (int i = 0; i < TRACKED_STATES.length; i++) {
            previousStates[i] = GL11.glIsEnabled(TRACKED_STATES[i]);
        }

        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean colorMask = GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK);
        int blendSource = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int blendDestination = GL11.glGetInteger(GL11.GL_BLEND_DST);
        int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            for (HitAfterimage afterimage : afterimages) {
                float alpha = afterimage.getAlpha(lifetimeMillis) * options.opacity;
                if (alpha <= 0.01F) {
                    continue;
                }

                EntityLivingBase living = resolveEntity(mc, afterimage);
                if (living == null) {
                    continue;
                }

                Render render = RenderManager.instance.getEntityRenderObject(living);
                if (!(render instanceof RendererLivingEntity)) {
                    continue;
                }

                RendererLivingEntity renderer = (RendererLivingEntity)render;
                ModelBase model = renderer.mainModel == null ? FALLBACK_MODEL : renderer.mainModel;

                try {
                    renderOne(afterimage, partialTicks, camera, living, renderer, model, alpha, options, lifetimeMillis);
                } catch (Throwable throwable) {
                    Logging.errorTagged("Hit Afterimage", "Could not render afterimage of %s: %s", living, throwable);
                }
            }
        } finally {
            restore(colorMask, depthMask, blendSource, blendDestination, cullFace, depthFunc, activeTexture, boundTexture, previousStates);
        }
    }

    /**
     * 取用于渲染的实体：优先世界里的那一个，其次用快照持有的软引用。
     *
     * <p>后者是给「实体死亡后被移出世界」准备的 —— 残影还要再淡出 1 秒多，
     * 这时候查不到实体就会整块消失，看起来就是消失动画缺了一段。
     */
    private static EntityLivingBase resolveEntity(Minecraft mc, HitAfterimage afterimage) {
        Entity entity = mc.theWorld.getEntityByID(afterimage.entityId);

        if (entity instanceof EntityLivingBase) {
            return (EntityLivingBase)entity;
        }

        return afterimage.getEntityReference();
    }

    private static void restore(
        boolean colorMask,
        boolean depthMask,
        int blendSource,
        int blendDestination,
        int cullFace,
        int depthFunc,
        int activeTexture,
        int boundTexture,
        boolean[] previousStates
    ) {
        GL11.glDepthMask(depthMask);
        GL11.glColorMask(colorMask, colorMask, colorMask, colorMask);
        GL11.glBlendFunc(blendSource, blendDestination);
        GL11.glCullFace(cullFace);
        GL11.glDepthFunc(depthFunc);
        GL13.glActiveTexture(activeTexture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < TRACKED_STATES.length; i++) {
            if (previousStates[i]) {
                GL11.glEnable(TRACKED_STATES[i]);
            } else {
                GL11.glDisable(TRACKED_STATES[i]);
            }
        }
    }

    private static void renderOne(
        HitAfterimage afterimage,
        float partialTicks,
        double[] camera,
        EntityLivingBase entity,
        RendererLivingEntity renderer,
        ModelBase model,
        float alpha,
        Options options,
        long lifetimeMillis
    ) {
        float progress = afterimage.getProgress(lifetimeMillis);
        // 上浮：随寿命往上飘，像烟散开前先升起来
        float riseOffset = options.rise * progress * 1.5F;
        // 闪烁：按实体 id + 时间做伪随机抖动，每个残影的节奏不一样
        float flicker = 1.0F;

        if (options.flicker > 0.0F) {
            double seed = afterimage.entityId * 7.0D + System.currentTimeMillis() / 45.0D;
            flicker = 1.0F - options.flicker * 0.65F * (float)Math.abs(Math.sin(seed * 0.9D));
        }

        float shadedAlpha = alpha * flicker;
        float headYaw = afterimage.rotationYawHead - afterimage.renderYawOffset;
        float headPitch = afterimage.rotationPitch;

        float previousSwing = model.swingProgress;
        boolean previousRiding = model.isRiding;
        boolean previousChild = model.isChild;
        boolean isBiped = model instanceof ModelBiped;
        boolean previousSneak = false;
        boolean previousAimedBow = false;

        if (isBiped) {
            ModelBiped biped = (ModelBiped)model;
            previousSneak = biped.isSneak;
            previousAimedBow = biped.aimedBow;
        }

        model.swingProgress = afterimage.swingProgress;
        model.isRiding = afterimage.riding;
        model.isChild = afterimage.child;

        if (isBiped) {
            ModelBiped biped = (ModelBiped)model;
            biped.isSneak = afterimage.sneaking;
            biped.aimedBow = false;
        }

        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_COLOR_MATERIAL);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            // 1) 只写深度，先把轮廓压进深度缓冲
            GL11.glPushMatrix();
            applyTransform(afterimage, camera, 1.0F, renderer, entity, partialTicks, riseOffset);
            GL11.glDepthMask(true);
            GL11.glColorMask(false, false, false, false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.0F);
            model.render(entity, afterimage.limbSwing, afterimage.limbSwingAmount, afterimage.ticksExisted, headYaw, headPitch, MODEL_SCALE);
            GL11.glColorMask(true, true, true, true);
            GL11.glPopMatrix();

            // 2) 实心核心
            GL11.glPushMatrix();
            applyTransform(afterimage, camera, 1.0F, renderer, entity, partialTicks, riseOffset);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glColor4f(options.red, options.green, options.blue, Math.min(1.0F, shadedAlpha * CORE_ALPHA * options.intensity));
            model.render(entity, afterimage.limbSwing, afterimage.limbSwingAmount, afterimage.ticksExisted, headYaw, headPitch, MODEL_SCALE);
            GL11.glPopMatrix();

            // 3) 逐层放大的外壳，样式决定缩放曲线与亮度分布
            float[] styleScales = STYLE_SCALES[Math.max(0, Math.min(STYLE_SCALES.length - 1, options.style))];
            float[] styleAlphas = STYLE_ALPHAS[Math.max(0, Math.min(STYLE_ALPHAS.length - 1, options.style))];
            int shells = Math.max(0, Math.min(styleScales.length, Math.round(styleScales.length * options.intensity)));

            for (int i = 0; i < shells; i++) {
                float scale = styleScales[i];

                // Pulse：外壳随时间往里收，形成一次"呼吸"
                if (options.style == STYLE_PULSE) {
                    scale *= 1.0F - 0.35F * progress;
                }

                GL11.glPushMatrix();
                applyTransform(afterimage, camera, scale, renderer, entity, partialTicks, riseOffset);
                GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glCullFace(GL11.GL_FRONT);

                float jitter = MathHelper.sin((afterimage.entityId + i) * 12.9898F) * 0.5F;
                float layerAlpha = shadedAlpha * styleAlphas[i] * (0.85F + 0.15F * (jitter + 1.0F)) * options.intensity;
                GL11.glColor4f(options.red, options.green, options.blue, Math.min(1.0F, layerAlpha));
                model.render(entity, afterimage.limbSwing, afterimage.limbSwingAmount, afterimage.ticksExisted, headYaw, headPitch, MODEL_SCALE);
                GL11.glPopMatrix();
            }
        } finally {
            model.swingProgress = previousSwing;
            model.isRiding = previousRiding;
            model.isChild = previousChild;

            if (isBiped) {
                ModelBiped biped = (ModelBiped)model;
                biped.isSneak = previousSneak;
                biped.aimedBow = previousAimedBow;
            }
        }
    }

    /** 与 RendererLivingEntity#doRender 的相机 → 实体 → 朝向 → 缩放链保持一致。 */
    private static void applyTransform(
        HitAfterimage afterimage,
        double[] camera,
        float scaleMultiplier,
        RendererLivingEntity renderer,
        EntityLivingBase entity,
        float partialTicks,
        float riseOffset
    ) {
        GL11.glTranslatef((float)(-camera[0]), (float)(-camera[1]), (float)(-camera[2]));
        GL11.glTranslatef((float)afterimage.posX, (float)afterimage.posY + riseOffset, (float)afterimage.posZ);
        GL11.glRotatef(180.0F - afterimage.renderYawOffset, 0.0F, 1.0F, 0.0F);
        GL11.glScalef(-1.0F, -1.0F, 1.0F);

        try {
            renderer.preRenderCallback(entity, partialTicks);
        } catch (Throwable ignored) {
            // 某些生物的缩放回调对类型有要求，失败时就按原尺寸画
        }

        GL11.glTranslatef(0.0F, MODEL_Y_ALIGN, 0.0F);

        if (scaleMultiplier != 1.0F) {
            GL11.glScalef(scaleMultiplier, scaleMultiplier, scaleMultiplier);
        }
    }

    /** 相机（或第三人称下的观察实体）在当前帧的插值位置。 */
    private static double[] cameraPosition(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;

        if (camera == null) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }

        return new double[]{
            camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks,
            camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks,
            camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks
        };
    }
}

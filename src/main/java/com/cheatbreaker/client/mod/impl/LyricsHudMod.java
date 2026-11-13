package com.cheatbreaker.client.mod.impl;

import com.cheatbreaker.client.event.ClientTickEvent;
import com.cheatbreaker.client.event.ScaledResolutionEvent3;
import com.cheatbreaker.client.mod.AbstractMod;
import com.cheatbreaker.client.mod.ModCategory;
import com.cheatbreaker.client.mod.setting.Setting;
import com.cheatbreaker.client.music.NeteaseApi;
import com.cheatbreaker.client.music.NeteaseLyrics;
import com.cheatbreaker.client.music.NeteasePlayer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

/**
 * 当前播放歌曲的歌词，两种显示方式：
 *
 * <ul>
 *   <li><b>POV</b>：屏幕固定的歌词块，当前行放大居中，上下 Context 行渐小渐暗。</li>
 *   <li><b>World</b>：照 Azalea 的 LyricsHud —— 每句在世界里取一个点钉住，投影走真实 GL 矩阵
 *       （透视 + 视角旋转 + 相机平移 + gluProject），屏幕位置再做指数平滑，多句按槽位上下错开。</li>
 * </ul>
 *
 * <p>中日文：客户端自带的 Play 字体没有 CJK 字形，这里用原版 {@link FontRenderer}
 * 并临时打开 {@code unicodeFlag}。原版字体不支持 alpha，淡出用亮度逼近。
 */
public class LyricsHudMod extends AbstractMod {
    private static final String MODE_POV = "POV";
    private static final String MODE_WORLD = "World";
    private static final int GLYPH_HEIGHT = 9;

    /** gluProject 的工作缓冲，复用以避免每帧分配。 */
    private static final FloatBuffer WINDOW_POSITION = GLAllocation.createDirectFloatBuffer(4);
    private static final IntBuffer VIEWPORT = GLAllocation.createDirectIntBuffer(16);
    private static final FloatBuffer MODEL_MATRIX = GLAllocation.createDirectFloatBuffer(16);
    private static final FloatBuffer PROJECTION_MATRIX = GLAllocation.createDirectFloatBuffer(16);
    private static final float[] PROJECTED = new float[3];

    public Setting mode;
    public Setting positionX;
    public Setting positionY;
    public Setting scale;
    public Setting context;
    public Setting leadMs;
    public Setting distance;
    public Setting spread;
    public Setting fadeMs;
    public Setting fadeInMs;
    public Setting nearHide;
    public Setting range;
    /** World 模式里歌词相对眼睛的高度（格）。 */
    public Setting height;

    private volatile NeteaseLyrics lyrics = NeteaseLyrics.EMPTY;
    private volatile long loadingSongId = -1L;
    private long loadedSongId = -1L;
    private long lastLoadAttempt;
    private int lastLineIndex = -1;
    private long lineChangedAt;

    private final List<Cue> cues = new ArrayList<Cue>();
    private int cueIndex = -1;
    private long lastFrameAt;

    public LyricsHudMod() throws java.io.IOException {
        super("Lyrics HUD");

        this.mode = new Setting(this, "Mode")
            .setDescription("POV keeps the lyrics on screen; World floats them in the air like Azalea.")
            .changeValue(MODE_POV)
            .setStringValues(MODE_POV, MODE_WORLD);

        this.positionX = new Setting(this, "Position X")
            .setDescription("Horizontal position of the POV lyric block.")
            .changeValue(50)
            .setRange(0, 100)
            .setUnit("%")
            .setCondition(this::isPov);

        this.positionY = new Setting(this, "Position Y")
            .setDescription("Vertical position of the current POV line.")
            .changeValue(72)
            .setRange(0, 100)
            .setUnit("%")
            .setCondition(this::isPov);

        this.context = new Setting(this, "Context")
            .setDescription("Already-sung / upcoming lines shown around the current one.")
            .changeValue(1)
            .setRange(0, 3);

        this.scale = new Setting(this, "Scale")
            .setDescription("Base text scale in both modes.")
            .changeValue(1.0F)
            .setRange(0.5F, 2.0F);

        this.leadMs = new Setting(this, "Lead")
            .setDescription("Shift the lyric timeline; negative shows lines earlier.")
            .changeValue(0)
            .setRange(-3000, 3000)
            .setUnit("ms");

        this.distance = new Setting(this, "Distance")
            .setDescription("How far in front of you a world line spawns.")
            .changeValue(3.6F)
            .setRange(1.0F, 16.0F)
            .setCondition(this::isWorld);

        this.spread = new Setting(this, "Spread")
            .setDescription("Random sideways range used to keep world lines apart.")
            .changeValue(2.2F)
            .setRange(0.0F, 8.0F)
            .setCondition(this::isWorld);

        this.fadeMs = new Setting(this, "Fade")
            .setDescription("How long a world line lingers after its end time.")
            .changeValue(900)
            .setRange(100, 5000)
            .setUnit("ms")
            .setCondition(this::isWorld);

        this.fadeInMs = new Setting(this, "Fade In")
            .setDescription("Fade-in duration of a world line.")
            .changeValue(260)
            .setRange(0, 2000)
            .setUnit("ms")
            .setCondition(this::isWorld);

        this.nearHide = new Setting(this, "Near Hide")
            .setDescription("Walk closer than this fraction of the spawn distance and the line fades out.")
            .changeValue(85)
            .setRange(30, 100)
            .setUnit("%")
            .setCondition(this::isWorld);

        this.range = new Setting(this, "Range")
            .setDescription("Beyond this distance a world line fades away, then is dropped.")
            .changeValue(24)
            .setRange(6, 48)
            .setUnit("m")
            .setCondition(this::isWorld);

        this.height = new Setting(this, "Height")
            .setDescription("How high above your eyes the world lines float. They stay up there instead of following your pitch down to the ground.")
            .changeValue(2.8F)
            .setRange(-2.0F, 12.0F)
            .setUnit("m")
            .setCondition(this::isWorld);

        this.setDescription("Synced lyrics for the built-in music player, as a POV block or floating in the world.");
        this.setAuthors("Raccoon (Azalea)", "OpenCheatBreaker");
        this.setCategories(ModCategory.MISC);
        this.setIcon(new ResourceLocation("client/icons/mods/mumble.png"), 40, 40);

        this.addEventHandler(ClientTickEvent.class, this::onClientTick);
        this.addEventHandler(ScaledResolutionEvent3.class, this::onRender);
        this.setEnabledByDefault(true);
    }

    private boolean isWorld() {
        return MODE_WORLD.equals(this.mode.getStringValue());
    }

    private boolean isPov() {
        return !this.isWorld();
    }

    /* ------------------------------------------------------------------ */
    /*  歌词加载                                                           */
    /* ------------------------------------------------------------------ */

    private void onClientTick(ClientTickEvent event) {
        NeteaseApi.Song song = NeteasePlayer.current();

        if (song == null) {
            this.lyrics = NeteaseLyrics.EMPTY;
            this.loadedSongId = -1L;
            this.loadingSongId = -1L;
            this.cues.clear();
            this.cueIndex = -1;
            return;
        }

        long songId = song.getId();

        if (songId == this.loadedSongId || songId == this.loadingSongId) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - this.lastLoadAttempt < 2000L) {
            return;
        }

        this.lastLoadAttempt = now;
        this.loadingSongId = songId;

        Thread loader = new Thread(() -> {
            NeteaseLyrics result = NeteaseApi.lyric(songId);

            if (NeteasePlayer.current() != null && NeteasePlayer.current().getId() == songId) {
                this.lyrics = result;
                this.loadedSongId = songId;
                this.lastLineIndex = -1;
                this.cueIndex = -1;
                this.cues.clear();
            }

            if (this.loadingSongId == songId) {
                this.loadingSongId = -1L;
            }
        }, "Lyrics Loader");

        loader.setDaemon(true);
        loader.start();
    }

    private int currentIndex() {
        return this.lyrics.indexAt(NeteasePlayer.positionMs() + this.leadMs.getIntValue());
    }

    /* ------------------------------------------------------------------ */
    /*  入口                                                               */
    /* ------------------------------------------------------------------ */

    private void onRender(ScaledResolutionEvent3 event) {
        if (this.lyrics.isEmpty() || NeteasePlayer.current() == null) {
            return;
        }

        ScaledResolution resolution = event.getScaledResolution();
        FontRenderer font = this.mc.fontRendererObj;
        boolean previousUnicode = font.getUnicodeFlag();
        font.setUnicodeFlag(true);

        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            if (this.isWorld()) {
                this.renderWorld(resolution);
            } else {
                this.renderPov(resolution);
            }
        } catch (Throwable throwable) {
            // EventBus 只把堆栈打到 stderr，这里自己记一份到日志，方便定位
            StringBuilder detail = new StringBuilder(throwable.toString());

            for (int i = 0; i < Math.min(8, throwable.getStackTrace().length); i++) {
                detail.append(" | ").append(throwable.getStackTrace()[i]);
            }

            com.cheatbreaker.client.util.Logging.infoTagged("Lyrics", "render error: %s", detail);
        } finally {
            font.setUnicodeFlag(previousUnicode);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  POV 模式                                                           */
    /* ------------------------------------------------------------------ */

    private void renderPov(ScaledResolution resolution) {
        int index = this.currentIndex();
        NeteaseLyrics.Line line = this.lyrics.line(index);

        if (line == null) {
            return;
        }

        if (index != this.lastLineIndex) {
            this.lastLineIndex = index;
            this.lineChangedAt = System.currentTimeMillis();
        }

        long sinceChange = System.currentTimeMillis() - this.lineChangedAt;
        float fade = Math.min(1.0F, sinceChange / 220.0F);
        float slide = (1.0F - fade) * 3.0F;
        float baseScale = this.scale.getFloatValue();
        float gap = 12.0F * baseScale;
        int contextLines = this.context.getIntValue();

        float centerX = resolution.getScaledWidth() * (this.positionX.getIntValue() / 100.0F);
        float centerY = resolution.getScaledHeight() * (this.positionY.getIntValue() / 100.0F);

        for (int i = contextLines; i >= 1; i--) {
            NeteaseLyrics.Line previous = this.lyrics.line(index - i);

            if (previous == null) {
                continue;
            }

            this.drawCentered(previous.getText(), centerX, centerY - i * gap, baseScale * (1.0F - i * 0.10F), 0xFF6E6E6E);
        }

        int currentColor = blend(0xFF7A7A7A, 0xFFFFFFFF, fade);
        this.drawCentered(line.getText(), centerX, centerY + slide, baseScale * 1.35F, currentColor);

        for (int i = 1; i <= contextLines; i++) {
            NeteaseLyrics.Line next = this.lyrics.line(index + i);

            if (next == null) {
                continue;
            }

            this.drawCentered(next.getText(), centerX, centerY + i * gap, baseScale * (1.0F - i * 0.08F), 0xFF9C9C9C);
        }
    }

    private void drawCentered(String text, float centerX, float y, float textScale, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(textScale, textScale, 1.0F);
        this.mc.fontRendererObj.drawStringWithShadow(text, -this.mc.fontRendererObj.getStringWidth(text) / 2, 0, color);
        GL11.glPopMatrix();
    }

    /* ------------------------------------------------------------------ */
    /*  World 模式                                                         */
    /* ------------------------------------------------------------------ */

    private void renderWorld(ScaledResolution resolution) {
        EntityLivingBase camera = this.mc.renderViewEntity;

        if (camera == null) {
            return;
        }

        long position = NeteasePlayer.positionMs();
        int index = this.currentIndex();

        if (index < 0) {
            index = 0;
        }

        if (index >= this.lyrics.lines().size()) {
            index = this.lyrics.lines().size() - 1;
        }

        this.spawnCue(camera, index, position);
        this.projectCues(resolution, position);
        this.drawCues(position);
    }

    /** 换句时在相机前方取一个新点钉住。 */
    private void spawnCue(EntityLivingBase camera, int index, long position) {
        if (index == this.cueIndex) {
            return;
        }

        this.cueIndex = index;
        NeteaseLyrics.Line line = this.lyrics.line(index);

        if (line == null || line.getText().trim().isEmpty()) {
            return;
        }

        long startMs = line.getTimeMs();
        List<NeteaseLyrics.Line> lines = this.lyrics.lines();
        long endMs = index + 1 < lines.size() ? lines.get(index + 1).getTimeMs() : startMs + 4000L;
        double lateral = this.pickLateral();
        double dist = this.spawnDistance();
        double[] point = this.pointInFront(camera, dist, lateral);

        this.cues.add(new Cue(index, line.getText(), point[0], point[1], point[2], startMs, endMs, lateral, dist));

        // 超出同屏容量：把最老的一句结束时间拉到"现在"，让它走正常淡出而不是被硬切
        int limit = Math.max(1, this.context.getIntValue() + 2);

        while (this.cues.size() > limit) {
            Cue oldest = this.cues.get(0);

            if (oldest.endMs > position) {
                oldest.endMs = position;
            }

            if (position - oldest.endMs > this.fadeMs.getIntValue()) {
                this.cues.remove(0);
            } else {
                break;
            }
        }
    }

    /**
     * 投影全部 cue：自己搭一份和世界渲染一致的相机矩阵（透视 + 视角旋转 + 相机平移），
     * 用 gluProject 取窗口坐标。
     *
     * <p>这个项目的 EntityRenderer 没有单独的 setupCameraTransform（相机矩阵是在 renderWorldPass
     * 里内联拼的），所以按同样顺序重建一份；比手算 forward/right/up 点积准得多 ——
     * 手算版忽略了 FOV、宽高比，转视角时文字会飘。
     */
    private void projectCues(ScaledResolution resolution, long position) {
        if (this.cues.isEmpty()) {
            return;
        }

        EntityLivingBase camera = this.mc.renderViewEntity;

        if (camera == null) {
            return;
        }

        int scaleFactor = Math.max(1, resolution.getScaleFactor());
        float partialTicks = this.mc.timer.renderPartialTicks;
        float yaw = camera.prevRotationYaw + (camera.rotationYaw - camera.prevRotationYaw) * partialTicks;
        float pitch = camera.prevRotationPitch + (camera.rotationPitch - camera.prevRotationPitch) * partialTicks;
        double camX = net.minecraft.client.renderer.entity.RenderManager.instance.viewerPosX;
        double camY = net.minecraft.client.renderer.entity.RenderManager.instance.viewerPosY;
        double camZ = net.minecraft.client.renderer.entity.RenderManager.instance.viewerPosZ;
        float fov = this.mc.gameSettings.fovSetting;
        float aspect = this.mc.displayWidth / (float)Math.max(1, this.mc.displayHeight);
        float hideAt = this.nearHide.getIntValue() / 100.0F;
        float fadeStart = Math.min(1.0F, hideAt + 0.12F);
        int maxRange = this.range.getIntValue();
        float screenWidth = resolution.getScaledWidth();
        float screenHeight = resolution.getScaledHeight();

        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GLU.gluPerspective(fov, aspect, 0.05F, 512.0F);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotatef(pitch, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(yaw + 180.0F, 0.0F, 1.0F, 0.0F);
        GL11.glTranslated(-camX, -camY, -camZ);

        try {
            for (Cue cue : this.cues) {
                float relativeX = (float)(cue.x - camX);
                float relativeY = (float)(cue.y - camY);
                float relativeZ = (float)(cue.z - camZ);
                // 矩阵里已经带了 -cam 平移，所以这里直接传世界坐标
                float[] projected = convert2D((float)cue.x, (float)cue.y, (float)cue.z, scaleFactor);

                if (projected == null) {
                    cue.onScreen = false;
                    continue;
                }

                double blockDistance = Math.max(0.6D, Math.sqrt(
                    relativeX * relativeX + relativeY * relativeY + relativeZ * relativeZ
                ));

                // 走近了就淡掉，避免糊脸：ratio 是「当前距离 / 出生距离」
                double ratio = blockDistance / Math.max(1.0D, cue.bornDistance);
                cue.nearFade = Math.max(0.0F, Math.min(1.0F, (float)((ratio - hideAt) / Math.max(0.01F, fadeStart - hideAt))));

                // 走远了变淡（最多压一半），真正回收交给下面的距离硬阈值
                if (blockDistance > maxRange) {
                    float over = (float)((blockDistance - maxRange) / Math.max(1.0D, maxRange * 0.5D));
                    cue.nearFade *= 1.0F - Math.min(1.0F, over) * 0.5F;
                }

                // 只记录"是否在视口里"，不拿它决定画不画
                cue.inView = projected[0] > -80.0F
                    && projected[0] < screenWidth + 80.0F
                    && projected[1] > -80.0F
                    && projected[1] < screenHeight + 80.0F;

                cue.screenX = projected[0];
                cue.screenY = projected[1];
                // Azalea 的字号公式：90 / 距离 × 用户缩放，量化到 4px 一档
                float rawSize = Math.max(6.0F, Math.min(72.0F, (float)(90.0D / blockDistance) * this.scale.getFloatValue()));
                cue.screenSize = Math.round(rawSize / 4.0F) * 4.0F;
                cue.onScreen = true;

                if (blockDistance > maxRange * 2.0D) {
                    cue.outOfRange = true;
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
        }

        // 被甩到视野外、但还在唱的那句：搬到此刻眼前，而不是让你对着空气等它
        for (Cue cue : this.cues) {
            if (cue.inView || cue.outOfRange) {
                continue;
            }

            if (position > cue.endMs) {
                continue;
            }

            cue.lateral = this.pickLateral();
            cue.bornDistance = this.spawnDistance();
            double[] point = this.pointInFront(camera, cue.bornDistance, cue.lateral);
            cue.x = point[0];
            cue.y = point[1];
            cue.z = point[2];
            // 故意不重置平滑状态：让它从原来的屏幕位置"滑"到眼前，而不是瞬移过去
        }

        // 同屏句子按槽位上下错开：一行字很宽，横向偏移根本拉不开
        int slot = 0;

        for (Cue cue : this.cues) {
            if (!cue.onScreen) {
                continue;
            }

            cue.screenY += slot * (cue.screenSize * 1.35F);
            slot++;
        }

        // 位置 / 尺寸 / 淡出都做与帧率无关的指数平滑，看起来才是"贴着空气"而不是每帧跳
        long now = System.currentTimeMillis();
        float dt = this.lastFrameAt == 0L ? 0.0F : Math.min(0.1F, (now - this.lastFrameAt) / 1000.0F);
        this.lastFrameAt = now;
        float ease = 1.0F - (float)Math.exp(-dt * 14.0F);

        for (Cue cue : this.cues) {
            if (!cue.shownInit) {
                cue.shownX = cue.screenX;
                cue.shownY = cue.screenY;
                cue.shownSize = cue.screenSize;
                cue.shownFade = cue.nearFade;
                cue.shownInit = true;
                continue;
            }

            cue.shownX += (cue.screenX - cue.shownX) * ease;
            cue.shownY += (cue.screenY - cue.shownY) * ease;
            cue.shownSize += (cue.screenSize - cue.shownSize) * ease;
            cue.shownFade += (cue.nearFade - cue.shownFade) * ease;
        }

        this.cues.removeIf(cue -> cue.outOfRange);
    }

    private void drawCues(long position) {
        long fade = Math.max(1L, this.fadeMs.getIntValue());
        long fadeIn = Math.max(1L, this.fadeInMs.getIntValue());

        for (Cue cue : this.cues) {
            if (!cue.onScreen || cue.shownSize < 4.0F) {
                continue;
            }

            float alpha = 1.0F;

            if (position > cue.endMs) {
                alpha = 1.0F - (position - cue.endMs) / (float)fade;
            }

            long born = cue.startMs - this.leadMs.getIntValue();
            float t = Math.max(0.0F, Math.min(1.0F, (position - born) / (float)fadeIn));
            alpha *= t * t * (3.0F - 2.0F * t);   // smoothstep 淡入
            alpha *= cue.shownFade;

            // 当前这一句永远留一个可见下限：距离/淡出参数调得再极端，也不至于整条消失
            if (cue.lineIndex == this.cueIndex) {
                alpha = Math.max(alpha, 0.45F);
            }

            if (alpha <= 0.02F) {
                continue;
            }

            // 原版字体没有 alpha 通道，用亮度逼近淡出
            int level = (int)(255.0F * Math.max(0.0F, Math.min(1.0F, alpha)));
            int color = 0xFF000000 | (level << 16) | (level << 8) | level;
            float textScale = cue.shownSize / GLYPH_HEIGHT;
            String text = cue.text;

            GL11.glPushMatrix();
            GL11.glTranslatef(cue.shownX, cue.shownY, 0.0F);
            GL11.glScalef(textScale, textScale, 1.0F);
            this.mc.fontRendererObj.drawStringWithShadow(
                text,
                -this.mc.fontRendererObj.getStringWidth(text) / 2,
                -GLYPH_HEIGHT / 2,
                color
            );
            GL11.glPopMatrix();
        }
    }

    /** 世界坐标 → 窗口坐标（除以 GUI 缩放），在相机背后返回 null。 */
    private static float[] convert2D(float x, float y, float z, int scaleFactor) {
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_MATRIX);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_MATRIX);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);

        if (GLU.gluProject(x, y, z, MODEL_MATRIX, PROJECTION_MATRIX, VIEWPORT, WINDOW_POSITION)) {
            PROJECTED[0] = WINDOW_POSITION.get(0) / scaleFactor;
            PROJECTED[1] = (net.minecraft.client.Minecraft.getMinecraft().displayHeight - WINDOW_POSITION.get(1)) / scaleFactor;
            PROJECTED[2] = WINDOW_POSITION.get(2);
            return PROJECTED;
        }

        return null;
    }

    /** 在 Spread 范围内挑一个离现有句子最远的横向偏移，避免新句压到还在淡出的旧句。 */
    private double pickLateral() {
        double rangeValue = this.spread.getFloatValue();

        if (rangeValue <= 0.05D) {
            return 0.0D;
        }

        double best = 0.0D;
        double bestScore = -1.0D;

        for (int attempt = 0; attempt < 8; attempt++) {
            double candidate = (Math.random() * 2.0D - 1.0D) * rangeValue;
            double nearest = Double.MAX_VALUE;

            for (Cue other : this.cues) {
                if (!other.onScreen) {
                    continue;
                }

                nearest = Math.min(nearest, Math.abs(candidate - other.lateral));
            }

            if (nearest > bestScore) {
                bestScore = nearest;
                best = candidate;
            }
        }

        return best;
    }

    private double spawnDistance() {
        return Math.max(1.0D, this.distance.getFloatValue() + (Math.random() * 2.0D - 1.0D) * 0.6D);
    }

    /**
     * 相机水平前方 dist 格、横向 lateral 格、高度固定在眼睛上方 height 格处的世界坐标。
     *
     * <p>这里刻意只用 yaw、不用 pitch：否则玩家一低头，歌词就跟着视线钻到地面里去
     * （原来的 -sin(pitch) * dist 就是这个行为）。现在它总是漂在头顶那片天，
     * 抬头低头只是在屏幕上上下移动。
     */
    private double[] pointInFront(EntityLivingBase camera, double dist, double lateral) {
        float yawRad = (float)Math.toRadians(camera.rotationYaw);

        double x = camera.posX - Math.sin(yawRad) * dist + Math.cos(yawRad) * lateral;
        double z = camera.posZ + Math.cos(yawRad) * dist + Math.sin(yawRad) * lateral;
        double y = camera.posY + camera.getEyeHeight() + this.height.getFloatValue();

        return new double[]{x, y, z};
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int)(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * ratio);
        int g = (int)(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * ratio);
        int b = (int)((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 一句钉在世界坐标上的歌词，外加一份屏幕平滑状态。 */
    private static final class Cue {
        private final int lineIndex;
        private final String text;
        private final long startMs;
        private long endMs;
        private double x;
        private double y;
        private double z;
        private double lateral;
        private double bornDistance;

        private boolean onScreen;
        private boolean inView;
        private boolean outOfRange;
        private boolean shownInit;
        private float screenX;
        private float screenY;
        private float screenSize;
        private float nearFade;
        private float shownX;
        private float shownY;
        private float shownSize;
        private float shownFade;

        private Cue(int lineIndex, String text, double x, double y, double z, long startMs, long endMs, double lateral, double bornDistance) {
            this.lineIndex = lineIndex;
            this.text = text;
            this.x = x;
            this.y = y;
            this.z = z;
            this.startMs = startMs;
            this.endMs = endMs;
            this.lateral = lateral;
            this.bornDistance = bornDistance;
        }
    }
}

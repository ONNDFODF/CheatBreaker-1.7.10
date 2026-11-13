package com.cheatbreaker.client.mod.impl;

import com.cheatbreaker.client.event.ClientTickEvent;
import com.cheatbreaker.client.event.EntityS19PacketEntityStatusEvent;
import com.cheatbreaker.client.event.RenderGlobalFloatEvent;
import com.cheatbreaker.client.mod.AbstractMod;
import com.cheatbreaker.client.mod.ModCategory;
import com.cheatbreaker.client.mod.setting.Setting;
import com.cheatbreaker.client.render.afterimage.HitAfterimage;
import com.cheatbreaker.client.render.afterimage.HitAfterimageRenderer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * 受击残影。
 *
 * <p>实体被打中的瞬间，就地定格一份模型快照，并用「反相外壳 + 多层柔晕」把它画成
 * 白色的烟雾轮廓，随后在设置的存活时间内淡出。功能取自 HitAfterimage 1.0
 * （作者 Raccoon），这里移植到客户端 ModManager 里，可以单独开关与调参。
 *
 * <p>受击检测有两条来源，和原 mod 一致：
 * <ul>
 *   <li>{@code S19PacketEntityStatus}(status 2)：服务器广播的受伤动画，最准；</li>
 *   <li>本地每 tick 轮询：hurtTime 上升或血量下降，兜住没有广播包的情况。</li>
 * </ul>
 */
public class HitAfterimageMod extends AbstractMod {
    /** 同一实体两次残影之间的最小间隔，避免连击刷屏。 */
    private static final long SPAWN_COOLDOWN_MILLIS = 50L;

    public Setting lifetime;
    public Setting maxAfterimages;
    public Setting onlyPlayers;
    /** 整体不透明度，百分比。 */
    public Setting opacity;
    /** 效果强度，百分比：同时放大外壳层数与每层亮度。 */
    public Setting intensity;
    /** 残影颜色（ARGB），只用 RGB，透明度由 Opacity 控制。 */
    public Setting color;
    /** 样式：Smoke / Ring / Pulse / Flat。 */
    public Setting style;
    /** 上浮幅度，百分比（0 = 原地不动）。 */
    public Setting rise;
    /** 亮度抖动，百分比。 */
    public Setting flicker;

    /** 每帧复用，避免堆分配。 */
    private final HitAfterimageRenderer.Options renderOptions = new HitAfterimageRenderer.Options();

    private final List<HitAfterimage> afterimages = new CopyOnWriteArrayList<HitAfterimage>();
    private final Map<Integer, Float> lastHealth = new HashMap<Integer, Float>();
    private final Map<Integer, Integer> lastHurtTime = new HashMap<Integer, Integer>();
    private final Map<Integer, Long> lastSpawn = new HashMap<Integer, Long>();
    private int cleanupTicker;

    public HitAfterimageMod() throws java.io.IOException {
        super("Hit Afterimage");

        this.lifetime = new Setting(this, "Lifetime")
            .setDescription("How long the afterimage stays visible.")
            .changeValue(1380)
            .setRange(100, 5000)
            .setUnit("ms");

        this.maxAfterimages = new Setting(this, "Max Afterimages")
            .setDescription("Upper bound of afterimages alive at the same time.")
            .changeValue(24)
            .setRange(1, 64);

        this.onlyPlayers = new Setting(this, "Only Players")
            .setDescription("Only spawn afterimages for players, ignore mobs.")
            .changeValue(false);

        this.opacity = new Setting(this, "Opacity")
            .setDescription("Overall transparency of the afterimage. 100% is the original look.")
            .changeValue(100)
            .setRange(5, 200)
            .setUnit("%");

        this.intensity = new Setting(this, "Intensity")
            .setDescription("How thick the smoke outline is: scales the number of shells and their brightness.")
            .changeValue(100)
            .setRange(10, 200)
            .setUnit("%");

        this.color = new Setting(this, "Color")
            .setDescription("Tint of the afterimage. White keeps the classic smoke look.")
            .changeValue(-1)
            .setRange(Integer.MIN_VALUE, Integer.MAX_VALUE);

        this.style = new Setting(this, "Style")
            .setDescription("Smoke hugs the body, Ring spreads into a band, Pulse breathes inward, Flat keeps only the solid silhouette.")
            .changeValue("Smoke")
            .setStringValues("Smoke", "Ring", "Pulse", "Flat");

        this.rise = new Setting(this, "Rise")
            .setDescription("How far the afterimage drifts upward over its lifetime.")
            .changeValue(0)
            .setRange(0, 200)
            .setUnit("%");

        this.flicker = new Setting(this, "Flicker")
            .setDescription("Random brightness flicker, each afterimage on its own rhythm.")
            .changeValue(0)
            .setRange(0, 100)
            .setUnit("%");

        this.setDescription("Leaves a fading white smoke outline where an entity got hit.");
        this.setAuthors("Raccoon (HitAfterimage)", "OpenCheatBreaker");
        this.setCategories(ModCategory.MISC);
        this.setIcon(new ResourceLocation("client/icons/mods/hittint.png"), 40, 40);

        this.addEventHandler(EntityS19PacketEntityStatusEvent.class, this::onEntityStatus);
        this.addEventHandler(ClientTickEvent.class, this::onClientTick);
        this.addEventHandler(RenderGlobalFloatEvent.class, this::onRenderGlobal);
        this.setEnabledByDefault(true);
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.afterimages.clear();
        this.lastHealth.clear();
        this.lastHurtTime.clear();
        this.lastSpawn.clear();
    }

    /** 服务器广播的受伤动画（status 2）。 */
    private void onEntityStatus(EntityS19PacketEntityStatusEvent event) {
        if (event.getS19PacketEntityStatus().getStatus() != 2) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof EntityLivingBase) || entity.isDead) {
            return;
        }

        if (this.mc.thePlayer != null && entity.getEntityId() == this.mc.thePlayer.getEntityId()) {
            return;
        }

        this.addAfterimage((EntityLivingBase)entity);
    }

    /** 本地兜底检测：受伤动画计数上升或血量下降都算一次受击。 */
    private void onClientTick(ClientTickEvent event) {
        if (this.mc.theWorld == null || this.mc.thePlayer == null) {
            return;
        }

        Set<Integer> visible = new HashSet<Integer>();

        for (Object entry : this.mc.theWorld.loadedEntityList) {
            if (!(entry instanceof EntityLivingBase)) {
                continue;
            }

            EntityLivingBase living = (EntityLivingBase)entry;
            int id = living.getEntityId();
            visible.add(id);

            if (living == this.mc.thePlayer || living.isDead) {
                continue;
            }

            Float previousHealth = this.lastHealth.get(id);
            Integer previousHurtTime = this.lastHurtTime.get(id);
            int hurtTime = living.hurtTime;
            boolean hurtTimeRose = previousHurtTime != null && hurtTime > previousHurtTime;
            boolean healthDropped = previousHealth != null && living.getHealth() < previousHealth - 0.01F;

            this.lastHealth.put(id, living.getHealth());
            this.lastHurtTime.put(id, hurtTime);

            if (hurtTimeRose || healthDropped) {
                this.addAfterimage(living);
            }
        }

        // 每 64 tick 清一次状态表，实体走远/死亡后不会一直留在 Map 里
        if ((++this.cleanupTicker & 63) == 0) {
            this.lastHealth.keySet().retainAll(visible);
            this.lastHurtTime.keySet().retainAll(visible);
            this.lastSpawn.keySet().retainAll(visible);
        }
    }

    /** 世界渲染的最后一步，此时相机变换已经生效。 */
    private void onRenderGlobal(RenderGlobalFloatEvent event) {
        if (this.afterimages.isEmpty()) {
            return;
        }

        long lifetimeMillis = this.lifetime.getIntValue();
        this.afterimages.removeIf(afterimage -> afterimage.isExpired(lifetimeMillis));

        int argb = this.color.getIntValue();
        this.renderOptions.opacity = this.opacity.getIntValue() / 100.0F;
        this.renderOptions.intensity = this.intensity.getIntValue() / 100.0F;
        this.renderOptions.red = ((argb >> 16) & 0xFF) / 255.0F;
        this.renderOptions.green = ((argb >> 8) & 0xFF) / 255.0F;
        this.renderOptions.blue = (argb & 0xFF) / 255.0F;
        this.renderOptions.style = styleIndex(this.style.getStringValue());
        this.renderOptions.rise = this.rise.getIntValue() / 100.0F;
        this.renderOptions.flicker = this.flicker.getIntValue() / 100.0F;

        HitAfterimageRenderer.render(this.afterimages, event.getFloatValue(), lifetimeMillis, this.renderOptions);
    }

    private static int styleIndex(String value) {
        if ("Ring".equals(value)) {
            return HitAfterimageRenderer.STYLE_RING;
        }

        if ("Pulse".equals(value)) {
            return HitAfterimageRenderer.STYLE_PULSE;
        }

        if ("Flat".equals(value)) {
            return HitAfterimageRenderer.STYLE_FLAT;
        }

        return HitAfterimageRenderer.STYLE_SMOKE;
    }

    private void addAfterimage(EntityLivingBase entity) {
        if (this.onlyPlayers.getBooleanValue() && !(entity instanceof EntityPlayer)) {
            return;
        }

        int id = entity.getEntityId();
        long now = System.currentTimeMillis();
        Long previous = this.lastSpawn.get(id);

        if (previous != null && now - previous < SPAWN_COOLDOWN_MILLIS) {
            return;
        }

        this.lastSpawn.put(id, now);

        int limit = this.maxAfterimages.getIntValue();
        while (this.afterimages.size() >= limit && !this.afterimages.isEmpty()) {
            this.afterimages.remove(0);
        }

        this.afterimages.add(new HitAfterimage(entity));
    }
}

package com.cheatbreaker.client.mod.impl;

import com.cheatbreaker.client.event.ClientTickEvent;
import com.cheatbreaker.client.event.IntEvent4;
import com.cheatbreaker.client.mod.AbstractMod;
import com.cheatbreaker.client.mod.ModCategory;
import com.cheatbreaker.client.mod.setting.Setting;
import com.cheatbreaker.client.music.NeteasePlayer;
import com.cheatbreaker.client.ui.screen.MusicScreen;
import net.minecraft.util.ResourceLocation;

/**
 * 内置网易云音乐入口。
 *
 * <p>除了开关和按键，这里还托管播放器的两个持久状态：音量与循环模式。
 * 它们存在 mod 设置里，随 profile 一起保存，切世界 / 重开游戏都会恢复；
 * 界面上动音量或换循环模式时会先写回设置，所以不会被下一 tick 的同步覆盖。
 */
public class MusicMod extends AbstractMod {
    public Setting openMenuKeybind;
    public Setting stopOnWorldChange;
    public Setting volume;
    public Setting loopMode;
    /** 上次播的那首，重启后装回来，免得歌词 HUD 一片空白。 */
    public Setting lastTrack;

    /** 上一 tick 是否在世界里；只有「从世界退回菜单」才停播，主菜单里点歌不会被立刻收掉。 */
    private boolean hadWorld;
    private int syncedVolume = -1;
    private String syncedLoopMode;
    private boolean restored;

    public MusicMod() throws java.io.IOException {
        super("Music");

        this.openMenuKeybind = new Setting(this, "Open Menu Keybind")
            .setDescription("Key that opens the music player.")
            .changeValue(0)
            .setMouseBind(false);

        this.stopOnWorldChange = new Setting(this, "Stop On World Change")
            .setDescription("Stop playback when the world is unloaded.")
            .changeValue(true);

        this.volume = new Setting(this, "Volume")
            .setDescription("Playback volume; also editable in the player.")
            .changeValue(80)
            .setRange(0, 100)
            .setUnit("%");

        this.loopMode = new Setting(this, "Loop Mode")
            .setDescription("LIST repeats the queue, ONE repeats the track, OFF stops at the end.")
            .changeValue("LIST")
            .setStringValues("LIST", "ONE", "OFF");

        this.lastTrack = new Setting(this, "Last Track")
            .setDescription("Internally remembers the last played track so it comes back after a restart.")
            .changeValue("");

        this.setDescription("Built-in NetEase Cloud Music player: login, playlists, search and playback.");
        this.setAuthors("Raccoon (Azalea)", "OpenCheatBreaker");
        this.setCategories(ModCategory.MISC);
        this.setIcon(new ResourceLocation("client/icons/mods/swordblur.png"), 40, 40);

        this.addEventHandler(IntEvent4.class, this::onKeyPress2);
        this.addEventHandler(ClientTickEvent.class, this::onClientTick);
        this.setEnabledByDefault(true);
        this.pushSettings();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        NeteasePlayer.stop();
    }

    private void onKeyPress2(IntEvent4 event) {
        int key = this.openMenuKeybind.getIntValue();

        if (key == 0 || event.getIntValue() != key) {
            return;
        }

        if (this.mc.currentScreen == null) {
            try {
                this.mc.displayGuiScreen(new MusicScreen());
            } catch (java.io.IOException exception) {
                this.cheatBreaker.getLogger().error("Could not open the music screen", exception);
            }
        }
    }

    private void onClientTick(ClientTickEvent event) {
        if (!this.restored) {
            this.restored = true;
            this.restoreLastTrack();
        }

        boolean hasWorld = this.mc.theWorld != null;

        // 注意判断的是「变化」而不是「当前没有世界」：
        // 主菜单里 theWorld 一直是 null，按 null 判断会在点歌的下一 tick 就把播放停掉。
        if (this.stopOnWorldChange.getBooleanValue() && this.hadWorld && !hasWorld && NeteasePlayer.current() != null) {
            NeteasePlayer.stop();
        }

        this.hadWorld = hasWorld;
        this.pushSettings();
    }

    /** 重开游戏后把上次那首装回来（暂停状态）：歌词 HUD 立刻有内容，不会一片空白。 */
    private void restoreLastTrack() {
        if (NeteasePlayer.current() != null) {
            return;
        }

        String saved = this.lastTrack.getStringValue();

        if (saved == null || saved.trim().isEmpty()) {
            return;
        }

        String[] parts = saved.split("\\|", 6);

        if (parts.length < 6) {
            return;
        }

        try {
            long id = Long.parseLong(parts[0]);
            long duration = Long.parseLong(parts[5]);
            NeteasePlayer.restore(new com.cheatbreaker.client.music.NeteaseApi.Song(id, parts[1], parts[2], parts[3], parts[4], duration));
        } catch (Throwable ignored) {
            // 存的格式不对就当没有
        }
    }

    /** 界面里点歌时记一笔，重启后能恢复。 */
    public void onTrackSelected(com.cheatbreaker.client.music.NeteaseApi.Song song) {
        if (song == null) {
            return;
        }

        String payload = song.getId()
            + "|"
            + nullToEmpty(song.getName())
            + "|"
            + nullToEmpty(song.getArtist())
            + "|"
            + nullToEmpty(song.getAlbum())
            + "|"
            + nullToEmpty(song.getPicUrl())
            + "|"
            + song.getDurationMs();

        this.lastTrack.changeValue(payload);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 把设置里的音量 / 循环模式同步给播放器（只在值变了的时候动手）。 */
    private void pushSettings() {
        int volume = this.volume.getIntValue();

        if (volume != this.syncedVolume) {
            this.syncedVolume = volume;
            NeteasePlayer.setVolume(volume / 100.0F);
        }

        String loop = this.loopMode.getStringValue();

        if (loop != null && !loop.equals(this.syncedLoopMode)) {
            this.syncedLoopMode = loop;

            if ("ONE".equals(loop)) {
                NeteasePlayer.setLoopMode(NeteasePlayer.LoopMode.ONE);
            } else if ("OFF".equals(loop)) {
                NeteasePlayer.setLoopMode(NeteasePlayer.LoopMode.OFF);
            } else {
                NeteasePlayer.setLoopMode(NeteasePlayer.LoopMode.LIST);
            }
        }
    }

    /** 界面里改了音量：写回设置，让 profile 记住。 */
    public void onVolumeChangedFromUi(float value) {
        this.syncedVolume = Math.round(value * 100.0F);
        this.volume.changeValue(this.syncedVolume);
    }

    /** 界面里换了循环模式：写回设置。 */
    public void onLoopModeChangedFromUi(NeteasePlayer.LoopMode mode) {
        this.syncedLoopMode = mode.name();
        this.loopMode.changeValue(mode.name());
    }
}

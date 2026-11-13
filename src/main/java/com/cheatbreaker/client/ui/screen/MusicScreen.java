package com.cheatbreaker.client.ui.screen;

import com.cheatbreaker.client.CheatBreaker;
import com.cheatbreaker.client.font.CustomFontRenderer;
import com.cheatbreaker.client.music.NeteaseApi;
import com.cheatbreaker.client.music.NeteasePlayer;
import com.cheatbreaker.client.music.NeteaseQrLogin;
import com.cheatbreaker.client.render.RenderUtils;
import com.cheatbreaker.client.ui.component.TextField;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * 内置网易云音乐界面。
 *
 * <p>布局照搬原播放器（顶栏 / 歌单 / 曲目 / 底部播放条），外观换成客户端自己的：
 * 半透明深色面板、直角描边，没有圆角、模糊和阴影。
 *
 * <p>文字统一走原版 {@link FontRenderer} 并临时打开 {@code unicodeFlag}：
 * 客户端自带的 Play 字体没有中日文字形，歌名 / 歌手里只要有假名或汉字就会变成空白。
 *
 * <p>界面同时用在主菜单和游戏内（Copilot / Mods 界面的音乐按钮），
 * 世界里关闭时直接回到游戏，而不是跳回主菜单。
 */
public class MusicScreen extends MenuScreen {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Music IO");
        thread.setDaemon(true);
        return thread;
    });

    private static final int PANEL_COLOR = 0x6E000000;
    private static final int PANEL_BORDER = 0x16FFFFFF;
    private static final int DIVIDER = 0x1AFFFFFF;
    private static final int ROW_HOVER = 0x14FFFFFF;
    private static final int ROW_ACTIVE = 0x2EFFFFFF;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB4B4B4;
    private static final int TEXT_MUTED = 0xFF7A7A7A;
    private static final int ACCENT = 0xFFFF6B6B;
    private static final long QR_POLL_MILLIS = 1500L;

    private static final float ROW_HEIGHT = 20.0F;
    private static final float SIDEBAR_WIDTH = 178.0F;
    private static final float TITLE_SCALE = 1.6F;
    private static final float BODY_SCALE = 1.3F;
    private static final float SMALL_SCALE = 1.15F;

    private final TextField searchField = new TextField(
        CheatBreaker.getInstance().getFontManager().playRegular14,
        "Search songs...",
        0x2FFFFFFF,
        0x6FFFFFFF
    );

    private volatile List<NeteaseApi.Playlist> playlists = Collections.emptyList();
    private volatile List<NeteaseApi.Song> tracks = Collections.emptyList();
    private volatile long accountUid = -1L;
    private volatile boolean loading;
    private volatile String statusText = "";

    private final Map<String, ResourceLocation> covers = new ConcurrentHashMap<String, ResourceLocation>();
    private final Set<String> coverPending = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<Runnable>();

    private int playlistScroll;
    private int trackScroll;
    private long lastQrPoll;
    private ResourceLocation qrTexture;
    private String qrContent;
    private boolean loginVisible;
    private boolean draggingProgress;
    private boolean draggingVolume;
    private int playlistHover = -1;
    private int trackHover = -1;

    public MusicScreen() {
        this.searchField.setMaxStringLength(120);
        this.ensureAccount();
    }

    /* ------------------------------------------------------------------ */
    /*  文本（支持中日文）                                                  */
    /* ------------------------------------------------------------------ */

    private FontRenderer font() {
        return this.mc.fontRendererObj;
    }

    /** 固定英文文案：用客户端自己的界面字体，和别的界面同一套基线，不会错位。 */
    private void drawText(String text, float x, float y, float scale, int color) {
        CheatBreaker.getInstance().getFontManager().playRegular12.drawString(text, x, y, color);
    }

    private void drawTextCentered(String text, float centerX, float y, float scale, int color) {
        CustomFontRenderer renderer = CheatBreaker.getInstance().getFontManager().playRegular12;
        renderer.drawString(text, centerX - renderer.getStringWidth(text) / 2.0F, y, color);
    }

    /**
     * 动态文案（歌名 / 歌手 / 歌单名）：可能是中文或日文，
     * 只能用原版字体直绘；这里不做 GL 缩放，坐标和矩形完全同一套，不会偏。
     */
    private void drawDynamic(String text, float x, float y, int color) {
        this.font().drawStringWithShadow(text, (int)x, (int)y, color);
    }

    private float dynamicWidth(String text) {
        return this.font().getStringWidth(text);
    }

    private String trimDynamic(String text, float maxWidth) {
        String value = text == null ? "" : text;

        if (this.dynamicWidth(value) <= maxWidth) {
            return value;
        }

        while (value.length() > 1 && this.dynamicWidth(value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }

        return value + "...";
    }

    private float textWidth(String text, float scale) {
        return CheatBreaker.getInstance().getFontManager().playRegular12.getStringWidth(text);
    }

    private String trim(String text, float maxWidth, float scale) {
        String value = text == null ? "" : text;

        if (this.textWidth(value, scale) <= maxWidth) {
            return value;
        }

        while (value.length() > 1 && this.textWidth(value + "...", scale) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }

        return value + "...";
    }

    /* ------------------------------------------------------------------ */
    /*  数据                                                               */
    /* ------------------------------------------------------------------ */

    private void ensureAccount() {
        IO.execute(() -> {
            long uid = NeteaseApi.accountUid();
            this.accountUid = uid;

            if (uid > 0L) {
                this.playlists = NeteaseApi.playlists(uid);
                this.statusText = "";
            } else {
                this.playlists = Collections.emptyList();
                this.statusText = "Not logged in";
            }
        });
    }

    private void doSearch() {
        String keyword = this.searchField.getText();

        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }

        this.loading = true;
        this.trackScroll = 0;
        IO.execute(() -> {
            List<NeteaseApi.Song> result = NeteaseApi.search(keyword, 50);
            this.tracks = result;
            this.statusText = result.isEmpty() ? "No results" : "";
            this.loading = false;
        });
    }

    private void openPlaylist(NeteaseApi.Playlist playlist) {
        this.loading = true;
        this.trackScroll = 0;
        IO.execute(() -> {
            List<NeteaseApi.Song> result = NeteaseApi.playlistTracks(playlist.getId());
            this.tracks = result;
            NeteasePlayer.setQueue(result);
            this.statusText = result.isEmpty() ? "Empty playlist" : "";
            this.loading = false;
        });
    }

    /* ------------------------------------------------------------------ */
    /*  绘制                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void drawScreenScaled(float mouseX, float mouseY) {
        boolean previousUnicode = this.font().getUnicodeFlag();
        this.font().setUnicodeFlag(true);

        try {
            float panelWidth = Math.min(640.0F, this.getScaledWidth() - 40.0F);
            float panelHeight = Math.min(420.0F, this.getScaledHeight() - 40.0F);
            float left = Math.round((this.getScaledWidth() - panelWidth) / 2.0F);
            float top = Math.round((this.getScaledHeight() - panelHeight) / 2.0F);
            float right = left + panelWidth;
            float bottom = top + panelHeight;

            RenderUtils.drawBorderedRect(left, top, right, bottom, PANEL_BORDER, PANEL_COLOR);

            this.drawHeader(mouseX, mouseY, left, top, right);
            this.drawSearchRow(mouseX, mouseY, left, top + 34.0F, right);

            float bodyTop = top + 66.0F;
            float bodyBottom = bottom - 62.0F;
            this.drawSidebar(mouseX, mouseY, left, bodyTop, left + SIDEBAR_WIDTH, bodyBottom);

            float trackLeft = left + SIDEBAR_WIDTH + 6.0F;
            float trackRight = right - 8.0F;
            this.drawTracks(mouseX, mouseY, trackLeft, bodyTop, trackRight, bodyBottom);
            this.drawFooter(mouseX, mouseY, left, bottom - 58.0F, right, bottom - 6.0F);

            if (this.loginVisible) {
                this.drawLoginOverlay(mouseX, mouseY, left, top, right, bottom);
            }
        } finally {
            this.font().setUnicodeFlag(previousUnicode);
        }
    }

    private void drawHeader(float mouseX, float mouseY, float left, float top, float right) {
        this.drawText("MUSIC", left + 12.0F, top + 7.0F, TITLE_SCALE, TEXT_PRIMARY);

        String state = this.accountUid > 0L ? "netease cloud \u00b7 logged in" : "netease cloud \u00b7 not logged in";
        this.drawText(state, left + 78.0F, top + 17.0F, SMALL_SCALE, TEXT_MUTED);

        String action = this.accountUid > 0L ? "LOGOUT" : "LOGIN";
        float actionWidth = 48.0F;
        float actionX = right - actionWidth - 10.0F;
        boolean hover = this.inside(mouseX, mouseY, actionX, top + 7.0F, actionWidth, 16.0F);

        RenderUtils.drawRectSized(actionX, top + 7.0F, actionWidth, 16.0F, hover ? ROW_ACTIVE : ROW_HOVER);
        this.drawTextCentered(action, actionX + actionWidth / 2.0F, top + 11.0F, SMALL_SCALE, TEXT_PRIMARY);

        // BACK 放在 LOGOUT 左边：底栏全是播放控制按钮，放进底栏会和它们抢点击
        float backX = actionX - 52.0F;
        boolean backHover = this.inside(mouseX, mouseY, backX, top + 7.0F, 46.0F, 16.0F);
        RenderUtils.drawRectSized(backX, top + 7.0F, 46.0F, 16.0F, backHover ? ROW_ACTIVE : ROW_HOVER);
        this.drawTextCentered("BACK", backX + 23.0F, top + 11.0F, SMALL_SCALE, TEXT_PRIMARY);

        if (this.loading) {
            this.drawText("loading...", right - 112.0F, top + 17.0F, SMALL_SCALE, TEXT_MUTED);
        } else if (!this.statusText.isEmpty()) {
            this.drawText(this.statusText, right - 112.0F - this.textWidth(this.statusText, SMALL_SCALE), top + 17.0F, SMALL_SCALE, TEXT_MUTED);
        }

        RenderUtils.drawRectSized(left, top + 30.0F, right - left, 1.0F, DIVIDER);
    }

    private void drawSearchRow(float mouseX, float mouseY, float left, float top, float right) {
        float fieldX = left + 12.0F;
        float fieldWidth = right - left - 96.0F;
        this.searchField.setBounds(fieldX, top + 3.0F, fieldWidth, 16.0F);
        this.searchField.render(mouseX, mouseY, true);

        float buttonX = fieldX + fieldWidth + 8.0F;
        boolean hover = this.inside(mouseX, mouseY, buttonX, top + 3.0F, 68.0F, 16.0F);
        RenderUtils.drawRectSized(buttonX, top + 3.0F, 68.0F, 16.0F, hover ? ROW_ACTIVE : ROW_HOVER);
        this.drawTextCentered("SEARCH", buttonX + 34.0F, top + 7.0F, SMALL_SCALE, TEXT_PRIMARY);
    }

    private void drawSidebar(float mouseX, float mouseY, float left, float top, float right, float bottom) {
        this.drawText("MY PLAYLISTS", left + 12.0F, top + 1.0F, SMALL_SCALE, TEXT_MUTED);
        String count = this.playlists.size() + " playlists";
        this.drawText(count, right - 12.0F - this.textWidth(count, SMALL_SCALE), top + 1.0F, SMALL_SCALE, TEXT_MUTED);

        float listTop = top + 16.0F;
        float listHeight = bottom - listTop;
        int capacity = Math.max(1, (int)(listHeight / ROW_HEIGHT));
        int maxScroll = Math.max(0, this.playlists.size() - capacity);
        this.playlistScroll = Math.min(this.playlistScroll, maxScroll);
        this.playlistHover = -1;

        RenderUtils.drawRectSized(right - 1.0F, listTop, 1.0F, listHeight, DIVIDER);

        for (int i = 0; i < capacity; i++) {
            int index = this.playlistScroll + i;

            if (index >= this.playlists.size()) {
                break;
            }

            NeteaseApi.Playlist playlist = this.playlists.get(index);
            float rowY = listTop + i * ROW_HEIGHT;
            boolean hover = this.inside(mouseX, mouseY, left + 8.0F, rowY, right - left - 16.0F, ROW_HEIGHT - 2.0F);

            if (hover) {
                this.playlistHover = index;
                RenderUtils.drawRectSized(left + 8.0F, rowY, right - left - 16.0F, ROW_HEIGHT - 2.0F, ROW_HOVER);
            }

            this.drawCover(playlist.getCoverUrl(), left + 12.0F, rowY + 4.0F, 12.0F);
            this.drawDynamic(this.trimDynamic(playlist.getName(), right - left - 56.0F), left + 28.0F, rowY + 6.0F, TEXT_SECONDARY);

            String tracks = String.valueOf(playlist.getTrackCount());
            this.drawText(tracks, right - 12.0F - this.textWidth(tracks, SMALL_SCALE), rowY + 6.0F, SMALL_SCALE, TEXT_MUTED);
        }

        if (this.playlists.isEmpty()) {
            String hint = this.accountUid > 0L ? "No playlists" : "Login to load playlists";
            this.drawTextCentered(hint, (left + right) / 2.0F, listTop + 6.0F, SMALL_SCALE, TEXT_MUTED);
        }
    }

    private void drawTracks(float mouseX, float mouseY, float left, float top, float right, float bottom) {
        this.drawText("TRACKS", left + 4.0F, top + 1.0F, SMALL_SCALE, TEXT_MUTED);
        String hint = "click to play";
        this.drawText(hint, right - this.textWidth(hint, SMALL_SCALE), top + 1.0F, SMALL_SCALE, TEXT_MUTED);

        float listTop = top + 16.0F;
        float listHeight = bottom - listTop;
        int capacity = Math.max(1, (int)(listHeight / ROW_HEIGHT));
        int maxScroll = Math.max(0, this.tracks.size() - capacity);
        this.trackScroll = Math.min(this.trackScroll, maxScroll);
        this.trackHover = -1;

        NeteaseApi.Song playing = NeteasePlayer.current();

        for (int i = 0; i < capacity; i++) {
            int index = this.trackScroll + i;

            if (index >= this.tracks.size()) {
                break;
            }

            NeteaseApi.Song song = this.tracks.get(index);
            float rowY = listTop + i * ROW_HEIGHT;
            boolean hover = this.inside(mouseX, mouseY, left, rowY, right - left, ROW_HEIGHT - 2.0F);
            boolean active = playing != null && playing.getId() == song.getId();

            if (hover) {
                this.trackHover = index;
            }

            if (active) {
                RenderUtils.drawRectSized(left, rowY, right - left, ROW_HEIGHT - 2.0F, ROW_ACTIVE);
            } else if (hover) {
                RenderUtils.drawRectSized(left, rowY, right - left, ROW_HEIGHT - 2.0F, ROW_HOVER);
            }

            this.drawCover(song.getPicUrl(), left + 2.0F, rowY + 2.0F, 14.0F);

            float titleWidth = (right - left) * 0.46F;
            this.drawDynamic(this.trimDynamic(song.getName(), titleWidth), left + 20.0F, rowY + 6.0F, active ? ACCENT : TEXT_PRIMARY);

            float artistX = left + 20.0F + titleWidth + 8.0F;
            this.drawDynamic(this.trimDynamic(song.getArtist(), right - artistX - 44.0F), artistX, rowY + 6.0F, TEXT_MUTED);

            String duration = formatTime(song.getDurationMs());
            this.drawText(duration, right - 4.0F - this.textWidth(duration, SMALL_SCALE), rowY + 6.0F, SMALL_SCALE, TEXT_MUTED);
        }

        if (this.tracks.isEmpty()) {
            String empty = this.loading ? "Loading..." : "Search a song or pick a playlist";
            this.drawTextCentered(empty, (left + right) / 2.0F, listTop + 6.0F, SMALL_SCALE, TEXT_MUTED);
        }

        if (maxScroll > 0) {
            float trackHeight = listHeight * capacity / (float)this.tracks.size();
            float thumbY = listTop + (listHeight - trackHeight) * (this.trackScroll / (float)maxScroll);
            RenderUtils.drawRectSized(right + 2.0F, thumbY, 2.0F, trackHeight, 0x33FFFFFF);
        }
    }

    private void drawFooter(float mouseX, float mouseY, float left, float top, float right, float bottom) {
        RenderUtils.drawRectSized(left, top - 6.0F, right - left, 1.0F, DIVIDER);

        NeteaseApi.Song current = NeteasePlayer.current();
        String title = current == null ? "Nothing playing" : current.getName() + "  \u00b7  " + current.getArtist();

        if (current == null) {
            this.drawText(title, left + 12.0F, top + 2.0F, BODY_SCALE, TEXT_MUTED);
        } else {
            this.drawDynamic(this.trimDynamic(title, right - left - 24.0F), left + 12.0F, top + 2.0F, TEXT_PRIMARY);
        }

        float progressX = left + 12.0F;
        float progressWidth = right - left - 24.0F;
        float progressY = top + 20.0F;

        long position = NeteasePlayer.positionMs();
        long duration = NeteasePlayer.durationMs();
        float fraction = duration > 0L ? Math.min(1.0F, position / (float)duration) : 0.0F;

        RenderUtils.drawRectSized(progressX, progressY, progressWidth, 2.0F, 0x33FFFFFF);
        RenderUtils.drawRectSized(progressX, progressY, progressWidth * fraction, 2.0F, ACCENT);
        RenderUtils.drawFilledCircle(progressX + progressWidth * fraction, progressY + 1.0F, 2.5D);

        this.drawText(formatTime(position), progressX, progressY + 5.0F, SMALL_SCALE, TEXT_MUTED);
        String total = formatTime(duration);
        this.drawText(total, progressX + progressWidth - this.textWidth(total, SMALL_SCALE), progressY + 5.0F, SMALL_SCALE, TEXT_MUTED);

        float controlsY = top + 38.0F;
        float buttonWidth = 46.0F;
        float gap = 6.0F;
        float controlsX = left + 12.0F;

        this.drawButton("|<", controlsX, controlsY, buttonWidth, 14.0F, mouseX, mouseY, false);
        this.drawButton(NeteasePlayer.isPaused() ? ">" : "||", controlsX + buttonWidth + gap, controlsY, buttonWidth, 14.0F, mouseX, mouseY, true);
        this.drawButton(">|", controlsX + (buttonWidth + gap) * 2.0F, controlsY, buttonWidth, 14.0F, mouseX, mouseY, false);

        String loop = loopLabel(NeteasePlayer.loopMode());
        this.drawButton(loop, controlsX + (buttonWidth + gap) * 3.0F, controlsY, buttonWidth, 14.0F, mouseX, mouseY, false);

        float volumeX = right - 112.0F;
        float volumeY = controlsY + 6.0F;
        this.drawText("VOL", volumeX - 24.0F, volumeY - 2.0F, SMALL_SCALE, TEXT_MUTED);
        RenderUtils.drawRectSized(volumeX, volumeY, 100.0F, 2.0F, 0x33FFFFFF);
        RenderUtils.drawRectSized(volumeX, volumeY, 100.0F * NeteasePlayer.volume(), 2.0F, TEXT_SECONDARY);
        RenderUtils.drawFilledCircle(volumeX + 100.0F * NeteasePlayer.volume(), volumeY + 1.0F, 2.5D);
    }

    private void drawButton(String label, float x, float y, float width, float height, float mouseX, float mouseY, boolean primary) {
        boolean hover = this.inside(mouseX, mouseY, x, y, width, height);

        if (primary) {
            RenderUtils.drawRectSized(x, y, width, height, hover ? 0x66FF6B6B : 0x44FF6B6B);
        } else {
            RenderUtils.drawRectSized(x, y, width, height, hover ? ROW_ACTIVE : ROW_HOVER);
        }

        this.drawTextCentered(label, x + width / 2.0F, y + 2.0F, SMALL_SCALE, TEXT_PRIMARY);
    }

    /** 扫码登录浮层：白底二维码 + 当前状态。 */
    private void drawLoginOverlay(float mouseX, float mouseY, float left, float top, float right, float bottom) {
        RenderUtils.drawRectSized(left, top, right - left, bottom - top, 0xB4000000);

        float size = 160.0F;
        float x = Math.round((left + right - size) / 2.0F);
        float y = Math.round(top + 48.0F);
        RenderUtils.drawRectSized(x - 6.0F, y - 6.0F, size + 12.0F, size + 12.0F, 0xFFFFFFFF);

        if (this.qrTexture != null) {
            RenderUtils.drawTexture(this.qrTexture, x, y, size, size);
        } else {
            this.drawTextCentered("generating...", x + size / 2.0F, y + size / 2.0F, SMALL_SCALE, TEXT_MUTED);
        }

        this.drawTextCentered(loginStateText(), (left + right) / 2.0F, y + size + 12.0F, BODY_SCALE, TEXT_PRIMARY);
        this.drawTextCentered("scan with the NetEase Cloud Music app", (left + right) / 2.0F, y + size + 28.0F, SMALL_SCALE, TEXT_MUTED);

        float closeWidth = 60.0F;
        float closeX = Math.round((left + right - closeWidth) / 2.0F);
        float closeY = Math.min(y + size + 44.0F, bottom - 20.0F);
        boolean hover = this.inside(mouseX, mouseY, closeX, closeY, closeWidth, 12.0F);
        RenderUtils.drawRectSized(closeX, closeY, closeWidth, 12.0F, hover ? ROW_ACTIVE : ROW_HOVER);
        this.drawTextCentered("CLOSE", closeX + closeWidth / 2.0F, closeY + 1.0F, SMALL_SCALE, TEXT_PRIMARY);
    }

    private static String loginStateText() {
        switch (NeteaseQrLogin.state()) {
            case SUCCESS:
                return "login successful";
            case SCANNED:
                return "scanned, confirm on your phone";
            case EXPIRED:
                return "qr expired, close and reopen";
            case FAILED:
                return "could not create qr code";
            case WAITING:
            default:
                return "waiting for scan";
        }
    }

    /* ------------------------------------------------------------------ */
    /*  封面                                                               */
    /* ------------------------------------------------------------------ */

    private void drawCover(String url, float x, float y, float size) {
        ResourceLocation texture = url == null ? null : this.covers.get(url);

        if (texture == null) {
            RenderUtils.drawRectSized(x, y, size, size, ROW_HOVER);
            this.requestCover(url);
            return;
        }

        RenderUtils.drawTexture(texture, x, y, size, size);
    }

    private void requestCover(String url) {
        if (url == null || url.isEmpty() || this.covers.containsKey(url) || !this.coverPending.add(url)) {
            return;
        }

        IO.execute(() -> {
            try {
                byte[] bytes = NeteaseApi.artwork(url);

                if (bytes == null || bytes.length == 0) {
                    return;
                }

                final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));

                if (image == null) {
                    return;
                }

                this.mainThreadTasks.add(() -> {
                    try {
                        DynamicTexture texture = new DynamicTexture(image);
                        ResourceLocation location = this.mc
                            .getTextureManager()
                            .getDynamicTextureLocation("netease-cover-" + Integer.toHexString(url.hashCode()), texture);
                        this.covers.put(url, location);
                    } catch (Throwable ignored) {
                        // 贴图上传失败就继续显示占位块
                    } finally {
                        this.coverPending.remove(url);
                    }
                });
            } catch (Throwable throwable) {
                this.coverPending.remove(url);
            }
        });
    }

    private void pulseMainThreadTasks() {
        int budget = 2;
        Runnable task;

        while (budget-- > 0 && (task = this.mainThreadTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable ignored) {
                // 单张封面失败不影响界面
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  交互                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public void mouseClickedScaled(float mouseX, float mouseY, int button) {
        Minecraft minecraft = Minecraft.getMinecraft();
        float panelWidth = Math.min(640.0F, this.getScaledWidth() - 40.0F);
        float panelHeight = Math.min(420.0F, this.getScaledHeight() - 40.0F);
        float left = Math.round((this.getScaledWidth() - panelWidth) / 2.0F);
        float top = Math.round((this.getScaledHeight() - panelHeight) / 2.0F);
        float right = left + panelWidth;
        float bottom = top + panelHeight;

        if (this.loginVisible) {
            float size = 160.0F;
            float qrY = Math.round(top + 48.0F);
            float closeWidth = 60.0F;
            float closeX = Math.round((left + right - closeWidth) / 2.0F);
            float closeY = Math.min(qrY + size + 44.0F, bottom - 20.0F);

            if (this.inside(mouseX, mouseY, closeX, closeY, closeWidth, 12.0F)) {
                this.playClick();
                this.loginVisible = false;
                this.qrTexture = null;
                this.qrContent = null;
            }

            return;
        }

        if (this.searchField.containsPoint(mouseX, mouseY)) {
            this.searchField.setFocused(true);
            return;
        }

        this.searchField.setFocused(false);

        float actionX = right - 58.0F;

        if (this.inside(mouseX, mouseY, actionX, top + 7.0F, 48.0F, 16.0F)) {
            this.playClick();
            this.toggleLogin();
            return;
        }

        if (this.inside(mouseX, mouseY, actionX - 52.0F, top + 7.0F, 46.0F, 16.0F)) {
            this.playClick();
            this.closeScreen();
            return;
        }

        if (this.inside(mouseX, mouseY, left + panelWidth - 96.0F, top + 37.0F, 68.0F, 16.0F)) {
            this.playClick();
            this.doSearch();
            return;
        }

        if (this.playlistHover >= 0 && this.playlistHover < this.playlists.size()) {
            this.playClick();
            this.openPlaylist(this.playlists.get(this.playlistHover));
            return;
        }

        if (this.trackHover >= 0 && this.trackHover < this.tracks.size()) {
            this.playClick();
            NeteaseApi.Song song = this.tracks.get(this.trackHover);
            NeteasePlayer.play(song);

            CheatBreaker cheatBreaker = CheatBreaker.getInstance();

            if (cheatBreaker != null && cheatBreaker.getModManager() != null && cheatBreaker.getModManager().musicMod != null) {
                cheatBreaker.getModManager().musicMod.onTrackSelected(song);
            }

            return;
        }

        float progressY = bottom - 58.0F + 20.0F;
        float progressX = left + 12.0F;
        float progressWidth = right - left - 24.0F;

        if (this.inside(mouseX, mouseY, progressX, progressY - 4.0F, progressWidth, 10.0F)) {
            this.draggingProgress = true;
            this.applySeek(mouseX, progressX, progressWidth);
            return;
        }

        float volumeX = right - 112.0F;
        float volumeY = progressY + 24.0F;

        if (this.inside(mouseX, mouseY, volumeX, volumeY - 4.0F, 100.0F, 10.0F)) {
            this.draggingVolume = true;
            this.applyVolume(mouseX, volumeX, 100.0F);
            return;
        }

        float controlsY = bottom - 58.0F + 38.0F;
        float buttonWidth = 46.0F;
        float gap = 6.0F;
        float controlsX = left + 12.0F;

        if (this.inside(mouseX, mouseY, controlsX, controlsY, buttonWidth, 14.0F)) {
            this.playClick();
            NeteasePlayer.playPrevious();
        } else if (this.inside(mouseX, mouseY, controlsX + buttonWidth + gap, controlsY, buttonWidth, 14.0F)) {
            this.playClick();
            NeteasePlayer.togglePlayPause();
        } else if (this.inside(mouseX, mouseY, controlsX + (buttonWidth + gap) * 2.0F, controlsY, buttonWidth, 14.0F)) {
            this.playClick();
            NeteasePlayer.playNext();
        } else if (this.inside(mouseX, mouseY, controlsX + (buttonWidth + gap) * 3.0F, controlsY, buttonWidth, 14.0F)) {
            this.playClick();
            this.applyLoopMode(NeteasePlayer.cycleLoopMode());
        }
    }

    @Override
    public void mouseReleasedScaled(float mouseX, float mouseY, int button) {
        this.draggingProgress = false;
        this.draggingVolume = false;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();

        int wheel = Mouse.getEventDWheel();

        if (wheel == 0) {
            return;
        }

        float mouseX = Mouse.getEventX() * this.getScaledWidth() / (float)this.mc.displayWidth;
        float panelWidth = Math.min(640.0F, this.getScaledWidth() - 40.0F);
        float panelHeight = Math.min(420.0F, this.getScaledHeight() - 40.0F);
        float left = Math.round((this.getScaledWidth() - panelWidth) / 2.0F);
        float top = Math.round((this.getScaledHeight() - panelHeight) / 2.0F);

        if (mouseX < left + SIDEBAR_WIDTH) {
            this.playlistScroll = Math.max(0, this.playlistScroll - wheel / 120);
        } else {
            this.trackScroll = Math.max(0, this.trackScroll - wheel / 120);
        }
    }

    @Override
    public void updateScreen() throws java.io.IOException {
        super.updateScreen();
        this.pulseMainThreadTasks();
        this.searchField.tick();

        if (this.draggingProgress || this.draggingVolume) {
            float mouseX = Mouse.getX() * this.getScaledWidth() / (float)this.mc.displayWidth;
            float panelWidth = Math.min(640.0F, this.getScaledWidth() - 40.0F);
            float left = Math.round((this.getScaledWidth() - panelWidth) / 2.0F);
            float right = left + panelWidth;

            if (this.draggingProgress) {
                this.applySeek(mouseX, left + 12.0F, right - left - 24.0F);
            } else {
                this.applyVolume(mouseX, right - 112.0F, 100.0F);
            }
        }

        if (this.loginVisible) {
            long now = System.currentTimeMillis();

            if (now - this.lastQrPoll >= QR_POLL_MILLIS) {
                this.lastQrPoll = now;
                IO.execute(NeteaseQrLogin::poll);

                if (NeteaseQrLogin.state() == NeteaseQrLogin.State.SUCCESS && this.accountUid <= 0L) {
                    this.ensureAccount();
                }
            }

            this.ensureQrTexture();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (this.searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN) {
                this.doSearch();
                return;
            }

            this.searchField.keyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.closeScreen();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  杂项                                                               */
    /* ------------------------------------------------------------------ */

    /** 世界里关闭回到游戏，主菜单里关闭回菜单。 */
    private void closeScreen() {
        try {
            if (this.mc.theWorld != null) {
                this.mc.displayGuiScreen(null);
                return;
            }

            this.mc.displayGuiScreen(new MainMenuScreen());
        } catch (java.io.IOException exception) {
            CheatBreaker.getInstance().getLogger().error("Could not close the music screen", exception);
        }
    }

    private void toggleLogin() {
        if (this.accountUid > 0L || this.loginVisible) {
            this.loginVisible = false;
            this.qrTexture = null;
            this.qrContent = null;
            return;
        }

        this.loginVisible = true;
        IO.execute(NeteaseQrLogin::start);
    }

    private void ensureQrTexture() {
        String content = NeteaseQrLogin.qrContent();

        if (content == null || content.isEmpty()) {
            return;
        }

        if (content.equals(this.qrContent) && this.qrTexture != null) {
            return;
        }

        this.qrContent = content;

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 256, 256);
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }

            DynamicTexture texture = new DynamicTexture(image);
            this.qrTexture = this.mc.getTextureManager().getDynamicTextureLocation("netease-qr", texture);
        } catch (Throwable throwable) {
            this.qrTexture = null;
        }
    }

    private void applySeek(float mouseX, float x, float width) {
        if (width <= 0.0F) {
            return;
        }

        NeteasePlayer.seekFraction(Math.max(0.0F, Math.min(1.0F, (mouseX - x) / width)));
    }

    private void applyVolume(float mouseX, float x, float width) {
        if (width <= 0.0F) {
            return;
        }

        float volume = Math.max(0.0F, Math.min(1.0F, (mouseX - x) / width));
        NeteasePlayer.setVolume(volume);

        // 写回 mod 设置，随 profile 保存
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();

        if (cheatBreaker != null && cheatBreaker.getModManager() != null && cheatBreaker.getModManager().musicMod != null) {
            cheatBreaker.getModManager().musicMod.onVolumeChangedFromUi(volume);
        }
    }

    private void applyLoopMode(NeteasePlayer.LoopMode mode) {
        NeteasePlayer.setLoopMode(mode);
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();

        if (cheatBreaker != null && cheatBreaker.getModManager() != null && cheatBreaker.getModManager().musicMod != null) {
            cheatBreaker.getModManager().musicMod.onLoopModeChangedFromUi(mode);
        }
    }

    private void playClick() {
        Minecraft.getMinecraft()
            .getSoundHandler()
            .playSound(PositionedSoundRecord.createPositionedSoundRecord(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private boolean inside(float mouseX, float mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String loopLabel(NeteasePlayer.LoopMode mode) {
        switch (mode) {
            case ONE:
                return "ONE";
            case OFF:
                return "OFF";
            case LIST:
            default:
                return "LIST";
        }
    }

    private static String formatTime(long millis) {
        if (millis <= 0L) {
            return "0:00";
        }

        long total = millis / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10L ? "0" : "") + seconds;
    }
}

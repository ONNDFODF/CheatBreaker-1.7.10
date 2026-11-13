package com.cheatbreaker.client.music;

import com.cheatbreaker.client.CheatBreaker;
import com.cheatbreaker.client.util.ClientVersion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * 内置网易云 API。
 *
 * <p>搜索 / 歌单 / 封面走公开的 {@code /api/*}，播放直链走加密的 {@code /weapi/*}
 * （见 {@link NeteaseWeapi}）—— 明文接口只给 128k，会员曲目还会直接返回空地址。
 *
 * <p>登录态是一整行 cookie，存在客户端配置目录的 {@code netease-cookie.txt} 里，
 * 由 {@link NeteaseQrLogin} 扫码后写入。
 */
public final class NeteaseApi {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/";

    private static String cookie;
    private static boolean cookieLoaded;

    private NeteaseApi() {
    }

    /** 一首歌的元数据。 */
    public static final class Song {
        private final long id;
        private final String name;
        private final String artist;
        private final String album;
        private final String picUrl;
        private final long durationMs;

        public Song(long id, String name, String artist, String album, String picUrl, long durationMs) {
            this.id = id;
            this.name = name;
            this.artist = artist;
            this.album = album;
            this.picUrl = picUrl;
            this.durationMs = durationMs;
        }

        public long getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public String getArtist() {
            return this.artist;
        }

        public String getAlbum() {
            return this.album;
        }

        public String getPicUrl() {
            return this.picUrl;
        }

        public long getDurationMs() {
            return this.durationMs;
        }
    }

    /** 歌单概要（含封面地址，用于列表左侧的小图）。 */
    public static final class Playlist {
        private final long id;
        private final String name;
        private final int trackCount;
        private final String coverUrl;

        public Playlist(long id, String name, int trackCount, String coverUrl) {
            this.id = id;
            this.name = name;
            this.trackCount = trackCount;
            this.coverUrl = coverUrl;
        }

        public long getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public int getTrackCount() {
            return this.trackCount;
        }

        /**
         * 歌单封面。网易云默认就用「第一首歌的封面」生成这个地址，
         * 所以列表里的小图直接拿它，不需要为每个歌单再拉一遍曲目。
         */
        public String getCoverUrl() {
            return this.coverUrl;
        }
    }

    /** 搜索歌曲，失败返回空列表。 */
    public static List<Song> search(String keyword, int limit) {
        List<Song> result = new ArrayList<Song>();

        if (keyword == null || keyword.trim().isEmpty()) {
            return result;
        }

        String url = "https://music.163.com/api/search/get/web?s="
            + encode(keyword)
            + "&type=1&limit="
            + Math.max(1, Math.min(50, limit))
            + "&offset=0";
        String json = get(url);

        if (json == null) {
            return result;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("result") || !root.get("result").isJsonObject()) {
                return result;
            }

            JsonObject body = root.getAsJsonObject("result");
            if (!body.has("songs") || !body.get("songs").isJsonArray()) {
                return result;
            }

            JsonArray songs = body.getAsJsonArray("songs");

            for (JsonElement element : songs) {
                if (!element.isJsonObject()) {
                    continue;
                }

                Song song = parseSong(element.getAsJsonObject());
                if (song != null) {
                    result.add(song);
                }
            }
        } catch (Throwable ignored) {
            // JSON 结构变了就当没搜到
        }

        return result;
    }

    /**
     * 解析一首歌。搜索接口用 {@code artists/album/duration}，歌单接口用 {@code ar/al/dt}，
     * 两套字段都要兼容，否则歌单里会变成「只有歌名 + --:--」。
     */
    private static Song parseSong(JsonObject json) {
        try {
            long id = json.get("id").getAsLong();
            String name = json.has("name") ? json.get("name").getAsString() : "";
            String artist = artistsOf(json);
            String album = "";
            String picUrl = null;
            JsonObject albumJson = firstObject(json, "album", "al");

            if (albumJson != null) {
                if (albumJson.has("name")) {
                    album = albumJson.get("name").getAsString();
                }

                if (albumJson.has("picUrl") && !albumJson.get("picUrl").isJsonNull()) {
                    String candidate = albumJson.get("picUrl").getAsString();
                    if (candidate.startsWith("http")) {
                        picUrl = candidate;
                    }
                }
            }

            long duration = json.has("duration")
                ? json.get("duration").getAsLong()
                : (json.has("dt") ? json.get("dt").getAsLong() : 0L);

            return new Song(id, name, artist, album, picUrl, duration);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject firstObject(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && json.get(key).isJsonObject()) {
                return json.getAsJsonObject(key);
            }
        }

        return null;
    }

    private static String artistsOf(JsonObject json) {
        JsonArray array = null;

        if (json.has("artists") && json.get("artists").isJsonArray()) {
            array = json.getAsJsonArray("artists");
        } else if (json.has("ar") && json.get("ar").isJsonArray()) {
            array = json.getAsJsonArray("ar");
        }

        if (array == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("name")) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(" / ");
            }

            builder.append(entry.get("name").getAsString());
        }

        return builder.toString();
    }

    /** 当前登录账号的 uid，未登录返回 -1。 */
    public static long accountUid() {
        if (cookie() == null) {
            return -1L;
        }

        String json = get("https://music.163.com/api/nuser/account/get");
        if (json == null) {
            return -1L;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("account") && root.get("account").isJsonObject()) {
                JsonObject account = root.getAsJsonObject("account");
                if (account.has("id")) {
                    return account.get("id").getAsLong();
                }
            }
        } catch (Throwable ignored) {
            // 结构变了就当没登录
        }

        return -1L;
    }

    /** 某个用户的歌单（含「我喜欢的音乐」）。 */
    public static List<Playlist> playlists(long uid) {
        List<Playlist> result = new ArrayList<Playlist>();

        if (uid <= 0L) {
            return result;
        }

        String json = get("https://music.163.com/api/user/playlist/?uid=" + uid + "&limit=100&offset=0");
        if (json == null) {
            return result;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("playlist") || !root.get("playlist").isJsonArray()) {
                return result;
            }

            for (JsonElement element : root.getAsJsonArray("playlist")) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("id")) {
                    continue;
                }

                long id = entry.get("id").getAsLong();
                String name = entry.has("name") ? entry.get("name").getAsString() : "未命名";
                int count = entry.has("trackCount") ? entry.get("trackCount").getAsInt() : 0;
                String cover = null;

                if (entry.has("coverImgUrl") && !entry.get("coverImgUrl").isJsonNull()) {
                    String candidate = entry.get("coverImgUrl").getAsString();

                    if (candidate.startsWith("http")) {
                        cover = candidate;
                    }
                }

                result.add(new Playlist(id, name, count, cover));
            }
        } catch (Throwable ignored) {
            // 同上
        }

        return result;
    }

    /** 歌单里的曲目。 */
    public static List<Song> playlistTracks(long playlistId) {
        List<Song> result = new ArrayList<Song>();

        if (playlistId <= 0L) {
            return result;
        }

        String json = get("https://music.163.com/api/v6/playlist/detail?id=" + playlistId + "&n=1000&s=0");
        if (json == null) {
            return result;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("playlist") || !root.get("playlist").isJsonObject()) {
                return result;
            }

            JsonObject playlist = root.getAsJsonObject("playlist");
            if (!playlist.has("tracks") || !playlist.get("tracks").isJsonArray()) {
                return result;
            }

            for (JsonElement element : playlist.getAsJsonArray("tracks")) {
                if (!element.isJsonObject()) {
                    continue;
                }

                Song song = parseSong(element.getAsJsonObject());
                if (song != null) {
                    result.add(song);
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }

        return result;
    }

    /** 取歌词（LRC），拿不到返回空歌词。 */
    public static NeteaseLyrics lyric(long songId) {
        String json = get("https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1");

        if (json == null) {
            return NeteaseLyrics.EMPTY;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("lrc") || !root.get("lrc").isJsonObject()) {
                return NeteaseLyrics.EMPTY;
            }

            JsonObject lrc = root.getAsJsonObject("lrc");

            if (!lrc.has("lyric")) {
                return NeteaseLyrics.EMPTY;
            }

            return NeteaseLyrics.parse(lrc.get("lyric").getAsString(), "netease");
        } catch (Throwable ignored) {
            return NeteaseLyrics.EMPTY;
        }
    }

    /** 下载封面字节（200x200），失败返回 null。 */
    public static byte[] artwork(String picUrl) {
        if (picUrl == null || !picUrl.startsWith("http")) {
            return null;
        }

        String url = picUrl.replaceFirst("^http://", "https://");
        int query = url.indexOf("?param=");
        url = query > 0 ? url.substring(0, query) + "?param=200y200" : url;
        return getBytes(url);
    }

    /**
     * 取播放直链。
     *
     * <p>优先 {@code /weapi/song/enhance/player/url/v1}（能拿到高码率 / 会员曲），
     * 失败再退到 {@code /api} 两个端点，最后用外链端点兜底 —— 后者 302 到不带签名的
     * CDN 地址，不带 cookie 时反而更稳。
     */
    public static String playUrl(long songId, int bitrate) {
        String level = bitrate >= 320000 ? "exhigh" : "standard";
        String weapiUrl = NeteaseWeapi.songUrl(songId, level, cookie());

        if (weapiUrl != null) {
            return weapiUrl;
        }

        String url = playUrlFrom("https://music.163.com/api/song/enhance/player/url?ids=[", songId, bitrate);
        if (url != null) {
            return url;
        }

        url = playUrlFrom("https://music.163.com/api/song/enhance/player/url/v1?level=exhigh&ids=[", songId, bitrate);
        if (url != null) {
            return url;
        }

        return "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
    }

    private static String playUrlFrom(String prefix, long songId, int bitrate) {
        String json = get(prefix + songId + "]&br=" + bitrate);
        if (json == null) {
            return null;
        }

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonArray()) {
                return null;
            }

            JsonArray data = root.getAsJsonArray("data");
            if (data.size() == 0 || !data.get(0).isJsonObject()) {
                return null;
            }

            JsonObject first = data.get(0).getAsJsonObject();
            if (!first.has("url") || first.get("url").isJsonNull()) {
                return null;
            }

            return first.get("url").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 丢掉缓存的 cookie：登录写盘后必须调一次，否则一直拿到登录前的 null。 */
    public static void invalidateCookie() {
        cookieLoaded = false;
        cookie = null;
    }

    static String cookieOrNull() {
        return cookie();
    }

    static String cookie() {
        if (cookieLoaded) {
            return cookie;
        }

        cookieLoaded = true;

        try {
            File file = cookieFile();

            if (!file.isFile()) {
                return null;
            }

            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            cookie = text.isEmpty() ? null : text;
        } catch (Throwable ignored) {
            // 读不到就当没登录
        }

        return cookie;
    }

    /** 登录态文件：和客户端其它配置放在同一个目录里。 */
    static File cookieFile() {
        return new File(configDirectory(), "netease-cookie.txt");
    }

    static File configDirectory() {
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        String branch = cheatBreaker == null || cheatBreaker.getBranch() == null ? "master" : cheatBreaker.getBranch();
        return new File(
            Minecraft.getMinecraft().mcDataDir,
            "config" + File.separator + "cheatbreaker-client-" + ClientVersion.version + "-" + branch
        );
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Referer", REFERER);

        String stored = cookie();
        if (stored != null) {
            connection.setRequestProperty("Cookie", stored);
        }

        return connection;
    }

    private static String get(String url) {
        HttpURLConnection connection = null;

        try {
            connection = open(url);

            if (connection.getResponseCode() != 200) {
                return null;
            }

            try (InputStream stream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }

                return builder.toString();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] getBytes(String url) {
        HttpURLConnection connection = null;

        try {
            connection = open(url);
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/*;q=0.8");

            if (connection.getResponseCode() != 200) {
                return null;
            }

            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;

                while ((read = stream.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }

                return out.toByteArray();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
    }
}

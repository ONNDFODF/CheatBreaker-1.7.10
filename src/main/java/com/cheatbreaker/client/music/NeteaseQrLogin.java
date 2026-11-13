package com.cheatbreaker.client.music;

import com.cheatbreaker.client.util.Logging;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 网易云扫码登录。
 *
 * <p>流程（都走 {@code /weapi/}，明文接口已经失效）：
 * <ol>
 *   <li>{@code /weapi/login/qrcode/unikey} 拿 unikey；</li>
 *   <li>二维码内容 = {@code https://music.163.com/login?codekey=<unikey>}，交给界面渲染；</li>
 *   <li>轮询 {@code /weapi/login/qrcode/client/login}，800/801/802/803 分别对应
 *       过期 / 待扫 / 已扫待确认 / 成功。</li>
 * </ol>
 *
 * <p>成功后把响应里的 cookie 写进 {@code netease-cookie.txt}，{@link NeteaseApi} 自动带上它。
 */
public final class NeteaseQrLogin {
    public enum State {
        IDLE,
        WAITING,
        SCANNED,
        EXPIRED,
        SUCCESS,
        FAILED
    }

    private static String unikey;
    private static volatile State state = State.IDLE;
    private static String qrContent;
    private static int lastLogged = -1;

    private NeteaseQrLogin() {
    }

    public static State state() {
        return state;
    }

    public static String qrContent() {
        return qrContent;
    }

    /** 生成新 unikey，旧二维码立即作废。 */
    public static void start() {
        state = State.WAITING;
        qrContent = null;
        unikey = null;
        lastLogged = -1;

        String body = NeteaseWeapi.post(
            "https://music.163.com/weapi/login/qrcode/unikey",
            "{\"type\":1,\"csrf_token\":\"\"}",
            null
        );

        if (body == null) {
            state = State.FAILED;
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            if (!root.has("unikey")) {
                state = State.FAILED;
                return;
            }

            unikey = root.get("unikey").getAsString();
            qrContent = "https://music.163.com/login?codekey=" + unikey;
        } catch (Throwable throwable) {
            state = State.FAILED;
            Logging.warnTagged("Music", "Could not parse QR unikey: %s", throwable);
        }
    }

    /** 轮询一次，界面每 1.5 秒调一下。 */
    public static void poll() {
        if (unikey == null || state == State.SUCCESS || state == State.FAILED || state == State.EXPIRED) {
            return;
        }

        String body = NeteaseWeapi.post(
            "https://music.163.com/weapi/login/qrcode/client/login",
            "{\"key\":\"" + unikey + "\",\"type\":1,\"csrf_token\":\"\"}",
            NeteaseQrLogin::saveCookie
        );

        if (body == null) {
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int status = root.has("code") ? root.get("code").getAsInt() : -1;
            logState(status);

            switch (status) {
                case 800:
                    state = State.EXPIRED;
                    break;
                case 801:
                    state = State.WAITING;
                    break;
                case 802:
                    state = State.SCANNED;
                    break;
                case 803:
                    state = State.SUCCESS;
                    if (root.has("cookie") && !root.get("cookie").isJsonNull()) {
                        saveCookie(root.get("cookie").getAsString());
                    }
                    break;
                case 8821:
                    state = State.EXPIRED;
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
            // 正文不是 JSON，下次再试
        }
    }

    private static void logState(int status) {
        if (status == lastLogged) {
            return;
        }

        lastLogged = status;

        switch (status) {
            case 800:
                Logging.infoTagged("Music", "QR code expired");
                break;
            case 801:
                Logging.infoTagged("Music", "Waiting for scan");
                break;
            case 802:
                Logging.infoTagged("Music", "Scanned, waiting for confirmation");
                break;
            case 803:
                Logging.infoTagged("Music", "Login successful");
                break;
            default:
                Logging.debugTagged("Music", "QR status %d", status);
        }
    }

    private static void saveCookie(String raw) {
        String trimmed = raw == null ? "" : raw.trim();

        if (trimmed.isEmpty()) {
            return;
        }

        try {
            File file = NeteaseApi.cookieFile();
            File parent = file.getParentFile();

            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Logging.warnTagged("Music", "Could not create %s", parent);
                return;
            }

            Files.write(file.toPath(), trimmed.getBytes(StandardCharsets.UTF_8));
            NeteaseApi.invalidateCookie();
            Logging.infoTagged("Music", "Saved NetEase cookie (%d chars)", trimmed.length());
        } catch (Throwable throwable) {
            Logging.warnTagged("Music", "Could not save NetEase cookie: %s", throwable);
        }
    }
}

package com.cheatbreaker.client.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 {@code /weapi/*} 加密层。
 *
 * <p>明文 {@code /api/...} 现在只给 128k，会员曲目直接返回 {@code {"url":null}}，
 * 所以登录和取播放直链都必须走 {@code /weapi/}：请求体 AES-CBC 加密后再套一层 RSA 加密的随机密钥。
 * 三个常量（nonce / iv / RSA 公钥）是官方客户端里写死的，公开多年。
 *
 * <pre>
 *   secretKey = 16 位随机字符
 *   params    = BASE64( AES( AES(json, nonce, iv), secretKey, iv ) )
 *   encSecKey = RSA( reverse(secretKey) )  → 左补零到 256 位十六进制
 * </pre>
 */
public final class NeteaseWeapi {
    private static final String NONCE = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String PUB_KEY = "010001";
    private static final String MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5"
        + "aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c"
        + "3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b"
        + "3ece0462db0a22b8e7";

    private static final char[] POOL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final Random RANDOM = new Random();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private NeteaseWeapi() {
    }

    /** Set-Cookie 回调，二维码登录靠它拿到登录态。 */
    public interface CookieSink {
        void accept(String cookies);
    }

    /** 发一个 weapi POST，返回响应正文（失败返回 null），同时把 Set-Cookie 交给 sink。 */
    public static String post(String url, String json, CookieSink sink) {
        HttpURLConnection connection = null;

        try {
            String secretKey = randomKey();
            String params = aes(aes(json, NONCE), secretKey);
            String encSecKey = rsa(secretKey);
            String body = "params=" + encode(params) + "&encSecKey=" + encode(encSecKey);

            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setRequestProperty("Origin", "https://music.163.com");
            connection.setRequestProperty("Accept", "*/*");

            String cookie = NeteaseApi.cookieOrNull();
            connection.setRequestProperty("Cookie", "os=pc; appver=2.10.13; " + (cookie == null ? "" : cookie));

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();

            if (sink != null) {
                StringBuilder cookies = new StringBuilder();

                for (java.util.Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie")) {
                        continue;
                    }

                    for (String value : header.getValue()) {
                        int end = value.indexOf(';');
                        cookies.append(end > 0 ? value.substring(0, end) : value).append("; ");
                    }
                }

                if (cookies.length() > 0) {
                    sink.accept(cookies.toString());
                }
            }

            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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

    /** 发一个 weapi POST 并把结果解析成 JsonObject。 */
    static JsonObject postJson(String path, String json, String cookie) {
        String body = post("https://music.163.com" + path, json, null);
        if (body == null) {
            return null;
        }

        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取一个可播放直链。
     *
     * <p>{@code encodeType} 必须是 {@code mp3}：客户端用的解码器是纯 Java 的 JLayer，
     * 只认 MPEG 音频。早期版本写 {@code flac} 想拿最高音质，结果会员账号拿到的是 flac 直链，
     * 下载成功但一帧都解不出来 —— 表现就是「点了播放没有任何声音」。
     *
     * @param level standard / higher / exhigh；会员账号用 exhigh 能拿到 320k 的 mp3
     */
    public static String songUrl(long songId, String level, String cookie) {
        JsonObject response = postJson(
            "/weapi/song/enhance/player/url/v1?csrf_token=",
            "{\"ids\":\"[" + songId + "]\",\"level\":\"" + level + "\",\"encodeType\":\"mp3\"}",
            cookie
        );

        if (response == null) {
            return null;
        }

        try {
            JsonObject first = response.getAsJsonArray("data").get(0).getAsJsonObject();
            if (first.get("url").isJsonNull()) {
                return null;
            }

            String url = first.get("url").getAsString();
            return url == null || url.isEmpty() ? null : url;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String randomKey() {
        StringBuilder key = new StringBuilder(16);

        for (int i = 0; i < 16; i++) {
            key.append(POOL[RANDOM.nextInt(POOL.length)]);
        }

        return key.toString();
    }

    private static String aes(String text, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8))
            );
            return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String rsa(String secretKey) {
        try {
            String reversed = new StringBuilder(secretKey).reverse().toString();
            BigInteger message = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
            BigInteger exponent = new BigInteger(PUB_KEY, 16);
            BigInteger modulus = new BigInteger(MODULUS, 16);
            String hex = message.modPow(exponent, modulus).toString(16);
            StringBuilder builder = new StringBuilder(hex);

            while (builder.length() < 256) {
                builder.insert(0, '0');
            }

            return builder.toString();
        } catch (Throwable ignored) {
            return "";
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

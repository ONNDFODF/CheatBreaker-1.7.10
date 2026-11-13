package com.cheatbreaker.client.radio;

import com.cheatbreaker.client.audio.ResourceJavaLayerHook;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import javazoom.jl.decoder.JavaLayerUtils;
import javazoom.jl.player.Player;

public class RadioApi {
    public static final String API_URL = "https://dash-api.com/api/v3/allData.php";
    public static RadioVolume volume = new RadioVolume();
    public static boolean playbackStarting;
    public static Player player;

    public static void play(String str) {
        if (!playbackStarting) {
            playbackStarting = true;
            if (player != null) {
                player.close();
                player = null;
            } else {
                new Thread(() -> {
                    try {
                        InputStream fetch2 = (InputStream)fetch(str, true);
                        if (fetch2 == null) {
                            throw new Exception("Failed to fetch stream data.");
                        }

                        volume = new RadioVolume();
                        player = new Player(fetch2, volume);
                        player.play();
                    } catch (Exception e) {
                        e.printStackTrace();
                        playbackStarting = false;
                    }
                }).start();
            }
        }
    }

    public static boolean isPlaying() {
        return player != null;
    }

    public static RadioVolume getVolume() {
        return volume;
    }

    public static void stop() {
        if (player != null) {
            player.close();
            player = null;
        }

        playbackStarting = false;
    }

    public static List<Station> loadStations() {
        // 电台功能已移除：不再请求 dash-api.com，返回空列表。
        return new ArrayList();
    }

    public static Object fetch(String str, boolean flag) {
        try {
            URLConnection openConnection = new URL(str).openConnection();
            openConnection.setConnectTimeout(5000);
            openConnection.setReadTimeout(5000);
            if (flag) {
                return openConnection.getInputStream();
            }

            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(openConnection.getInputStream()))) {
                StringBuilder sb = new StringBuilder();

                String str2;
                while ((str2 = bufferedReader.readLine()) != null) {
                    sb.append(str2);
                }

                return sb.toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}

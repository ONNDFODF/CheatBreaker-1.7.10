package com.cheatbreaker.client.music;

import com.cheatbreaker.client.util.Logging;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * 内置网易云播放器。
 *
 * <p>解码用纯 Java 的 JLayer（客户端已经带着），所以不需要任何 natives：
 * 播放地址下载成 mp3 缓存到配置目录，再用 {@link Bitstream}+{@link Decoder} 逐帧解成
 * 16bit PCM 写进 {@link SourceDataLine}。
 *
 * <p>线程模型：所有解码与输出都在同一条播放线程里串行执行，对外只暴露 volatile 状态，
 * seek / 音量 / 暂停都是改标记位，不会出现两首歌同时占着输出线的情况。
 */
public final class NeteasePlayer {
    public enum LoopMode {
        LIST,
        ONE,
        OFF
    }

    private static final int BITRATE = 128000;
    private static final int BUFFER_BYTES = 262144;

    private static final Object LOCK = new Object();
    private static final List<NeteaseApi.Song> queue = new ArrayList<NeteaseApi.Song>();

    /** 每次 play() 递增：旧线程醒来后发现代号变了就不能再接管播放。 */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();

    private static volatile NeteaseApi.Song current;
    private static volatile boolean playing;
    private static volatile boolean paused;
    private static volatile boolean stopRequested;
    private static volatile long positionMs;
    private static volatile long durationMs;
    private static volatile long seekTarget = -1L;
    private static volatile float volume = 0.8F;
    private static volatile LoopMode loopMode = LoopMode.LIST;
    private static volatile String status = "";

    private static Thread worker;
    private static SourceDataLine line;
    /** 本次尝试是否在「取直链 / 下载 / 校验」阶段就失败了；失败时不要自动跳下一首。 */
    private static volatile boolean lastAttemptFailed;

    private NeteasePlayer() {
    }

    /* ------------------------------------------------------------------ */
    /*  对外状态                                                           */
    /* ------------------------------------------------------------------ */

    public static NeteaseApi.Song current() {
        return current;
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isReady() {
        return playing && !paused;
    }

    public static long positionMs() {
        return positionMs;
    }

    public static long durationMs() {
        return durationMs;
    }

    public static float volume() {
        return volume;
    }

    public static void setVolume(float value) {
        volume = Math.max(0.0F, Math.min(1.0F, value));
    }

    public static LoopMode loopMode() {
        return loopMode;
    }

    public static void setLoopMode(LoopMode mode) {
        loopMode = mode == null ? LoopMode.LIST : mode;
    }

    public static LoopMode cycleLoopMode() {
        LoopMode[] values = LoopMode.values();
        loopMode = values[(loopMode.ordinal() + 1) % values.length];
        return loopMode;
    }

    /** 最近一次失败/状态说明，界面可以直接显示。 */
    public static String status() {
        return status;
    }

    public static List<NeteaseApi.Song> queue() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<NeteaseApi.Song>(queue));
        }
    }

    public static void setQueue(List<NeteaseApi.Song> songs) {
        synchronized (LOCK) {
            queue.clear();
            if (songs != null) {
                queue.addAll(songs);
            }
        }
    }

    public static int queueIndex() {
        synchronized (LOCK) {
            if (current == null) {
                return -1;
            }

            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).getId() == current.getId()) {
                    return i;
                }
            }

            return -1;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  控制                                                               */
    /* ------------------------------------------------------------------ */

    public static void play(NeteaseApi.Song song) {
        if (song == null) {
            return;
        }

        stopInternal();

        final long generation = GENERATION.incrementAndGet();
        Logging.debugTagged("Music", "play #%d %s <- %s", generation, song.getName(), caller());
        current = song;
        durationMs = song.getDurationMs();
        positionMs = 0L;
        seekTarget = -1L;
        paused = false;
        stopRequested = false;
        playing = true;
        status = "Loading " + song.getName();

        worker = new Thread(() -> run(song, generation), "Netease Player");
        worker.setDaemon(true);
        worker.start();
    }

    public static void pause() {
        if (playing) {
            paused = true;
        }
    }

    /**
     * 只把某首歌装回"当前曲目"，不开始播放。
     * 用于重开游戏后恢复上次那首（歌词 HUD 立刻有内容，用户在播放器里点播放才出声）。
     */
    public static void restore(NeteaseApi.Song song) {
        if (song == null) {
            return;
        }

        stopInternal();
        current = song;
        durationMs = song.getDurationMs();
        positionMs = 0L;
        seekTarget = -1L;
        playing = false;
        paused = true;
        status = "Paused";
    }

    public static void resume() {
        if (playing) {
            paused = false;
        }
    }

    public static void togglePlayPause() {
        if (!playing) {
            if (current != null) {
                play(current);
            }

            return;
        }

        paused = !paused;
    }

    public static void stop() {
        stopInternal();
        current = null;
        positionMs = 0L;
        durationMs = 0L;
        status = "";
    }

    public static void seek(long millis) {
        if (!playing) {
            return;
        }

        seekTarget = Math.max(0L, millis);
    }

    public static void seekFraction(float fraction) {
        long total = durationMs > 0L ? durationMs : 1L;
        seek((long)(Math.max(0.0F, Math.min(1.0F, fraction)) * total));
    }

    public static void playNext() {
        NeteaseApi.Song next = nextInQueue(1);
        if (next != null) {
            play(next);
        }
    }

    public static void playPrevious() {
        NeteaseApi.Song previous = nextInQueue(-1);
        if (previous != null) {
            play(previous);
        }
    }

    private static NeteaseApi.Song nextInQueue(int step) {
        synchronized (LOCK) {
            if (queue.isEmpty()) {
                return null;
            }

            int index = -1;

            for (int i = 0; i < queue.size(); i++) {
                if (current != null && queue.get(i).getId() == current.getId()) {
                    index = i;
                    break;
                }
            }

            if (index < 0) {
                return queue.get(0);
            }

            int size = queue.size();
            int next = ((index + step) % size + size) % size;
            return queue.get(next);
        }
    }

    private static void stopInternal() {
        stopRequested = true;
        playing = false;
        paused = false;

        SourceDataLine currentLine = line;
        line = null;

        if (currentLine != null) {
            try {
                currentLine.stop();
                currentLine.flush();
                currentLine.close();
            } catch (Throwable ignored) {
                // 已经关了
            }
        }

        // 故意不 interrupt：旧线程可能正卡在 HTTP 读取里，打断只会让下载半途而废。
        // 它靠 stopRequested 在每个解码帧检查后自行退出，并且代号校验会拦住误接管。
        worker = null;
    }

    /* ------------------------------------------------------------------ */
    /*  播放线程                                                           */
    /* ------------------------------------------------------------------ */

    private static void run(NeteaseApi.Song song, long generation) {
        File file = null;

        try {
            if (isStale(generation)) {
                return;
            }

            lastAttemptFailed = false;
            file = cacheFile(song);

            // 缓存里可能是上次失败留下的 HTML 错误页 / flac 直链，先按文件头验一遍
            if (file.isFile() && !looksLikeMp3(file)) {
                Logging.warnTagged("Music", "Discarding invalid cache file %s", file);
                file.delete();
            }

            if (!file.isFile() || file.length() == 0L) {
                String url = NeteaseApi.playUrl(song.getId(), BITRATE);

                if (url == null) {
                    status = "No playable url (login or VIP required)";
                    lastAttemptFailed = true;
                    Logging.warnTagged("Music", "No playable url for %s", song.getName());
                    return;
                }

                Logging.debugTagged("Music", "Resolved url for %s: %s", song.getName(), shorten(url));
                status = "Buffering...";

                if (!download(url, file, generation)) {
                    status = "Download failed";
                    lastAttemptFailed = true;
                    return;
                }

                if (!looksLikeMp3(file)) {
                    Logging.warnTagged("Music", "Downloaded file is not MPEG audio, discarding %s", file);
                    file.delete();
                    status = "Track is not playable (no mp3 stream)";
                    lastAttemptFailed = true;
                    return;
                }
            }

            if (song.getDurationMs() <= 0L) {
                durationMs = probeDuration(file);
            }

            decodeLoop(file, generation);
        } catch (Throwable throwable) {
            status = "Playback error: " + throwable.getClass().getSimpleName();
            Logging.errorTagged("Music", "Playback failed for %s: %s", song.getName(), throwable);
        } finally {
            SourceDataLine currentLine = line;
            line = null;

            if (currentLine != null) {
                try {
                    currentLine.stop();
                    currentLine.flush();
                    currentLine.close();
                } catch (Throwable ignored) {
                    // 已经关了
                }
            }

            // 只有「还是当前这一代播放」才允许接下一首；
            // 否则旧线程退出时会把刚点的新歌顶掉，表现就是一首歌被反复重下。
            if (generation == GENERATION.get()) {
                playing = false;

                // 取直链/下载阶段就失败的话，切下一首只会把整张歌单快速刷一遍
                if (!stopRequested && !lastAttemptFailed && current != null && current.getId() == song.getId()) {
                    handleTrackEnd();
                }
            }
        }
    }

    /** 逐帧解码 + 输出；拖动进度条时重开文件并丢弃到目标位置。 */
    private static void decodeLoop(File file, long generation) throws Exception {
        long startOffset = 0L;

        while (!stopRequested && !isStale(generation)) {
            boolean seeking = startOffset > 0L;
            boolean reopen = false;
            Bitstream bitstream = new Bitstream(new BufferedInputStream(new FileInputStream(file), 65536));
            Decoder decoder = new Decoder();
            long played = 0L;
            int sampleRate = 44100;

            try {
                Header header;

                while (!stopRequested && !isStale(generation) && (header = bitstream.readFrame()) != null) {
                    SampleBuffer output = (SampleBuffer)decoder.decodeFrame(header, bitstream);
                    int samples = output.getBufferLength();
                    int channels = Math.max(1, output.getChannelCount());
                    sampleRate = output.getSampleFrequency() > 0 ? output.getSampleFrequency() : 44100;

                    if (samples > 0) {
                        played += (long)samples * 1000L / ((long)channels * (long)sampleRate);
                    }

                    if (seeking) {
                        if (played >= startOffset) {
                            seeking = false;
                            positionMs = played;
                        }

                        bitstream.closeFrame();
                        continue;
                    }

                    if (seekTarget >= 0L) {
                        reopen = true;
                        bitstream.closeFrame();
                        break;
                    }

                    while (paused && !stopRequested && !isStale(generation)) {
                        sleepQuietly(20L);
                    }

                    if (stopRequested || isStale(generation)) {
                        bitstream.closeFrame();
                        break;
                    }

                    ensureLine(sampleRate, channels);
                    writePcm(output);
                    positionMs = played;
                    bitstream.closeFrame();
                }
            } finally {
                try {
                    bitstream.close();
                } catch (Throwable ignored) {
                    // 已经关了
                }
            }

            if (stopRequested || isStale(generation)) {
                return;
            }

            if (reopen) {
                long target = seekTarget;
                seekTarget = -1L;
                startOffset = Math.max(0L, target);
                positionMs = startOffset;
                continue;
            }

            return;
        }
    }

    private static void ensureLine(int sampleRate, int channels) throws Exception {
        SourceDataLine currentLine = line;

        if (currentLine != null) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine opened = (SourceDataLine)AudioSystem.getLine(info);
        opened.open(format, BUFFER_BYTES);
        opened.start();
        line = opened;
        status = "";
        Logging.infoTagged("Music", "Playing %s", current == null ? "?" : current.getName());
    }

    private static void writePcm(SampleBuffer output) {
        SourceDataLine currentLine = line;

        if (currentLine == null) {
            return;
        }

        short[] buffer = output.getBuffer();
        int count = output.getBufferLength();
        float gain = volume;
        byte[] bytes = new byte[count * 2];

        for (int i = 0; i < count; i++) {
            int value = (int)(buffer[i] * gain);

            if (value > 32767) {
                value = 32767;
            } else if (value < -32768) {
                value = -32768;
            }

            bytes[i * 2] = (byte)(value & 0xFF);
            bytes[i * 2 + 1] = (byte)((value >> 8) & 0xFF);
        }

        currentLine.write(bytes, 0, bytes.length);
    }

    private static void handleTrackEnd() {
        switch (loopMode) {
            case ONE:
                play(current);
                break;
            case OFF:
                playing = false;
                positionMs = 0L;
                break;
            case LIST:
            default:
                NeteaseApi.Song next = nextInQueue(1);

                if (next != null && (current == null || next.getId() != current.getId())) {
                    play(next);
                } else {
                    playing = false;
                }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  文件                                                               */
    /* ------------------------------------------------------------------ */

    private static File cacheFile(NeteaseApi.Song song) {
        File directory = new File(NeteaseApi.configDirectory(), "music-cache");

        if (!directory.isDirectory() && !directory.mkdirs()) {
            Logging.warnTagged("Music", "Could not create %s", directory);
        }

        return new File(directory, song.getId() + ".mp3");
    }

    /**
     * 下载到缓存文件。
     *
     * <p>临时文件名带播放代号：换歌时旧线程可能还卡在 {@code read} 里，
     * 共用同一个 {@code .part} 会撞出「另一个程序正在使用此文件」。
     * 只有完整拿到 Content-Length 声明的大小才允许改名为正式缓存，
     * 否则半截 mp3 会被当成有效缓存，下次直接播放出几秒就断。
     */
    private static boolean download(String url, File target, long generation) {
        HttpURLConnection connection = null;
        File temporary = new File(target.getAbsolutePath() + "." + generation + ".part");

        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setInstanceFollowRedirects(true);

            int code = connection.getResponseCode();

            if (code != 200) {
                Logging.warnTagged("Music", "Download failed with HTTP %d", code);
                return false;
            }

            long expected = connection.getContentLength();
            long written = 0L;

            try (InputStream in = connection.getInputStream();
                OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary), 65536)) {
                byte[] buffer = new byte[16384];
                int read;

                while (!stopRequested && !isStale(generation) && (read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            }

            if (stopRequested || isStale(generation)) {
                temporary.delete();
                return false;
            }

            if (expected > 0L && written != expected) {
                Logging.warnTagged("Music", "Truncated download: %d of %d bytes", written, expected);
                temporary.delete();
                return false;
            }

            // CDN 有时候用 chunked 传输不给 Content-Length，这时按码率估算总时长，
            // 明显短于歌曲元数据说明下到的是一段半截 mp3（播几秒就 EOF、然后跳下一首）。
            if (expected <= 0L && durationMs > 0L) {
                long approximateMs = written * 8L * 1000L / BITRATE;

                if (approximateMs < durationMs * 7L / 10L) {
                    Logging.warnTagged("Music", "Short download: %.1fs of %.1fs", approximateMs / 1000.0F, durationMs / 1000.0F);
                    temporary.delete();
                    return false;
                }
            }

            if (temporary.length() == 0L) {
                temporary.delete();
                return false;
            }

            if (target.exists() && !target.delete()) {
                Logging.warnTagged("Music", "Could not replace cache file %s", target);
                return false;
            }

            boolean renamed = temporary.renameTo(target);
            Logging.debugTagged("Music", "Cached %s (%d bytes, renamed=%s)", target.getName(), target.length(), renamed);
            return renamed;
        } catch (Throwable throwable) {
            Logging.warnTagged("Music", "Could not download track: %s", throwable);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }

            if (temporary.isFile() && temporary.length() == 0L) {
                temporary.delete();
            }
        }
    }

    /** 没有元数据时，用解码一帧 + 文件大小估算时长。 */
    private static long probeDuration(File file) {
        Bitstream bitstream = null;

        try {
            bitstream = new Bitstream(new BufferedInputStream(new FileInputStream(file), 8192));
            Decoder decoder = new Decoder();
            Header header = bitstream.readFrame();

            if (header == null) {
                return 0L;
            }

            decoder.decodeFrame(header, bitstream);
            int bitrate = header.bitrate();
            long bytes = file.length();

            if (bitrate > 0) {
                return bytes * 8000L / bitrate;
            }
        } catch (Throwable ignored) {
            // 估不出来就返回 0，界面用进度未知处理
        } finally {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Throwable ignored) {
                    // 已经关了
                }
            }
        }

        return 0L;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 是否有更新的播放请求把这个线程顶掉了；旧线程看到就要立刻收手。 */
    private static boolean isStale(long generation) {
        return generation != GENERATION.get();
    }

    /** 临时诊断：谁调用了 play()。 */
    private static String caller() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();

        for (int i = 2; i < Math.min(trace.length, 8); i++) {
            String name = trace[i].getClassName();

            if (name.startsWith("java.lang.Thread") || name.equals(NeteasePlayer.class.getName())) {
                continue;
            }

            return name.substring(name.lastIndexOf('.') + 1) + "#" + trace[i].getMethodName() + ":" + trace[i].getLineNumber();
        }

        return "?";
    }

    /** JLayer 只吃 MPEG：ID3 头或帧同步字开头才算合格。 */
    private static boolean looksLikeMp3(File file) {        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] head = new byte[4];

            if (raf.read(head) < 4) {
                return false;
            }

            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return true;
            }

            return (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String shorten(String url) {
        if (url == null) {
            return "null";
        }

        return url.length() <= 90 ? url : url.substring(0, 90) + "...";
    }
}

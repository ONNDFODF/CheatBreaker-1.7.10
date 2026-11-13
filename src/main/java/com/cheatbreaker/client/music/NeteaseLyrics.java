package com.cheatbreaker.client.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一首歌的歌词：按时间排好的一行行文本。
 *
 * <p>只认 {@code [mm:ss.xx]} 这种逐行时间轴；一行里可以有多个时间标签
 * （{@code [00:12.00][01:20.00]副歌}），每个都会生成一条。
 * 增强型逐字歌词（{@code <mm:ss.xx>}）退化成整行显示 —— 逐字动画收益太低。
 */
public final class NeteaseLyrics {
    public static final NeteaseLyrics EMPTY = new NeteaseLyrics(Collections.<Line>emptyList(), "none");

    private final List<Line> lines;
    private final String source;

    private NeteaseLyrics(List<Line> lines, String source) {
        this.lines = lines;
        this.source = source;
    }

    public static final class Line {
        private final long timeMs;
        private final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        public long getTimeMs() {
            return this.timeMs;
        }

        public String getText() {
            return this.text;
        }
    }

    public List<Line> lines() {
        return this.lines;
    }

    public String source() {
        return this.source;
    }

    public boolean isEmpty() {
        return this.lines.isEmpty();
    }

    public Line line(int index) {
        return index >= 0 && index < this.lines.size() ? this.lines.get(index) : null;
    }

    /** 当前该显示哪一行；没有歌词返回 -1。 */
    public int indexAt(long positionMs) {
        if (this.lines.isEmpty()) {
            return -1;
        }

        if (positionMs < 0L) {
            return 0;
        }

        int index = 0;

        for (int i = 0; i < this.lines.size(); i++) {
            if (this.lines.get(i).getTimeMs() <= positionMs) {
                index = i;
            } else {
                break;
            }
        }

        return index;
    }

    /** 解析 LRC。 */
    public static NeteaseLyrics parse(String raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return new NeteaseLyrics(Collections.<Line>emptyList(), source);
        }

        List<Line> parsed = new ArrayList<Line>();

        for (String rawLine : raw.split("\r?\n")) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            List<Long> times = new ArrayList<Long>();
            int cursor = 0;

            // 连续吃掉行首的 [..] 标签，非时间标签（[ar:] [ti:] 之类）会被 parseTime 丢掉
            while (cursor < line.length() && line.charAt(cursor) == '[') {
                int close = line.indexOf(']', cursor);

                if (close < 0) {
                    break;
                }

                long time = parseTime(line.substring(cursor + 1, close));

                if (time >= 0L) {
                    times.add(time);
                }

                cursor = close + 1;
            }

            if (times.isEmpty()) {
                continue;
            }

            String text = line.substring(cursor).trim();

            if (text.isEmpty()) {
                continue;
            }

            for (Long time : times) {
                parsed.add(new Line(time, text));
            }
        }

        parsed.sort((a, b) -> Long.compare(a.getTimeMs(), b.getTimeMs()));
        return new NeteaseLyrics(parsed, source);
    }

    /** {@code mm:ss.xx} / {@code mm:ss.xxx} / {@code mm:ss} → 毫秒；不认识返回 -1。 */
    private static long parseTime(String token) {
        int colon = token.indexOf(':');

        if (colon < 0) {
            return -1L;
        }

        try {
            int minutes = Integer.parseInt(token.substring(0, colon).trim());
            String rest = token.substring(colon + 1).trim();
            int dot = rest.indexOf('.');
            double seconds;

            if (dot < 0) {
                seconds = Double.parseDouble(rest);
            } else {
                String fraction = rest.substring(dot + 1);
                double scale = Math.pow(10.0D, fraction.length());
                seconds = Double.parseDouble(rest.substring(0, dot)) + Double.parseDouble(fraction) / scale;
            }

            return Math.round((minutes * 60.0D + seconds) * 1000.0D);
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}

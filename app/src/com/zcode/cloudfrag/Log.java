package com.zcode.cloudfrag;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 进程内的日志缓冲，界面轮询取。 */
final class Log {

    private static final int MAX = 600;
    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private static final String FILE = "/data/local/tmp/cf_log.txt";
    private static volatile java.io.File file;
    private static volatile boolean running;

    private Log() {
    }

    /** App 进程没有 /data/local/tmp 写权限，落到自己的私有目录。 */
    static void setFile(java.io.File f) {
        file = f;
    }

    static void add(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        // 同时写 logcat 和文件，方便 adb 侧排查
        try {
            android.util.Log.i("cloudfrag", line);
        } catch (Throwable ignored) {
        }
        try {
            java.io.File f = file;
            if (f != null) {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
                try {
                    fo.write((line + "\n").getBytes("UTF-8"));
                    fo.flush();
                } finally {
                    fo.close();
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (LINES) {
            LINES.add(line);
            while (LINES.size() > MAX) {
                LINES.remove(0);
            }
        }
    }

    static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    static void setRunning(boolean r) {
        running = r;
    }

    static boolean isRunning() {
        return running;
    }
}

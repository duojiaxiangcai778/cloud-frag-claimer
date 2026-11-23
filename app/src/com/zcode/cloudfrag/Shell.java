package com.zcode.cloudfrag;

import java.io.File;

/**
 * root 命令通道。
 *
 * 踩过的坑（都实测过）：
 * 1) 不要 Process.waitFor / 读 su 管道等子进程收尾：uiautomator、dumpsys 会留下不退出的
 *    子进程，waitFor 能挂几分钟。
 * 2) App 切到后台后，每次新起一个 su 进程可能要 30~60 秒（授权检查被拖住），所以全部命令
 *    复用同一个常驻 root shell：启动时用一次 su 拉起 daemon.sh，之后靠文件队列通信。
 * 3) dump 文件是边写边读的，必须等它写完（以 </hierarchy> 结尾）再读，否则解析到半截。
 */
final class Shell {

    static String DIR = "/data/data/com.zcode.cloudfrag/files";

    private static final Object LOCK = new Object();
    private static volatile boolean daemonUp;

    private Shell() {
    }

    static void setDir(String dir) {
        DIR = dir;
    }

    // ------------------------------------------------------------------ daemon

    private static String script() {
        return "#!/system/bin/sh\n"
                + "D=" + DIR + "\n"
                + "while true; do\n"
                + "  if [ -f \"$D/stop.flag\" ]; then rm -f \"$D/stop.flag\"; rm -f \"$D/run.flag\";"
                + " exit 0; fi\n"
                + "  if [ -f \"$D/run.flag\" ]; then\n"
                + "    rm -f \"$D/run.flag\"\n"
                + "    rm -f \"$D/out.txt\"\n"
                + "    sh \"$D/cmd.sh\" > \"$D/out.txt\" 2>&1\n"
                + "    touch \"$D/done.flag\"\n"
                + "  fi\n"
                + "  sleep 0.05\n"
                + "done\n";
    }

    /** 拉起常驻 root shell（只在开始时调一次）。 */
    static void ensureDaemon() {
        if (daemonUp) {
            return;
        }
        synchronized (LOCK) {
            if (daemonUp) {
                return;
            }
            writeText(DIR + "/daemon.sh", script());
            new File(DIR + "/stop.flag").delete();
            new File(DIR + "/run.flag").delete();
            new File(DIR + "/done.flag").delete();
            exec("sh " + DIR + "/daemon.sh >/dev/null 2>&1 &");
            sleep(400);
            daemonUp = true;
            Log.add("root shell 已启动");
        }
    }

    static void stopDaemon() {
        if (!daemonUp) {
            return;
        }
        writeText(DIR + "/stop.flag", "1");
        daemonUp = false;
    }

    // ------------------------------------------------------------------ 执行

    /** 在常驻 root shell 里跑一条命令，返回输出（超时返回已有内容）。 */
    static String su(String cmd, int timeoutMs) {
        ensureDaemon();
        synchronized (LOCK) {
            File done = new File(DIR + "/done.flag");
            File out = new File(DIR + "/out.txt");
            done.delete();
            out.delete();
            if (!writeText(DIR + "/cmd.sh", cmd + "\n")) {
                return "";
            }
            if (!writeText(DIR + "/run.flag", "1")) {
                return "";
            }
            long deadline = System.currentTimeMillis() + Math.max(1200, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                if (done.exists()) {
                    String s = readText(DIR + "/out.txt");
                    return s == null ? "" : s;
                }
                sleep(30);
            }
            Log.add("命令超时：" + (cmd.length() > 40 ? cmd.substring(0, 40) : cmd));
            return "";
        }
    }

    /** 抓界面 XML：dump 写文件，等到写完（以 </hierarchy> 结尾）再读。 */
    static String dumpXml(int timeoutMs) {
        String path = DIR + "/ui_" + System.nanoTime() + ".xml";
        su("timeout 8 uiautomator dump " + path + " >/dev/null 2>&1", 9000);
        long deadline = System.currentTimeMillis() + Math.max(1200, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            String s = readText(path);
            if (s != null && s.length() > 200 && s.trim().endsWith("</hierarchy>")) {
                new File(path).delete();
                return s;
            }
            sleep(60);
        }
        new File(path).delete();
        return null;
    }

    // ------------------------------------------------------------------ 基础动作

    static void tap(int x, int y) {
        su("input tap " + x + " " + y, 5000);
    }

    static void swipe(int x1, int y1, int x2, int y2, int ms) {
        su("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms, 6000);
    }

    static void key(int code) {
        su("input keyevent " + code, 5000);
    }

    static void amStart(String component) {
        su("am start -n " + component, 8000);
    }

    static void forceStop(String pkg) {
        su("am force-stop " + pkg, 8000);
    }

    /**
     * 把这套 Activity 所在任务栈拽回前台（不重建、不丢状态）。
     * 实测 1.5s 左右；比 HOME + 拉起首页 + BACK 快 4 倍，而且 H5 的 JS 状态原样保留。
     * 云服务那个 H5 容器没导出，只有 root 能 start（shell 会 SecurityException）。
     */
    static void amStartReorder(String component) {
        su("am start --activity-reorder-to-front -n " + component, 8000);
    }

    /** 同上，但把 am 的输出返回出来，方便排查（daemon 里跑 am 可能被后台启动限制拦）。 */
    static String amStartReorderOut(String component) {
        return su("am start --activity-reorder-to-front -n " + component + " 2>&1", 8000);
    }

    /** 绕过常驻 shell，用一个全新的 su 进程拉起（用于 am start 被拦的情况）。 */
    static void amStartFresh(String args) {
        exec("am start " + args);
    }

    /** 当前已装的三方包集合（约 150ms）。 */
    static java.util.Set<String> thirdPartyPackages() {
        String out = su("cmd package list packages -3", 12000);
        java.util.Set<String> set = new java.util.HashSet<String>();
        if (out == null) {
            return set;
        }
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                String p = line.substring(8).trim();
                if (p.length() > 0) {
                    set.add(p);
                }
            }
        }
        return set;
    }

    /** 比对包集合：返回新增的包名，没有返回 null。 */
    static String newPackageSince(java.util.Set<String> before) {
        java.util.Set<String> now = thirdPartyPackages();
        for (String p : now) {
            if (!before.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** 当前前台包名（取不到返回空串）。 */
    static String foregroundPkg() {
        return parseFocusPkg(su("dumpsys window | grep -m1 mCurrentFocus", 6000));
    }

    static String parseFocusPkg(String out) {
        if (out == null) {
            return "";
        }
        int i = out.indexOf("mCurrentFocus=");
        if (i < 0) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("u0 ([A-Za-z0-9_.]+)/").matcher(out.substring(i));
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    // ------------------------------------------------------------------ 进程 & IO

    /** 启动一个 su 进程（只有拉起 daemon 时才用），不等它。 */
    static Process exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            try {
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                pb.redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")));
            } catch (Throwable ignored) {
            }
            return pb.start();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean writeText(String path, String text) {
        try {
            File f = new File(path);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
            try {
                fo.write(text.getBytes("UTF-8"));
                fo.flush();
            } finally {
                fo.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String readText(String path) {
        File f = new File(path);
        try {
            if (!f.exists() || f.length() <= 0) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                int n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
                return new String(buf, 0, off, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** root 是否可用。 */
    static boolean isRoot() {
        String out = su("id", 8000);
        return out != null && out.contains("uid=0");
    }

    /**
     * 截屏后按坐标取点，判断有没有「橙色」像素。
     * 用来快速判断右下角「50G 限时特惠」浮窗在不在（130ms 截屏 + 解码，比 dump 1.5s 便宜得多）。
     * 浮窗橙色条实测在 x 922..1186 / y 2403..2631，取几个点就够。
     */
    static boolean anyOrangeAt(int[][] pts) {
        String path = DIR + "/px_" + System.nanoTime() + ".png";
        su("screencap -p " + path, 9000);
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
        new File(path).delete();
        if (bmp == null) {
            return false;
        }
        boolean hit = false;
        try {
            for (int[] p : pts) {
                int x = p[0];
                int y = p[1];
                if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) {
                    continue;
                }
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if (r > 200 && g > 90 && g < 185 && b < 115) {
                    hit = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        bmp.recycle();
        return hit;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.zcode.cloudfrag;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.zcode.cloudfrag.Ui.Node;

import java.io.File;
import java.util.List;

/**
 * 通用拼图滑块（「安全验证 / 向右拖动滑块验证」）自动通过器。
 *
 * ============================ 方法论 ============================
 * 这类验证码的通过条件只有一句话：**把拼图块拖到缺口上**。
 * 所以整个流程拆成「先比对位置，再滑动，再校验」三步：
 *
 *   ① 定位 —— 从截图里量出三个东西（全部动态测量，不写死任何坐标）：
 *        · 拼图照片区：弹窗是白的、照片是花的，用"非白横向带"切出照片的那块矩形；
 *        · 拼图块（白圈）：半径不固定（实测 70，别处可能 44~104），
 *          判据 = 环上 24 点够白 且 环外 1.55r 处不够白（后半条用来排除白色对话框背景，
 *          旧版就是因为缺这一条，每次都定位到标题文字周围）；
 *        · 缺口（暗色圆盘）：盘内平均亮度 明显低于 外圈平均亮度。
 *          照片背景再花，这个"暗进去一块"的差值都很稳。
 *   ② 计算 —— dx = 缺口x − 拼图块x
 *   ③ 滑动 —— 按住手柄，分段 MOVE 过去，中途再量一次拼图块实际位置做闭环修正，
 *             对齐了才松手。
 *   ④ 校验 —— 再抓一次界面，弹窗没了才算过；没过就重新截图重来（失败会换新图，
 *             所以每一轮都必须重新定位）。
 *
 * ============================ 实测参数 ============================
 * 以 1264×2780 屏为参考（逐像素量过；分辨率不同不改代码，只做参考）：
 *   拼图照片区   y ≈ 1047..1550，x ≈ 110..1150
 *   拼图块(白圈) 半径 ≈ 70，静止圆心 x ≈ 275（= 照片区左边缘 +165）
 *   缺口(暗色盘) 半径 ≈ 66，与拼图块同一水平线
 *   滑块手柄     白色胶囊 x ≈ 131..282、y ≈ 1625..1730 → 圆心 (206, 1674)
 *   手柄胶囊内部有两个深色 ▶▶ 图标，所以"整列全白"会被切成两段，要允许 ≤80px 空洞
 *
 * ============================ 三个关键坑 ============================
 * 1) `input swipe` 的轨迹密度不够，容易被判机器；实测 5 秒慢拖偶尔能过。
 *    改用 `input motionevent DOWN / MOVE... / UP` 分多次注入 ——
 *    已验证可以跨进程保持按下状态（拖动页面实测位移 12.48%），
 *    这样步数、时长、缓动全部自己控制。
 * 2) 失败一次拼图就会换一张图，所以**每一轮都必须重新截图重新定位**，
 *    拿上一轮算出来的 dx 重试是没有意义的。
 * 3) 手柄胶囊里有两个深色 ▶▶ 图标，用"整列全白"找手柄会被切成 161/251 两段来回飘，
 *    必须允许中间有空洞。
 */
final class Captcha {

    static final String[] TEXTS = {"安全验证", "拖动滑块", "向右拖动"};

    // ---------- 识别参数 ----------
    /** 环上采样亮度阈值（0..255）。白圈是很白的描边，195 足够宽容。 */
    private static final int RING_WHITE = 195;
    /** 环半径扫描范围：实测 70，放宽到 44..104 覆盖其它尺寸的弹窗。 */
    private static final int RING_MIN = 44;
    private static final int RING_MAX = 104;
    /** 缺口判据：盘内比外圈暗多少（归一化到 0..1）。实测缺口约 -0.39。 */
    private static final double GAP_DELTA = -0.18;
    /** 拖拽分段数。太少轨迹稀疏，太多太慢。 */
    private static final int STEPS = 26;
    /** 闭环修正的容差（像素）。 */
    private static final int TOLERANCE = 5;

    private Captcha() {
    }

    // ==================== 对外接口 ====================

    static boolean isShown(List<Node> nodes) {
        if (nodes == null) {
            return false;
        }
        for (Node n : nodes) {
            if (n.text == null) {
                continue;
            }
            for (String t : TEXTS) {
                if (n.text.contains(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 有滑块就处理；返回 true 表示出现过滑块（成功或已放弃）。 */
    static boolean handle(Engine e) {
        List<Node> nodes = e.dumpStable();
        if (!isShown(nodes)) {
            return false;
        }
        e.log("出现风控滑块「安全验证」，开始定位");
        int handleY = -1;
        for (Node n : nodes) {
            if (n.text != null && n.text.contains("拖动滑块")) {
                handleY = n.cy();
                break;
            }
        }
        for (int round = 1; round <= 4; round++) {
            Shot s = capture();
            if (s == null) {
                e.log("滑块：截图失败");
                return true;
            }
            try {
                int[] photo = photoBox(s);
                if (photo == null) {
                    e.log("滑块：找不到拼图照片区（第 " + round + " 轮）");
                    Shell.sleep(1000);
                    continue;
                }
                int[] piece = findPiece(s, photo);
                int[] gap = findGap(s, photo, piece);
                int hy = handleY > 0 ? handleY : (int) (s.h * 0.602);
                int hx = findHandle(s, hy);
                if (gap == null) {
                    e.log("滑块：定位不到缺口（第 " + round + " 轮），刷新再试");
                    Shell.sleep(1200);
                    continue;
                }
                // 找不到白圈时按实测比例兜底：照片区左边缘 +165
                int pieceX = piece != null ? piece[0] : (photo[0] + 165);
                int dx = gap[0] - pieceX;
                e.log("滑块[" + round + "轮] 照片区 x" + photo[0] + ".." + photo[2]
                        + " y" + photo[1] + ".." + photo[3]
                        + " | 拼图 x=" + pieceX + (piece == null ? "(兜底)" : "")
                        + " 缺口 x=" + gap[0] + " | dx=" + dx
                        + " 手柄(" + hx + "," + hy + ")");
                if (dx < 40 || dx > s.w * 0.78) {
                    e.log("滑块：dx 不合理，跳过这轮");
                    Shell.sleep(1000);
                    continue;
                }
                boolean ok = drag(e, s, photo, hx, hy, gap[0], dx);
                if (!ok) {
                    e.log("滑块：拖动注入失败");
                }
                Shell.sleep(1500);
                if (!isShown(e.dumpStable())) {
                    e.log("滑块已过（第 " + round + " 轮，dx=" + dx + "）");
                    return true;
                }
                e.log("滑块没过（第 " + round + " 轮），换图重来");
                // 留一张现场图，方便下次拿来复现（脚本/人工都能用）
                Shell.su("screencap -p /sdcard/cf_captcha_fail_" + round + ".png", 8000);
                Shell.sleep(1200);
            } finally {
                s.bmp.recycle();
            }
        }
        cancel(e);
        e.log("滑块 4 轮都没过，取消");
        return true;
    }

    /**
     * 自检：只做识别、不拖动，把每一步量到的值打出来。
     * 没有弹窗时会明确说"没找到照片区"，用来随时验证识别链路是否还准。
     */
    static String selfTest() {
        Shot s = capture();
        if (s == null) {
            return "截图失败（root 或存储不可用？）";
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("屏幕 ").append(s.w).append('x').append(s.h).append('\n');
            int[] photo = photoBox(s);
            if (photo == null) {
                sb.append("未找到拼图照片区 —— 当前应该没有滑块弹窗");
                return sb.toString();
            }
            sb.append("照片区 x").append(photo[0]).append("..").append(photo[2])
                    .append("  y").append(photo[1]).append("..").append(photo[3]).append('\n');
            int[] piece = findPiece(s, photo);
            sb.append("拼图块: ").append(piece == null ? "未找到" :
                    ("x=" + piece[0] + " y=" + piece[1] + " 匹配度=" + piece[2] + "%")).append('\n');
            int[] gap = findGap(s, photo, piece);
            sb.append("缺口: ").append(gap == null ? "未找到" :
                    ("x=" + gap[0] + " y=" + gap[1] + " 亮度差=" + (gap[2] / 1000.0))).append('\n');
            int hy = (int) (s.h * 0.602);
            sb.append("手柄: x=").append(findHandle(s, hy)).append(" y=").append(hy).append('\n');
            if (gap != null) {
                int pieceX = piece != null ? piece[0] : (photo[0] + 165);
                sb.append("=> 建议 dx = ").append(gap[0] - pieceX);
            }
            return sb.toString();
        } finally {
            s.bmp.recycle();
        }
    }

    // ==================== 截图 ====================

    private static final class Shot {
        Bitmap bmp;
        int w;
        int h;
        int[] px;

        double lum(int x, int y) {
            if (x < 0 || y < 0 || x >= w || y >= h) {
                return 0;
            }
            int c = px[y * w + x];
            return (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3.0;
        }
    }

    private static Shot capture() {
        String path = Shell.DIR + "/cap_" + System.nanoTime() + ".png";
        Shell.su("screencap -p " + path, 9000);
        Bitmap bmp = BitmapFactory.decodeFile(path);
        new File(path).delete();
        if (bmp == null) {
            return null;
        }
        Shot s = new Shot();
        s.bmp = bmp;
        s.w = bmp.getWidth();
        s.h = bmp.getHeight();
        s.px = new int[s.w * s.h];
        try {
            bmp.getPixels(s.px, 0, s.w, 0, 0, s.w, s.h);
        } catch (Throwable t) {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {
            }
            return null;
        }
        return s;
    }

    private static void cancel(Engine e) {
        for (Node n : e.dumpStable()) {
            if (n.text != null && n.text.trim().equals("取消")) {
                e.tap(n.cx(), n.cy());
                Shell.sleep(700);
                return;
            }
        }
    }

    // ==================== 识别 ====================

    /**
     * 拼图照片区：一段足够高的「非白」横向带。
     * 弹窗白、照片花，这是最稳的分割依据，不依赖任何固定坐标。
     */
    private static int[] photoBox(Shot s) {
        int bestY0 = -1;
        int bestY1 = -1;
        int curStart = -1;
        int xFrom = (int) (s.w * 0.06);
        int xTo = (int) (s.w * 0.94);
        for (int y = (int) (s.h * 0.08); y < (int) (s.h * 0.85); y += 6) {
            int nonWhite = 0;
            int n = 0;
            for (int x = xFrom; x < xTo; x += 8) {
                n++;
                if (s.lum(x, y) < 245) {
                    nonWhite++;
                }
            }
            boolean isPhoto = n > 0 && nonWhite * 1.0 / n > 0.70;
            if (isPhoto) {
                if (curStart < 0) {
                    curStart = y;
                }
                if (y - curStart > bestY1 - bestY0) {
                    bestY0 = curStart;
                    bestY1 = y;
                }
            } else {
                curStart = -1;
            }
        }
        if (bestY0 < 0 || bestY1 - bestY0 < s.h * 0.08) {
            return null;
        }
        int yc = (bestY0 + bestY1) / 2;
        int x0 = -1;
        int x1 = -1;
        for (int x = (int) (s.w * 0.03); x < (int) (s.w * 0.97); x += 4) {
            if (s.lum(x, yc) < 245) {
                if (x0 < 0) {
                    x0 = x;
                }
                x1 = x;
            }
        }
        if (x0 < 0 || x1 - x0 < s.w * 0.45) {
            return null;
        }
        return new int[]{x0, bestY0, x1, bestY1};
    }

    /**
     * 拼图块（白色圆环）。
     * 双条件：环上够白 + 环外不够白。只靠"环上够白"会把对话框白底也算进来。
     */
    private static int[] findPiece(Shot s, int[] photo) {
        int bestX = -1;
        int bestY = -1;
        double best = 0;
        // 拼图块一定在照片区左半边，不用全图扫
        int xTo = Math.min(photo[0] + (photo[2] - photo[0]) / 2 + 60, photo[2] - 60);
        for (int cy = photo[1] + 70; cy < photo[3] - 70; cy += 5) {
            for (int cx = photo[0] + 50; cx < xTo; cx += 5) {
                for (int r = RING_MIN; r <= RING_MAX; r += 4) {
                    int hit = 0;
                    int n = 0;
                    for (int k = 0; k < 24; k++) {
                        double a = k * Math.PI * 2 / 24;
                        int x = cx + (int) Math.round(Math.cos(a) * r);
                        int y = cy + (int) Math.round(Math.sin(a) * r);
                        n++;
                        if (s.lum(x, y) >= RING_WHITE) {
                            hit++;
                        }
                    }
                    if (n == 0 || hit * 1.0 / n < 0.85) {
                        continue;
                    }
                    int outWhite = 0;
                    int on = 0;
                    for (int k = 0; k < 24; k++) {
                        double a = k * Math.PI * 2 / 24;
                        int x = cx + (int) Math.round(Math.cos(a) * r * 1.55);
                        int y = cy + (int) Math.round(Math.sin(a) * r * 1.55);
                        if (x < 0 || y < 0 || x >= s.w || y >= s.h) {
                            continue;
                        }
                        on++;
                        if (s.lum(x, y) >= RING_WHITE) {
                            outWhite++;
                        }
                    }
                    if (on == 0 || outWhite * 1.0 / on > 0.35) {
                        continue;
                    }
                    double score = hit * 1.0 / n;
                    if (score > best) {
                        best = score;
                        bestX = cx;
                        bestY = cy;
                    }
                }
            }
        }
        if (bestX < 0) {
            return null;
        }
        return new int[]{bestX, bestY, (int) Math.round(best * 100)};
    }

    /** 缺口：拼图区里的暗色圆盘，判据 = 盘内均值明显低于外圈均值。 */
    private static int[] findGap(Shot s, int[] photo, int[] piece) {
        int cy0;
        int cy1;
        if (piece != null) {
            cy0 = Math.max(photo[1] + 60, piece[1] - 55);
            cy1 = Math.min(photo[3] - 60, piece[1] + 55);
        } else {
            cy0 = photo[1] + 80;
            cy1 = photo[3] - 80;
        }
        int xStart = piece != null ? piece[0] + 110 : photo[0] + 220;
        int xEnd = photo[2] - 90;
        double best = 1;
        int bestX = -1;
        int bestY = -1;
        for (int cy = cy0; cy <= cy1; cy += 6) {
            for (int cx = xStart; cx < xEnd; cx += 6) {
                double inSum = 0;
                int inN = 0;
                for (int dx = -40; dx <= 40; dx += 10) {
                    for (int dy = -40; dy <= 40; dy += 10) {
                        if (dx * dx + dy * dy > 1600) {
                            continue;
                        }
                        inSum += s.lum(cx + dx, cy + dy);
                        inN++;
                    }
                }
                double outSum = 0;
                int outN = 0;
                for (int dx = -130; dx <= 130; dx += 16) {
                    for (int dy = -130; dy <= 130; dy += 16) {
                        int d2 = dx * dx + dy * dy;
                        if (d2 < 7700 || d2 > 16900) {
                            continue;
                        }
                        outSum += s.lum(cx + dx, cy + dy);
                        outN++;
                    }
                }
                if (inN == 0 || outN == 0) {
                    continue;
                }
                double delta = (inSum / inN - outSum / outN) / 255.0;
                if (delta < best) {
                    best = delta;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }
        if (bestX < 0 || best > GAP_DELTA) {
            return null;
        }
        return new int[]{bestX, bestY, (int) (best * 1000)};
    }

    /**
     * 手柄 x：滑块条左端的白色胶囊。
     * 取「这一行带内整列接近纯白」的最左连续段，允许 ≤80px 空洞（▶▶ 图标是深色的）。
     * 起始 x 必须跳过对话框左边距，否则会返回白边（旧版就是恒返回 74）。
     */
    private static int findHandle(Shot s, int handleY) {
        int y0 = Math.max(0, handleY - 22);
        int y1 = Math.min(s.h - 1, handleY + 22);
        int start = -1;
        int last = -1;
        int xFrom = (int) (s.w * 0.08);
        int xTo = (int) (s.w * 0.62);
        for (int x = xFrom; x < xTo; x += 3) {
            boolean allWhite = true;
            for (int y = y0; y <= y1; y += 7) {
                if (s.lum(x, y) < 244) {
                    allWhite = false;
                    break;
                }
            }
            if (allWhite) {
                if (start < 0) {
                    start = x;
                    last = x;
                } else if (x - last <= 80) {
                    last = x;
                } else {
                    break;
                }
            }
        }
        if (start < 0 || last - start < s.w * 0.05) {
            // 兜底：没找到胶囊就用轨道左侧固定比例
            return (int) (s.w * 0.163);
        }
        return (start + last) / 2;
    }

    // ==================== 拖动 ====================

    /**
     * 按住手柄 → 分段 MOVE → 中途闭环量一次 → 对齐后 UP。
     *
     * 用 motionevent 而不是 swipe，是因为 swipe 的轨迹由系统插值、密度不可控；
     * motionevent 可以自己决定步数、每一步的时长和缓动曲线，而且分多次进程调用
     * 依然能保持按下状态（已验证：拖页面实测位移 12.48%）。
     */
    private static boolean drag(Engine e, Shot shot, int[] photo,
                                int hx, int hy, int gapX, int dx) {
        String out = Shell.su("input motionevent DOWN " + hx + " " + hy, 4000);
        if (out != null && (out.contains("Unknown command") || out.contains("not found")
                || out.contains("Exception"))) {
            e.log("滑块：这台机器不支持 input motionevent，退回 swipe 慢拖");
            return swipeDrag(hx, hy, dx, 3000);
        }
        Shell.sleep(110);

        // 第一步：先走到 92%，留出闭环修正的余量
        int curX = glide(hx, hy, (int) Math.round(dx * 0.92), STEPS);
        Shell.sleep(90);

        // 第二步：闭环 —— 量一下拼图块实际被拖到哪了，差多少补多少
        Shot s1 = capture();
        if (s1 != null) {
            try {
                int[] p1 = findPiece(s1, photoBox(s1) != null ? photoBox(s1) : photo);
                if (p1 != null) {
                    int err = gapX - p1[0];
                    e.log("滑块闭环：拼图实际 x=" + p1[0] + " 目标 x=" + gapX + " 误差 " + err);
                    if (Math.abs(err) > TOLERANCE) {
                        curX = glide(curX, hy, err, 8);
                    }
                } else {
                    e.log("滑块闭环：拖动中量不到拼图块，按预估值松手");
                }
            } finally {
                s1.bmp.recycle();
            }
        }
        Shell.sleep(70);
        Shell.su("input motionevent UP " + curX + " " + hy, 4000);
        return true;
    }

    /** 从 x0 平滑移动 delta 像素（先快后慢 + 轻微抖动），返回结束时的 x。 */
    private static int glide(int x0, int y0, int delta, int steps) {
        int n = Math.max(6, steps);
        int last = x0;
        for (int i = 1; i <= n; i++) {
            double t = i * 1.0 / n;
            double ease = 1 - Math.pow(1 - t, 2.4);
            int x = x0 + (int) Math.round(delta * ease);
            int y = y0 + (int) Math.round((Math.random() - 0.5) * 3);
            Shell.su("input motionevent MOVE " + x + " " + y, 4000);
            Shell.sleep(26 + (int) (Math.random() * 20));
            last = x;
        }
        return last;
    }

    /** 老机器兜底：单次慢速 swipe。 */
    private static boolean swipeDrag(int hx, int hy, int dx, int ms) {
        try {
            Shell.su("input swipe " + hx + " " + hy + " " + (hx + dx) + " " + hy + " " + ms, 9000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

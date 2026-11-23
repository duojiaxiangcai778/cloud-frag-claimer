package com.zcode.cloudfrag;

import com.zcode.cloudfrag.Ui.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动化主流程：
 *   进任务页 → 反复「安装 / 打开 / 领取」→ 全部完成 → 结束。
 *
 * 2026-10-23 优化（全部真机验证过）：
 *   1) 返回云服务不再走 HOME → 拉起首页 → BACK，而是
 *      `am start --activity-reorder-to-front -n ...CloudWebBusinessActivity`：
 *      直接把整条任务栈拽回前台，H5 实例/JS 状态原样保留，实测约 1.5s（旧的 6~8s 且经常失败）。
 *      注意该 Activity 没导出，只有 root 能起（uid 0 绕过 exported 检查，shell 会 SecurityException）。
 *   2) 首页横幅是 2 页轮播，第 1 页才是「做任务免费领云空间」；点之前先把轮播拨回第 1 页，
 *      否则会落到「免费领200GB / 学生认证」页（以前靠运气）。
 *   3) 安装完成判定改成 `cmd package list packages -3` 前后差集（约 150ms/次），
 *      新包一出现立刻只刷新一次页面，不再盲等 48 秒。
 *   4) 打开任务返回后如果按钮还停在「打开」，主动刷新一次页面，避免再白等 16 秒。
 *   5) 特惠弹窗的关闭按钮改成按节点找（原来写死「立即抢购」下方 187px，位置早变了，
 *      实测会点到协议链接、跳到学生页）。
 */
final class Engine {

    interface Listener {
        void onLog(String msg);

        void onProgress(int claimed);

        void onFinish(boolean ok, String msg);
    }

    static final String CLOUD = "com.heytap.cloud";
    static final String LAUNCHER = "com.heytap.cloud/.home.ui.CloudLauncherActivity";
    /** H5 容器；未导出，root 才能直接 start。 */
    static final String WEB = "com.heytap.cloud/.webbusiness.activity.CloudWebBusinessActivity";
    static final String TASK_TITLE = "赚碎片兑云空间";

    /** 横幅中心点（参考 1264×2780 屏，ViewPager bounds [56,1099][1208,1335]）。 */
    static final int BANNER_X = 632;
    static final int BANNER_Y = 1217;

    /** 卡片标题所在的板块，按屏幕上从上到下排序。 */
    static final String[][] SECTIONS = {
            {"今日推荐"},
            {"连续2天体验应用"},
            {"先安装再注册"},
            {"日常任务"},
            {"更多"},
    };

    static final String ST_INSTALL = "安装";
    static final String ST_OPEN = "打开";
    static final String ST_CLAIM = "领取";
    static final String ST_RETRY = "重试";
    /** 「先安装再注册」板块：按钮是「去注册 / 立即注册」，旧版整个不认识，导致该板块一个都没做。 */
    static final String ST_REG = "去注册";

    /** 卡在安装/等待中的状态：不是可点的任务，但必须能认出卡片，别误判成"卡片没了"。 */
    static final String[] ST_BUSY = {"安装中", "下载中", "等待中", "明日领", "已领取", "已完成",
            "待领取", "注册中", "审核中"};

    /**
     * 顶部「今日推荐」那三个圆形快捷入口（抖音 / 同程 / 1688）。
     * 它们不是 android.widget.Button，是 TextView ＋ 一张圆形图标，旧版 collect() 完全看不到，
     * 所以「今日推荐」永远只做卡片里那几项 —— 用户说的"做了两个"就是这个区域。
     */
    static final String[] QUICK_WORDS = {"重新安装", "开始安装", "立即注册", "去注册", "去使用", "立即安装"};

    /** 卡片里出现这些文字就不是标题。 */
    static final String[] JUNK = {
            "隐私", "权限", "介绍", "已卸载", "已安装", "已领取", "+", "碎片", "安装", "打开", "领取",
            "立即", "去使用", "元", "天", "查看", "规则", "兑换", "今日", "任务",
    };

    static final class Item {
        Node btn;
        Node card;
        String state;
        String title;
        int section;
        int cx;
        int cy;
        /** 顶部「今日推荐」三个圆形快捷入口（不是 Button，坐标要往上偏）。 */
        boolean quick;
        /** 快捷位当前的原始文案，只用于日志展示（不能进 title，否则状态一变就对不上）。 */
        String word;

        @Override
        public String toString() {
            if (quick) {
                return "[" + state + "] " + title + "「" + word + "」(推荐位)";
            }
            return "[" + state + "] " + title + " (板块" + section + ")";
        }
    }

    private final Listener listener;
    private final int useSeconds;
    private final boolean doToday;
    private final boolean doMiddle;
    private final boolean doDaily;
    private final int maxTasks;
    private final int maxMinutes;
    private final boolean exitAtEnd;
    private final int installTimeoutSec;

    private volatile boolean stop;
    private int claimedCount;
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Set<String> finished = new HashSet<>();
    private long startedAt;
    private String lastLog = "";
    private int overlayLoops;
    private volatile long lastLogAt;
    private volatile String stepName = "启动";

    Engine(Listener l, int useSeconds, boolean doToday, boolean doMiddle, boolean doDaily,
           int maxTasks, int maxMinutes, boolean exitAtEnd, int installTimeoutSec) {
        this.listener = l;
        this.useSeconds = useSeconds;
        this.doToday = doToday;
        this.doMiddle = doMiddle;
        this.doDaily = doDaily;
        this.maxTasks = maxTasks;
        this.maxMinutes = maxMinutes;
        this.exitAtEnd = exitAtEnd;
        this.installTimeoutSec = Math.max(30, installTimeoutSec);
    }

    void requestStop() {
        stop = true;
    }

    void log(String msg) {
        if (msg == null || msg.equals(lastLog)) {
            return;
        }
        lastLog = msg;
        stepName = msg.length() > 24 ? msg.substring(0, 24) : msg;
        lastLogAt = System.currentTimeMillis();
        if (listener != null) {
            listener.onLog(msg);
        }
    }

    /** 卡住时每 20 秒报一次心跳，方便判断进度。 */
    private void startHeartbeat() {
        final Engine self = this;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stop) {
                    Shell.sleep(20000);
                    if (stop) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - lastLogAt;
                    if (idle > 20000) {
                        self.log("…仍在进行（" + stepName + "，已 " + idle / 1000 + "s 无新进展）");
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean timeUp() {
        return (System.currentTimeMillis() - startedAt) > maxMinutes * 60000L;
    }

    /**
     * 右下角「50G 限时特惠」浮窗猎手。
     *
     * 这个浮窗是任务页 H5 自己画的，页面每重新加载一次它就回来一次，而且刚好压住
     * 「抖音精选/软件商店」那两行的按钮 —— 点下去等于点浮窗（会跳付费页）。
     * 系统层没法"永久关闭"它（WebView 没开远程调试，注入不了 JS），所以用最便宜的方式盯：
     * 每 2.3 秒截一次图，只在右下角那块橙色区域取 4 个点，命中就点它的 ×（实测 1195,2365），
     * 一般 3 秒内就消失，全程不碰主流程的 dump。
     */
    private void startBubbleWatch() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final int[][] pts = {{1000, 2500}, {1060, 2520}, {1120, 2540}, {980, 2470}};
                int kills = 0;
                while (!stop) {
                    Shell.sleep(2300);
                    if (stop) {
                        return;
                    }
                    if (!isWeb(Shell.foregroundPkg())) {
                        continue;
                    }
                    if (!Shell.anyOrangeAt(pts)) {
                        continue;
                    }
                    tap(1195, 2365);
                    kills++;
                    log("浮窗又冒出来了，已关掉（第 " + kills + " 次）");
                    Shell.sleep(1000);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 主流程

    void run() {
        startedAt = System.currentTimeMillis();
        lastLogAt = startedAt;
        startHeartbeat();
        startBubbleWatch();
        // 趁 App 还在前台，先把常驻 root shell 拉起来
        Shell.ensureDaemon();
        boolean ok = false;
        String msg = "结束";
        try {
            log("root: " + (Shell.isRoot() ? "可用" : "不可用（请确认已授权）"));
            if (!ensureTaskPage()) {
                finish(false, "进不去任务页，请确认云服务已登录");
                return;
            }
            log("已进入任务页「" + TASK_TITLE + "」");
            int steps = 0;
            int idleRounds = 0;
            while (!stop && steps < 120) {
                steps++;
                if (timeUp()) {
                    msg = "超时（" + maxMinutes + " 分钟）";
                    break;
                }
                if (claimedCount >= maxTasks) {
                    msg = "已完成 " + claimedCount + " 个任务，达到上限";
                    break;
                }
                List<Node> nodes = dumpStable();
                if (nodes == null || !isTaskPage(nodes)) {
                    // 风控滑块会盖住页面，先认出来再去过它
                    if (Captcha.isShown(nodes) && Captcha.handle(this)) {
                        continue;
                    }
                    log("不在任务页，尝试恢复…");
                    if (!ensureTaskPage()) {
                        msg = "任务页丢失";
                        break;
                    }
                    continue;
                }
                if (Captcha.isShown(nodes)) {
                    Captcha.handle(this);
                    continue;
                }
                if (dismissOverlay(nodes) && overlayLoops++ < 3) {
                    // 刚关掉遮挡物，页面已经被点过一下了，重新抓一遍再挑，别用旧坐标
                    continue;
                }
                overlayLoops = 0;
                Item it = pick(nodes);
                if (it == null) {
                    // 当前屏没有可做的 → 从头把整页逐屏扫一遍再决定，
                    // 旧版这里只往下滚一次就空转，下半屏的任务（第二板块后两个、日常任务）永远看不到。
                    it = pickGlobal();
                    if (it == null) {
                        idleRounds++;
                        if (idleRounds >= 2) {
                            msg = "整页已没有可做的任务";
                            break;
                        }
                        log("整页扫完没有可做任务，等一下再看");
                        Shell.sleep(2500);
                        continue;
                    }
                }
                idleRounds = 0;
                log("处理 " + it);
                handle(it);
            }
            ok = true;
            msg = "完成：本次领取 " + claimedCount + " 次碎片（" + msg + "）";
            finish(ok, msg);
        } catch (Throwable t) {
            finish(false, "异常：" + t);
        }
    }

    private void finish(boolean ok, String msg) {
        if (listener != null) {
            listener.onFinish(ok, msg);
        }
        if (exitAtEnd) {
            Shell.sleep(1200);
            Shell.su("am force-stop " + CLOUD, 8000);
            Shell.sleep(300);
            Shell.su("input keyevent 3", 4000);
            Shell.sleep(300);
            Shell.su("am kill com.zcode.cloudfrag", 5000);
            Shell.sleep(200);
            Shell.su("am force-stop com.zcode.cloudfrag", 5000);
        }
    }

    // ------------------------------------------------------------------ 单个任务

    private void handle(Item it) {
        String key = it.title + "|" + it.state;
        Integer n = attempts.get(key);
        int c = n == null ? 0 : n;
        if (c >= (ST_INSTALL.equals(it.state) ? 2 : 2)) {
            log("跳过（已试过两次）：" + it.title);
            finished.add(it.title);
            return;
        }
        attempts.put(key, c + 1);

        // 风控滑块：先过它，再谈点任务
        if (Captcha.isShown(dumpStable())) {
            Captcha.handle(this);
        }

        if (ST_CLAIM.equals(it.state)) {
            claim(it);
            return;
        }
        if (ST_OPEN.equals(it.state)) {
            openAndReturn(it);
            return;
        }
        if (ST_REG.equals(it.state)) {
            // 「去注册 / 立即注册」：点进去是目标 App 的注册页，等一会儿再回来即可。
            // 旧版这一整类被 collect() 过滤掉，所以「先安装再注册」板块一个都没做。
            openAndReturn(it);
            return;
        }
        install(it);
        // 任务之间随机喘一口气，太快容易把风控勾出来
        Shell.sleep(1200 + (long) (Math.random() * 2000));
    }

    /**
     * 领取。真机上「点准了就真到账」（实测碎片 30→32），但点歪了页面也不报错，
     * 所以不能只看「领取按钮不见了」就记成功（那样会无限空转，本次运行已经踩过）。
     * 判定顺序：卡片从列表消失 = 领到；否则刷新页面再看，还挂着「领取」/退回「打开」都算没领到。
     */
    private void claim(Item it) {
        long t0 = System.currentTimeMillis();
        log("点「领取」：" + it.title);
        tap(it.cx, it.cy);
        Shell.sleep(1300);
        List<Node> nodes = dumpStable();
        if (Captcha.isShown(nodes)) {
            Captcha.handle(this);
            nodes = dumpStable();
        }
        if (dismissOverlay(nodes)) {
            nodes = dumpStable();
            Item again = find(nodes, it.title);
            if (again != null && ST_CLAIM.equals(again.state)) {
                log("有弹窗挡着，重新点「领取」");
                tap(again.cx, again.cy);
                Shell.sleep(1300);
                nodes = dumpStable();
            }
        }
        Item now = find(nodes, it.title);
        if (now == null) {
            markClaimed(it, t0);
            return;
        }
        if (!ST_CLAIM.equals(now.state)) {
            // 按钮变了但卡片还在：可能刚领完还没刷新，也可能点歪了，刷新一次确认
            log("领取后按钮变「" + now.state + "」，刷新确认");
            if (refreshPage()) {
                Item n2 = find(dumpStable(), it.title);
                if (n2 == null) {
                    markClaimed(it, t0);
                    return;
                }
                log("刷新后仍是「" + n2.state + "」，这次没领到：" + it.title);
                return;
            }
            log("刷新失败，领取结果未知：" + it.title);
            return;
        }
        // 还是「领取」：浮窗/弹窗可能还没清干净，再来一次
        log("领取没反应，重试一次");
        if (dismissOverlay(nodes)) {
            nodes = dumpStable();
        }
        Item retry = find(nodes, it.title);
        if (retry != null && ST_CLAIM.equals(retry.state)) {
            tap(retry.cx, retry.cy);
            Shell.sleep(1500);
            Item n3 = find(dumpStable(), it.title);
            if (n3 == null) {
                markClaimed(it, t0);
                return;
            }
        }
        log("领取失败：" + it.title);
    }

    private void markClaimed(Item it, long t0) {
        claimedCount++;
        log("领取成功：" + it.title + "（累计 " + claimedCount + "，"
                + ((System.currentTimeMillis() - t0) / 1000) + "s）");
        if (listener != null) {
            listener.onProgress(claimedCount);
        }
    }

    /**
     * 安装：点「安装」→ 等下载安装（包管理器差集）→ 确认按钮变「打开」。
     *
     * 2026-10-23 17:00 修的两个真 bug：
     *   a) 卡片按钮变成「安装中」后，find() 找不到（状态白名单里没有），被当成"卡片不在了"直接跳过 ——
     *      抖音精选/快手极速版就是这么被漏掉的。
     *   b) 进度条上的「下载安装」是**文字标签不是按钮**，旧代码会去点它，白点一下还可能弹出详情层。
     */
    private void install(Item it) {
        log("点「安装」：" + it.title);
        tap(it.cx, it.cy);
        Shell.sleep(1300);
        // 点完「安装 / 重新安装 / 开始安装」，真机上最常见的下一幕就是风控滑块。
        // 旧版完全没在这里看，滑块弹出来就把后面的 120 秒全耗在等待上（红果、网商银行都是这么死的）。
        if (Captcha.isShown(dumpStable())) {
            Captcha.handle(this);
            // 滑块一关，H5 会整页重绘，这时候 dump 出来只有 60~90 个节点（空壳），
            // 拿它去 find() 只会得到"按钮不见了"的假结论，然后白白超时一整个安装等待。
            Shell.sleep(1200);
            for (int i = 0; i < 6; i++) {
                if (dumpStable().size() >= 150) {
                    break;
                }
                Shell.sleep(900);
            }
        }
        handleInstallDialog();
        List<Node> first = dumpStable();
        if (dismissOverlay(first)) {
            // 弹窗/浮窗挡着 → 刚才那一下没落在按钮上，抓新坐标重点
            first = dumpStable();
            Item again = find(first, it.title);
            if (again != null && ST_INSTALL.equals(again.state)) {
                log("有遮挡，重新点「安装」");
                it = again;
                tap(again.cx, again.cy);
                Shell.sleep(1300);
                handleInstallDialog();
                first = dumpStable();
            }
        }
        Item now = find(first, it.title);
        if (now == null && first.size() < 150) {
            Shell.sleep(1200);
            first = dumpStable();
            now = find(first, it.title);
        }
        if (now != null && !ST_INSTALL.equals(now.state) && !ST_RETRY.equals(now.state)) {
            log("点完直接变「" + now.state + "」：" + it.title);
            if (!isBusy(now.state)) {
                return;
            }
        }
        if (now != null && ST_INSTALL.equals(now.state)) {
            // 点完还停在「安装」= 这一下没生效（多半点到卡片空白/弹层上了）
            // 主因是风控滑块把第一次点击吃掉了：滑块过了，但安装根本没触发。
            // 旧版只有在"检测到遮挡"时才重点，于是滑块这一路走完就干等到超时。
            log("点了没反应，重新点一次「安装」");
            dismissOverlay(first);
            Item n2 = find(dumpStable(), it.title);
            if (n2 != null && ST_INSTALL.equals(n2.state)) {
                tap(n2.cx, n2.cy);
                Shell.sleep(1400);
                if (Captcha.isShown(dumpStable())) {
                    Captcha.handle(this);
                    Shell.sleep(1500);
                }
                handleInstallDialog();
            }
        }

        // 2026-11-01 重写等待段。旧版拿「包管理器差集」当主要判据，真机上完全跑不通：
        // 手机后台随时在装/更新别的 App，每出现一个新包就当成"任务装完了"去刷新页面 ——
        // 实测 90 秒里连续刷了 7 次（zte.linkpro / mobileqq / chsiapp / filemanager…全是无关包），
        // 而刷新会**打断正在进行的下载**，于是任务 App 永远装不完，被打断后再点再打断。
        // 现在只认一个信号：按钮文字有没有从「安装」走到「安装中」。
        long t0 = System.currentTimeMillis();
        boolean didRefresh = false;
        // 只要出现过「安装中/下载中」，就说明下载真的起来了，值得继续等；
        // 一直停在「安装」说明点击根本没生效，再等就是白烧时间。
        boolean sawBusy = false;
        int tick = 0;
        String lastState = "";
        while (!stop && (System.currentTimeMillis() - t0) < installTimeoutSec * 1000L) {
            Shell.sleep(1500);
            long el = (System.currentTimeMillis() - t0) / 1000;
            tick++;
            if (tick % 6 == 0) {
                handleInstallDialog();
            }
            // 每 6 秒看一次按钮文字：安装中＝正在下载，别去刷新打断它
            if (el % 6 == 0) {
                List<Node> probe = dumpStable();
                if (Captcha.isShown(probe)) {
                    Captcha.handle(this);
                    continue;
                }
                // 节点太少是空壳页，不能据此判断"卡片消失"
                Item n4 = probe.size() >= 120 ? find(probe, it.title) : null;
                if (n4 != null) {
                    if (!n4.state.equals(lastState)) {
                        lastState = n4.state;
                        log("按钮状态：" + lastState + "（" + el + "s）");
                    }
                    if (isBusy(n4.state)) {
                        sawBusy = true;
                    }
                    if (!ST_INSTALL.equals(n4.state) && !ST_RETRY.equals(n4.state)
                            && !isBusy(n4.state)) {
                        log("安装完成，按钮已变「" + n4.state + "」：" + it.title);
                        return;
                    }
                }
            }
            // 25 秒还没出现「安装中」→ 点击被拦了（滑块/弹层），刷一次页面再看
            if (!didRefresh && el >= 25 && !sawBusy) {
                didRefresh = true;
                log("25 秒还没出现「安装中」，刷新页面看结果");
                if (!refreshPage() && !ensureTaskPage()) {
                    log("刷新失败，跳过：" + it.title);
                    return;
                }
                List<Node> after = dumpStable();
                Item n5 = after.size() >= 120 ? find(after, it.title) : null;
                if (n5 == null) {
                    log("刷新后抓不到卡片（页面未就绪），按未完成跳过：" + it.title);
                } else if (!ST_INSTALL.equals(n5.state) && !ST_RETRY.equals(n5.state)
                        && !isBusy(n5.state)) {
                    log("安装完成（刷新后）：「" + n5.state + "」" + it.title);
                    return;
                }
            }
            // 刷新过一次仍然没开始下载 → 别把整个超时烧在这一个任务上
            if (didRefresh && el >= 60 && !sawBusy) {
                log("刷新后依然没开始下载，先跳过（后面还有机会重试）：" + it.title);
                return;
            }
        }
        log("等待安装超时（" + installTimeoutSec + "s）：" + it.title);
    }

    private boolean isBusy(String state) {
        if (state == null) {
            return false;
        }
        for (String b : ST_BUSY) {
            if (state.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** 「打开」和「去注册」处理路径一样：点进去 → 等 → 回来。 */
    private boolean isOpenLike(String state) {
        return ST_OPEN.equals(state) || ST_REG.equals(state);
    }

    /**
     * 把页面上五花八门的按钮文案归一成 5 种状态。
     *
     * 旧版 collect() 是拿原始文字直接跟常量的 equals 比，于是：
     *   「重新安装 / 开始安装」→ 认不出，直接丢（顶部三个推荐位就是这么全灭的）；
     *   「立即注册 / 去注册」  → 认不出，整个「先安装再注册」板块 0 处理；
     *   「去使用 / 去看看」    → 认不出，做完的卡片永远停在旧状态。
     * 归一之后这五类都能落到 ST_INSTALL / ST_REG / ST_OPEN / ST_CLAIM / ST_RETRY 上。
     */
    private String normState(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.length() == 0) {
            return "";
        }
        if (isBusy(t)) {
            return t;              // 安装中/明日领 等原样保留，别归一成可点状态
        }
        if (t.contains("注册")) {
            return ST_REG;
        }
        if (t.contains("安装")) {
            return ST_INSTALL;
        }
        if (t.contains("领取")) {
            return ST_CLAIM;
        }
        if (t.contains("重试")) {
            return ST_RETRY;
        }
        if ("打开".equals(t) || t.contains("去使用") || t.contains("去看看")) {
            return ST_OPEN;
        }
        return t;
    }

    /** BACK 回首页 → 拨回第 1 页横幅 → 重进任务页，强制 H5 重新拉数据。 */
    private boolean refreshPage() {
        Shell.key(4);
        Shell.sleep(900);
        String fg = fgRetry();
        if (isWeb(fg)) {
            Shell.key(4);
            Shell.sleep(800);
            fg = fgRetry();
        }
        if (!CLOUD.equals(fg)) {
            Shell.amStart(LAUNCHER);
            Shell.sleep(1500);
            fg = fgRetry();
        }
        if (!CLOUD.equals(fg)) {
            log("刷新失败：没回到云服务首页（当前 " + (fg.length() == 0 ? "取不到前台" : fg) + "）");
            return false;
        }
        log("刷新页面（首页 → 横幅重进）");
        return enterFromHome(3);
    }

    /**
     * dumpsys window 偶尔取不到前台（切应用瞬间），空串别当成「不是云服务」直接放弃。
     */
    private String fgRetry() {
        for (int i = 0; i < 3; i++) {
            String fg = Shell.foregroundPkg();
            if (fg != null && fg.length() > 0) {
                return fg;
            }
            Shell.sleep(450);
        }
        return "";
    }

    private void openAndReturn(Item it) {
        log("点「打开」：" + it.title + "，准备等 " + useSeconds + " 秒");
        tap(it.cx, it.cy);
        Shell.sleep(1700);
        String fg = fgRetry();
        if (CLOUD.equals(fg) || isWeb(fg)) {
            // 没切走：多半是浮窗/弹窗把按钮挡住了，清掉重点一次
            log("没切到应用，检查遮挡");
            List<Node> nodes = dumpStable();
            if (dismissOverlay(nodes)) {
                nodes = dumpStable();
                Item again = find(nodes, it.title);
                if (again != null && isOpenLike(again.state)) {
                    log("重新点「打开」");
                    tap(again.cx, again.cy);
                    Shell.sleep(1900);
                }
            }
            fg = Shell.foregroundPkg();
        }
        if (CLOUD.equals(fg) || isWeb(fg)) {
            log("还是没打开应用（仍在 " + fg + "），这轮跳过，不白等");
            return;
        }
        log("已切到 " + fg);
        handleInstallDialog();
        Shell.sleep(useSeconds * 1000L);
        log("时间到，回云服务");
        if (!returnToTaskPage()) {
            log("快速返回失败，走首页重进");
            ensureTaskPage();
            return;
        }
        Shell.sleep(1200);
        List<Node> nodes = dumpStable();
        Item now = find(nodes, it.title);
        if (now != null && isOpenLike(now.state)) {
            // 服务端还没记上使用时长：刷新一次页面再看，别直接再开一次浪费一整轮
            log("回来还是「打开」，刷新页面看时长");
            if (refreshPage()) {
                Item n2 = find(dumpStable(), it.title);
                if (n2 == null) {
                    log("任务卡片已消失（可能已领）");
                } else if (!isOpenLike(n2.state)) {
                    log("时长已记上，按钮变「" + n2.state + "」：" + it.title);
                } else {
                    log("刷新后仍是「打开」：" + it.title);
                }
            }
        }
    }

    /** 系统安装/权限对话框：有就点「安装」「继续」「允许」。 */
    private void handleInstallDialog() {
        String fg = Shell.foregroundPkg();
        if (fg == null || fg.isEmpty()) {
            return;
        }
        if (fg.startsWith("com.heytap.cloud") || fg.startsWith("com.android.systemui")) {
            return;
        }
        String xml = Shell.dumpXml(15000);
        List<Node> nodes = Ui.parse(xml);
        for (Node n : nodes) {
            if (!n.onScreen() || !n.clickable) {
                continue;
            }
            String t = n.text;
            if (t == null) {
                continue;
            }
            if (t.equals("安装") || t.equals("继续安装") || t.equals("继续") || t.equals("允许")
                    || t.equals("确定") || t.equals("授权") || t.equals("同意")) {
                log("对话框点「" + t + "」");
                tap(n.cx(), n.cy());
                Shell.sleep(1200);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 判定 & 选择

    private boolean isTaskPage(List<Node> nodes) {
        if (nodes == null) {
            return false;
        }
        for (Node n : nodes) {
            if (n.has(TASK_TITLE) || n.has("我的碎片")) {
                return true;
            }
        }
        return false;
    }

    private Item pick(List<Node> nodes) {
        List<Item> all = collect(nodes);
        Item best = null;
        int bestScore = -1;
        for (Item it : all) {
            if (finished.contains(it.title) && !ST_CLAIM.equals(it.state)) {
                continue;
            }
            // 顶部的推荐位属于「今日推荐」，但不跟着板块开关走：
            // 用户即使没勾「今日推荐」，这三个也照样要做（这是页面上最显眼的三个任务）。
            if (!it.quick && !sectionEnabled(it.section)) {
                continue;
            }
            // 安装中/等待中这类不是任务按钮，别去点
            if (isBusy(it.state)) {
                continue;
            }
            Integer n = attempts.get(it.title + "|" + it.state);
            if (n != null && n >= 2) {
                continue;
            }
            int score;
            if (ST_CLAIM.equals(it.state)) {
                score = 300;
            } else if (ST_OPEN.equals(it.state)) {
                score = 200;
            } else if (ST_REG.equals(it.state)) {
                score = 150;      // 「去注册」只是打开一次，成本最低，排在安装前面
            } else if (ST_INSTALL.equals(it.state) || ST_RETRY.equals(it.state)) {
                score = 100;
            } else {
                continue;
            }
            // 按板块顺序：中间板块优先于日常任务
            score += (10 - it.section) * 5;
            if (it.quick) {
                score += 4;       // 推荐位在首屏，点起来最快，先清掉
            }
            if (score > bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }

    private boolean sectionEnabled(int section) {
        if (section == 0) {
            return doToday;
        }
        if (section == 1 || section == 2) {
            return doMiddle;
        }
        if (section == 3) {
            return doDaily;
        }
        return false;
    }

    /**
     * 从头到尾把整页过一遍，返回第一个能做的任务。
     *
     * 页面实测约 2.5 屏，任务散在「今日推荐 / 连续2天体验 / 先安装再注册 / 日常任务」四个板块里。
     * 旧版只在当前屏找、没有就 scrollDown()（两段共约 1400px，一跨就是一屏多），
     * 再没有就 idle++，三次就 break —— 这就是「第二板块只做了两个、日常任务只做了一个」的成因。
     */
    private Item pickGlobal() {
        scrollTop();
        List<Node> nodes = dumpStable();
        if (dismissOverlay(nodes)) {
            nodes = dumpStable();
        }
        Item it = pick(nodes);
        if (it != null) {
            return it;
        }
        List<String> prev = signature(nodes);
        for (int page = 0; page < 8; page++) {
            scrollPage();
            nodes = dumpStable();
            if (dismissOverlay(nodes)) {
                nodes = dumpStable();
            }
            it = pick(nodes);
            if (it != null) {
                return it;
            }
            List<String> cur = signature(nodes);
            if (cur.equals(prev)) {
                break;        // 滚不动了 = 到底
            }
            prev = cur;
        }
        return null;
    }

    /**
     * 顶部三个圆形推荐位的槽位号（从左到右 1/2/3）。
     * 不能直接用 cx/100 当 key —— 中间某个做完消失后，剩下的会重排，cx 一挪 key 就变，
     * 于是同一个任务被当成新任务反复重试。按固定区间切三段就稳得住。
     */
    private int quickSlot(int cx) {
        if (cx < 780) {
            return 1;
        }
        if (cx < 1000) {
            return 2;
        }
        return 3;
    }

    /** 整页指纹，用来判断"还能不能往下滚"，比舔屏距可靠。 */
    private List<String> signature(List<Node> nodes) {
        List<String> s = new ArrayList<>();
        for (Item it : collect(nodes)) {
            s.add(it.title + "|" + it.state);
        }
        Collections.sort(s);
        return s;
    }

    /** 单次滚动约 950px：比旧版 scrollDown 的两段 1400px 细，不会跳过一整屏。 */
    private void scrollPage() {
        Shell.swipe(632, 2100, 632, 1150, 450);
        Shell.sleep(650);
    }

    private void scrollTop() {
        for (int i = 0; i < 2; i++) {
            Shell.swipe(632, 900, 632, 2300, 300);
            Shell.sleep(500);
        }
    }

    private void scrollDown() {
        Shell.swipe(632, 1600, 632, 700, 350);
        Shell.sleep(700);
        Shell.swipe(632, 1600, 632, 800, 350);
        Shell.sleep(700);
    }

    /**
     * 关掉遮挡物。真机上实测到两类，都会把「打开/领取/安装」按钮挡住：
     *  1) 右下角「50G 限时特惠」浮窗：文字节点在按钮下方，但视觉上盖住按钮一半，
     *     不关掉的话点按钮会变成点浮窗（以前就是这样白等一整个安装超时）。
     *     关闭 × 在文字节点右上方约 (x2+12, y1-190)。
     *  2) 应用详情底部弹窗（隐私/权限/介绍 + 关闭）：点卡片空白处会弹出来。
     * 另外还有「云空间限时特惠」付费弹窗，按「立即抢购」上方的 Image 找关闭按钮。
     */
    private boolean dismissOverlay(List<Node> nodes) {
        if (nodes == null) {
            return false;
        }
        // 1) 付费特惠弹窗
        for (Node n : nodes) {
            if (n.has("立即抢购")) {
                Node close = null;
                for (Node c : nodes) {
                    if (!c.onScreen() || !"android.widget.Image".equals(c.cls)) {
                        continue;
                    }
                    if (c.y1 <= n.y2) {
                        continue;
                    }
                    int w = c.w();
                    int h = c.h();
                    if (w < 50 || w > 220 || h < 50 || h > 220) {
                        continue;
                    }
                    if (c.cx() < 380 || c.cx() > 900) {
                        continue;
                    }
                    if (close == null || (w * h) > (close.w() * close.h())) {
                        close = c;
                    }
                }
                int x = close != null ? close.cx() : BANNER_X;
                int y = close != null ? close.cy() : Math.min(n.y2 + 460, 2700);
                log("关掉特惠弹窗 (" + x + "," + y + ")");
                tap(x, y);
                Shell.sleep(900);
                return true;
            }
        }
        // 2) 应用详情底部弹窗
        Node closeBtn = null;
        for (Node n : nodes) {
            if (n.onScreen() && "android.widget.Button".equals(n.cls)
                    && "关闭".equals(n.text == null ? "" : n.text.trim())) {
                closeBtn = n;
                break;
            }
        }
        if (closeBtn != null) {
            log("关掉底部弹窗「关闭」(" + closeBtn.cx() + "," + closeBtn.cy() + ")");
            tap(closeBtn.cx(), closeBtn.cy());
            Shell.sleep(900);
            return true;
        }
        // 3) 右下角特惠浮窗：出现就关，不再判断有没有压住按钮
        Node bubble = null;
        for (Node n : nodes) {
            if (n.has("限时特惠") && n.onScreen()) {
                bubble = n;
                break;
            }
        }
        if (bubble != null) {
            int x = Math.min(bubble.x2 + 12, 1240);
            int y = Math.max(bubble.y1 - 190, 240);
            log("关掉右下角浮窗 (" + x + "," + y + ")");
            tap(x, y);
            Shell.sleep(800);
            return true;
        }
        return false;
    }

    private Item find(List<Node> nodes, String title) {
        for (Item it : collect(nodes)) {
            if (title.equals(it.title)) {
                return it;
            }
        }
        return null;
    }

    /** 把按钮和它所在的卡片、卡片标题、板块都配起来。 */
    private List<Item> collect(List<Node> nodes) {
        List<Item> out = new ArrayList<>();
        if (nodes == null) {
            return out;
        }
        List<Node> buttons = new ArrayList<>();
        for (Node n : nodes) {
            if (n.onScreen() && "android.widget.Button".equals(n.cls)) {
                String st = normState(n.text);
                if (ST_INSTALL.equals(st) || ST_OPEN.equals(st) || ST_CLAIM.equals(st)
                        || ST_RETRY.equals(st) || ST_REG.equals(st) || isBusy(st)) {
                    buttons.add(n);
                }
            }
        }
        List<Node> headers = new ArrayList<>();
        for (Node n : nodes) {
            if (!n.isText() || n.text == null || n.text.length() < 2) {
                continue;
            }
            for (int s = 0; s < SECTIONS.length; s++) {
                String h = SECTIONS[s][0];
                if (n.has(h)) {
                    headers.add(n);
                    break;
                }
            }
        }
        for (Node b : buttons) {
            Item it = new Item();
            it.btn = b;
            it.state = normState(b.text);
            it.card = findCard(b);
            it.title = it.card == null ? "" : findTitle(it.card);
            if (it.title.length() == 0) {
                continue;
            }
            it.section = sectionOf(it.card != null ? it.card : b, headers);
            it.cx = Math.max(b.x1 + 6, Math.min(b.x2 - 6, b.cx()));
            it.cy = Math.max(b.y1 + 6, Math.min(b.y2 - 6, b.cy()));
            out.add(it);
        }

        // 顶部「今日推荐」三个圆形快捷入口（抖音 / 同程 / 1688）。
        // 它们不是 Button：结构是「圆形图标 + 正下方一行 TextView」，文字实测
        // bounds 形如 【548,588】【763,640】，图标中心 ≈ (文字中心x, 文字y1-190)。
        // 旧版只扫 android.widget.Button，这三个从头到尾没被点过一次。
        for (Node n : nodes) {
            if (!n.onScreen() || !n.isText() || n.text == null) {
                continue;
            }
            if (n.y1 < 300 || n.y1 > 1080 || n.w() < 60) {
                continue;
            }
            String t = n.text.trim();
            boolean hit = false;
            for (String q : QUICK_WORDS) {
                if (t.equals(q)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            Item it = new Item();
            it.btn = n;
            it.quick = true;
            it.card = findCard(n);
            it.state = normState(t);
            // title 只用位置做 key：快捷位的文案会随状态变（重新安装 → 安装中 → 明日领），
            // 一旦把文案写进 title，状态一变 find() 就对不上了。
            it.title = "推荐位" + quickSlot(n.cx());
            it.word = t;
            it.section = 0;
            // 点击点必须是「圆形图标的圆心」，不能拿文字坐标硬减一个常数 ——
            // 实测文字 y1=556 时图标区是 [381,553]，圆心 467；按老经验减 190 会落到
            // 381 上方的空白处，点下去啥也不发生（这就是「开始安装」一直无效的真正原因）。
            // 稳妥做法：往上找第一个高度 >=150 的容器（它包住图标），再取「容器顶→文字顶」的中点。
            Node box = n.parent;
            int guard = 0;
            while (box != null && guard++ < 6 && box.h() < 150) {
                box = box.parent;
            }
            if (box != null && box.h() >= 150 && box.y1 < n.y1) {
                it.cx = box.cx();
                it.cy = (box.y1 + n.y1) / 2;
            } else {
                it.cx = n.cx();
                it.cy = Math.max(220, n.y1 - 90);
            }
            out.add(it);
        }
        return out;
    }

    /** 往上找卡片容器：宽度够、高度像卡片（200~500）。 */
    private Node findCard(Node btn) {
        Node n = btn.parent;
        int guard = 0;
        while (n != null && guard++ < 8) {
            if (n.w() > 900 && n.h() >= 180 && n.h() <= 520) {
                return n;
            }
            n = n.parent;
        }
        return btn.parent;
    }

    /** 卡片里第一个「像标题」的文本。 */
    private String findTitle(Node card) {
        for (Node k : card.kids) {
            String t = titleOf(k);
            if (t != null) {
                return t;
            }
        }
        return "";
    }

    private String titleOf(Node n) {
        if (!n.isText() || n.text == null) {
            for (Node k : n.kids) {
                String t = titleOf(k);
                if (t != null) {
                    return t;
                }
            }
            return null;
        }
        String t = n.text.trim();
        if (t.length() < 2 || t.length() > 24) {
            return null;
        }
        if (isJunk(t)) {
            return null;
        }
        // 跳过纯数字/纯符号
        if (t.matches("^[+\\s\\d\\uFF0B]*$")) {
            return null;
        }
        return t;
    }

    private boolean isJunk(String t) {
        for (String j : JUNK) {
            if (t.contains(j)) {
                return true;
            }
        }
        return false;
    }

    private int sectionOf(Node card, List<Node> headers) {
        int sec = 4;
        int bestY = -1;
        for (Node h : headers) {
            if (h.y1 <= card.y1 && h.y1 > bestY) {
                bestY = h.y1;
                for (int s = 0; s < SECTIONS.length; s++) {
                    if (h.has(SECTIONS[s][0])) {
                        sec = s;
                        break;
                    }
                }
            }
        }
        return sec;
    }

    // ------------------------------------------------------------------ 页面流转

    /** 抓界面：WebView 第一次 dump 常常只有空壳（5354 字节），节点少就重抓。 */
    List<Node> dumpStable() {
        long t0 = System.currentTimeMillis();
        List<Node> nodes = Ui.parse(Shell.dumpXml(6000));
        int tries = 1;
        while (nodes.size() < 60 && tries < 3) {
            Shell.sleep(1000);
            nodes = Ui.parse(Shell.dumpXml(6000));
            tries++;
        }
        Log.add("dump#" + tries + " " + (System.currentTimeMillis() - t0) + "ms 节点" + nodes.size());
        return nodes;
    }

    /**
     * 最快的一条返回路径：直接把 H5 任务栈 reorder 到前台（实测约 1~2 秒，
     * 页面 DOM/滚动位置原样保留，比 HOME + 拉起首页 + BACK 快 4 倍）。
     * 该 Activity 未导出，只有 root 能 start；若它的任务栈已经不在了（被 BACK 清掉），
     * 这里会拉起一个空白页，返回 false，交给首页兜底。
     */
    private boolean toTaskPageFast() {
        long t0 = System.currentTimeMillis();
        String out = Shell.amStartReorderOut(WEB);
        if (out != null) {
            out = out.replace("\n", " ").trim();
            if (out.length() > 100) {
                out = out.substring(0, 100);
            }
            Log.add("reorder 输出: " + out);
        }
        int blank = 0;
        for (int i = 0; i < 5; i++) {
            Shell.sleep(700);
            String fg = Shell.foregroundPkg();
            if (!isWeb(fg)) {
                continue;
            }
            List<Node> nodes = dumpStable();
            if (isTaskPage(nodes)) {
                log("reorder 直接拉回任务页（" + (System.currentTimeMillis() - t0) + "ms）");
                return true;
            }
            if (nodes.size() < 30) {
                blank++;
            }
        }
        if (blank > 0) {
            log("reorder 起来的是空白页，改用首页进");
            Shell.key(4);
        }
        return false;
    }

    private boolean ensureTaskPage() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String fg = fgRetry();
            if (isWeb(fg)) {
                log("当前是 H5 页面，等它渲染");
                if (isTaskPage(dumpStable())) {
                    return true;
                }
                log("不是任务页，退回首页");
                Shell.key(4);
                Shell.sleep(900);
                fg = Shell.foregroundPkg();
            } else if (CLOUD.equals(fg)) {
                if (isTaskPage(dumpStable())) {
                    return true;
                }
            } else if (!fg.isEmpty() && !fg.startsWith("com.zcode.cloudfrag")) {
                log("当前在 " + fg + "，先把任务栈拉回来");
                if (toTaskPageFast()) {
                    return true;
                }
                Shell.key(3);
                Shell.sleep(600);
                fg = Shell.foregroundPkg();
            }
            if (CLOUD.equals(fg)) {
                log("从首页横幅进任务页（第 " + attempt + " 次）");
                if (enterFromHome(2)) {
                    return true;
                }
            }
            log("冷启动云服务（第 " + attempt + " 次）");
            Shell.forceStop(CLOUD);
            Shell.sleep(500);
            Shell.amStart(LAUNCHER);
            Shell.sleep(2300);
            if (enterFromHome(2)) {
                return true;
            }
        }
        return false;
    }

    /** 假定现在在云服务首页：拨回第 1 页横幅 → 点它 → 校验是不是任务页。 */
    private boolean enterFromHome(int tries) {
        for (int i = 0; i < tries; i++) {
            List<Node> nodes = dumpStable();
            if (isTaskPage(nodes)) {
                return true;
            }
            String fg = Shell.foregroundPkg();
            if (isWeb(fg)) {
                Shell.key(4);
                Shell.sleep(900);
                continue;
            }
            // 别只信前台包名：切应用瞬间 dumpsys 会返回空串，用界面特征兜底
            if (!CLOUD.equals(fg) && !isHome(nodes)) {
                Shell.amStart(LAUNCHER);
                Shell.sleep(1500);
                continue;
            }
            bannerFirstPage();
            tapBanner(nodes);
            if (waitTaskPage(2)) {
                return true;
            }
            // 落到别的 H5 页（学生认证/套餐）→ 退回首页再来
            Shell.key(4);
            Shell.sleep(900);
        }
        return false;
    }

    /** 横幅是 2 页轮播，只有第 1 页是「做任务免费领云空间」，先右滑拨回第 1 页。 */
    private void bannerFirstPage() {
        for (int i = 0; i < 2; i++) {
            Shell.swipe(200, BANNER_Y, 1000, BANNER_Y, 240);
            Shell.sleep(260);
        }
    }

    /** 反复抓几次，等任务页渲染出来（H5 第一次 dump 常常只有空壳）。 */
    private boolean waitTaskPage(int times) {
        for (int i = 0; i < times; i++) {
            Shell.sleep(1300);
            if (isTaskPage(dumpStable())) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeb(String fg) {
        return fg != null && fg.contains("webbusiness");
    }

    /** 云服务首页特征。 */
    private boolean isHome(List<Node> nodes) {
        if (nodes == null) {
            return false;
        }
        for (Node n : nodes) {
            if (n.has("领取权益") || n.has("我的云空间") || n.has("去整理")) {
                return true;
            }
        }
        return false;
    }

    /** 点首页轮播横幅；坐标必须落在横幅区域，别误点成别的卡片。 */
    private boolean tapBanner(List<Node> nodes) {
        if (nodes != null) {
            for (Node n : nodes) {
                String cls = n.cls == null ? "" : n.cls;
                if (cls.contains("ViewPager") && n.onScreen()
                        && n.w() > 900 && n.h() >= 120 && n.h() <= 420
                        && n.y1 > 600 && n.y2 < 1700) {
                    log("点横幅 (" + n.cx() + "," + n.cy() + ")");
                    tap(n.cx(), n.cy());
                    return true;
                }
            }
        }
        log("按固定坐标点横幅 (" + BANNER_X + "," + BANNER_Y + ")");
        tap(BANNER_X, BANNER_Y);
        return true;
    }

    /** 回到 H5 任务页：BACK → reorder → 首页兜底，三级递进。 */
    private boolean returnToTaskPage() {
        // 1) BACK 最便宜：多数 App 一次就退回 H5
        Shell.key(4);
        Shell.sleep(800);
        String fg = Shell.foregroundPkg();
        if (isWeb(fg)) {
            if (waitTaskPage(2)) {
                log("BACK 直接回到任务页");
                return true;
            }
        }
        // 2) 把这套 H5 栈拽回前台
        if (toTaskPageFast()) {
            return true;
        }
        // 3) 兜底：HOME → 首页 → 横幅
        log("快速返回都失败，改走首页");
        Shell.key(3);
        Shell.sleep(500);
        Shell.amStart(LAUNCHER);
        Shell.sleep(1800);
        return enterFromHome(2);
    }

    void tap(int x, int y) {
        Shell.tap(x, y);
    }
}

package com.zcode.cloudfrag;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** 主界面：一堆开关 + 日志。 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etSeconds;
    private EditText etMaxTasks;
    private EditText etMaxMinutes;
    private EditText etInstallWait;
    private CheckBox cbToday;
    private CheckBox cbMiddle;
    private CheckBox cbDaily;
    private CheckBox cbExit;
    private TextView logView;
    private ScrollView logScroll;
    private Button btnStart;
    private Button btnStop;
    private TextView status;

    private String lastLog = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.setFile(new java.io.File(getFilesDir(), "cf_log.txt"));
        Shell.setDir(getFilesDir().getAbsolutePath());
        setContentView(buildUi());
        Prefs p = Prefs.load(this);
        etSeconds.setText(String.valueOf(p.useSeconds));
        etMaxTasks.setText(String.valueOf(p.maxTasks));
        etMaxMinutes.setText(String.valueOf(p.maxMinutes));
        etInstallWait.setText(String.valueOf(p.installTimeout));
        cbToday.setChecked(p.doToday);
        cbMiddle.setChecked(p.doMiddle);
        cbDaily.setChecked(p.doDaily);
        cbExit.setChecked(p.exitAtEnd);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            String t = Log.text();
            if (!t.equals(lastLog)) {
                lastLog = t;
                logView.setText(t);
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
            boolean running = Log.isRunning();
            btnStart.setEnabled(!running);
            btnStop.setEnabled(running);
            status.setText(running ? "运行中…" : "空闲");
            ui.postDelayed(this, 700);
        }
    };

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("云服务领碎片助手");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setText("空闲");
        status.setTextSize(13);
        root.addView(status);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setText("流程：云服务 → 做任务免费领云空间 → 安装 → 打开等 16 秒 → 回云服务 → 领取。\n"
                + "安装完成靠包管理器差集判定，一装完立刻刷新页面，不再干等。\n"
                + "右下角「限时特惠」浮窗由后台线程每 2 秒盯一次，冒出来就点掉。\n"
                + "需要 root。跑的时候别动手机。");
        root.addView(hint);

        etSeconds = addNumber(root, "打开后等待秒数", "16");
        etMaxTasks = addNumber(root, "最多领取次数", "12");
        etMaxMinutes = addNumber(root, "最长运行分钟", "40");
        etInstallWait = addNumber(root, "安装最长等待秒", "180");

        cbToday = addCheck(root, "也做「今日推荐」板块（默认不做）");
        cbMiddle = addCheck(root, "做中间板块（连续2天体验 / 先安装再注册）");
        cbDaily = addCheck(root, "做「日常任务」板块");
        cbExit = addCheck(root, "全部完成后自动退出本 App");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);

        btnStart = new Button(this);
        btnStart.setText("开始自动领取");
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });
        row.addView(btnStart, new LinearLayout.LayoutParams(0, -2, 1f));

        btnStop = new Button(this);
        btnStop.setText("停止");
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoService.stop(MainActivity.this);
                Log.add("已请求停止");
            }
        });
        row.addView(btnStop, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);

        Button self = new Button(this);
        self.setText("自检");
        self.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.add("root: " + (Shell.isRoot() ? "OK" : "不可用"));
                        Log.add("当前前台: " + Shell.foregroundPkg());
                        List<Ui.Node> nodes = Ui.parse(Shell.dumpXml(20000));
                        Log.add("界面节点数: " + nodes.size()
                                + (hasText(nodes, Engine.TASK_TITLE) ? "（在任务页）" : ""));
                    }
                }).start();
            }
        });
        row2.addView(self, new LinearLayout.LayoutParams(0, -2, 1f));

        Button capT = new Button(this);
        capT.setText("滑块自检");
        capT.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.add("—— 滑块识别自检（只识别、不拖动）——");
                        for (String line : Captcha.selfTest().split("\n")) {
                            Log.add(line);
                        }
                    }
                }).start();
            }
        });
        row2.addView(capT, new LinearLayout.LayoutParams(0, -2, 1f));

        Button dump = new Button(this);
        dump.setText("导出界面");
        dump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String xml = Shell.dumpXml(25000);
                        StringBuilder sb = new StringBuilder();
                        if (xml == null) {
                            sb.append("抓不到界面（dump 为空）");
                        } else {
                            for (Ui.Node n : Ui.parse(xml)) {
                                if (n.isButton() || n.has("碎片") || n.has("任务")) {
                                    sb.append('[').append(n.text).append("] ").append(n.x1).append(',')
                                            .append(n.y1).append('-').append(n.x2).append(',').append(n.y2)
                                            .append('\n');
                                }
                            }
                        }
                        Log.add("---- 界面摘要 ----");
                        Log.add(sb.toString().trim());
                    }
                }).start();
            }
        });
        row2.addView(dump, new LinearLayout.LayoutParams(0, -2, 1f));

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.clear();
            }
        });
        row2.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));

        // 导出日志：root 拷到 /sdcard/Download，手机连电脑直接拿走
        Button export = new Button(this);
        export.setText("导出日志到 Download");
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String name = "cf_log_" + new java.text.SimpleDateFormat("MMdd_HHmm",
                                java.util.Locale.US).format(new java.util.Date()) + ".txt";
                        String src = getFilesDir().getAbsolutePath() + "/cf_log.txt";
                        String dst = "/sdcard/Download/" + name;
                        String out = Shell.su("cp " + src + " " + dst + " && chmod 666 " + dst
                                + " && echo OK", 20000);
                        final String msg = out != null && out.contains("OK")
                                ? "已导出：Download/" + name : "导出失败，检查 root";
                        Log.add(msg);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(MainActivity.this, msg,
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(export);

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(280));
        lp.topMargin = dp(8);
        root.addView(logScroll, lp);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);

        return scroll;
    }

    private boolean hasText(List<Ui.Node> nodes, String t) {
        for (Ui.Node n : nodes) {
            if (n.has(t)) {
                return true;
            }
        }
        return false;
    }

    private EditText addNumber(LinearLayout root, String label, String def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(def);
        et.setTextSize(13);
        row.addView(et, new LinearLayout.LayoutParams(dp(90), -2));
        return et;
    }

    private CheckBox addCheck(LinearLayout root, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(13);
        root.addView(cb);
        return cb;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ---------------------------------------------------------------- 启动

    private void start() {
        Prefs p = new Prefs();
        p.useSeconds = num(etSeconds, 16, 5, 120);
        p.maxTasks = num(etMaxTasks, 12, 1, 40);
        p.maxMinutes = num(etMaxMinutes, 40, 5, 180);
        p.installTimeout = num(etInstallWait, 180, 30, 600);
        p.doToday = cbToday.isChecked();
        p.doMiddle = cbMiddle.isChecked();
        p.doDaily = cbDaily.isChecked();
        p.exitAtEnd = cbExit.isChecked();
        p.save(this);
        etSeconds.setText(String.valueOf(p.useSeconds));
        etMaxTasks.setText(String.valueOf(p.maxTasks));
        etMaxMinutes.setText(String.valueOf(p.maxMinutes));
        etInstallWait.setText(String.valueOf(p.installTimeout));
        Log.clear();
        Log.add("配置：等待 " + p.useSeconds + "s，最多 " + p.maxTasks + " 次，最长 " + p.maxMinutes
                + " 分钟，安装上限 " + p.installTimeout + "s；今日推荐=" + p.doToday + " 中间=" + p.doMiddle
                + " 日常=" + p.doDaily);
        AutoService.start(this);
    }

    private int num(EditText et, int def, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable t) {
            v = def;
        }
        return Math.max(min, Math.min(max, v));
    }
}

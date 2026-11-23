package com.zcode.cloudfrag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** 前台服务：保证息屏/切到别的前台时脚本不被掐。 */
public class AutoService extends Service implements Engine.Listener {

    static final String CHANNEL = "cloudfrag";
    static final int NOTI_ID = 1001;

    private Engine engine;
    private Thread worker;
    private PowerManager.WakeLock lock;
    private android.view.View overlay;

    /** 1x1 透明悬浮窗：带可见悬浮窗的进程不会被系统"速冻"，脚本才能一直跑。 */
    private void addOverlay() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            overlay = new android.view.View(this);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    2, 2,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(overlay, lp);
            Log.add("悬浮窗已挂上（防速冻）");
        } catch (Throwable t) {
            Log.add("悬浮窗失败：" + t);
        }
    }

    private void removeOverlay() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null && overlay != null) {
                wm.removeView(overlay);
            }
        } catch (Throwable ignored) {
        }
        overlay = null;
    }

    static void start(Context c) {
        Intent i = new Intent(c, AutoService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AutoService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.setFile(new java.io.File(getFilesDir(), "cf_log.txt"));
        Shell.setDir(getFilesDir().getAbsolutePath());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;
        }
        startForeground(NOTI_ID, buildNotification("准备中…"));
        addOverlay();
        Prefs p = Prefs.load(this);
        if (p.keepScreenOn) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "cloudfrag:run");
                try {
                    lock.acquire(45 * 60 * 1000L);
                } catch (Throwable ignored) {
                }
            }
        }
        Log.setRunning(true);
        Log.add("—— 开始运行 ——");
        engine = new Engine(this, p.useSeconds, p.doToday, p.doMiddle, p.doDaily,
                p.maxTasks, p.maxMinutes, p.exitAtEnd, p.installTimeout);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.run();
                } catch (Throwable t) {
                    Log.add("崩溃：" + t);
                } finally {
                    Log.setRunning(false);
                    stopSelf();
                }
            }
        }, "cloudfrag-engine");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.requestStop();
        }
        Shell.stopDaemon();
        Log.setRunning(false);
        removeOverlay();
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- 回调

    @Override
    public void onLog(String msg) {
        Log.add(msg);
        update(buildNotification(msg));
    }

    @Override
    public void onProgress(int claimed) {
        update(buildNotification("已领取 " + claimed + " 次碎片"));
    }

    @Override
    public void onFinish(boolean ok, String msg) {
        Log.add(msg);
        update(buildNotification(msg));
    }

    private void update(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTI_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "领碎片任务", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("云服务领碎片")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }
}

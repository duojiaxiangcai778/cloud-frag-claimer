package com.zcode.cloudfrag;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置项。 */
final class Prefs {

    private static final String NAME = "cloudfrag";

    int useSeconds = 16;
    boolean doToday = true;
    boolean doMiddle = true;
    boolean doDaily = true;
    int maxTasks = 12;
    int maxMinutes = 40;
    /** 单个应用装完的最长等待（秒）：差集检测到新包立刻走，这个只是兜底上限。 */
    int installTimeout = 90;
    boolean exitAtEnd = true;
    boolean keepScreenOn = true;

    static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.useSeconds = sp.getInt("useSeconds", 16);
        p.doToday = sp.getBoolean("doToday", true);
        p.doMiddle = sp.getBoolean("doMiddle", true);
        p.doDaily = sp.getBoolean("doDaily", true);
        p.maxTasks = sp.getInt("maxTasks", 12);
        p.maxMinutes = sp.getInt("maxMinutes", 40);
        p.installTimeout = sp.getInt("installTimeout", 90);
        p.exitAtEnd = sp.getBoolean("exitAtEnd", true);
        p.keepScreenOn = sp.getBoolean("keepScreenOn", true);
        return p;
    }

    void save(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt("useSeconds", useSeconds)
                .putBoolean("doToday", doToday)
                .putBoolean("doMiddle", doMiddle)
                .putBoolean("doDaily", doDaily)
                .putInt("maxTasks", maxTasks)
                .putInt("maxMinutes", maxMinutes)
                .putInt("installTimeout", installTimeout)
                .putBoolean("exitAtEnd", exitAtEnd)
                .putBoolean("keepScreenOn", keepScreenOn)
                .apply();
    }
}


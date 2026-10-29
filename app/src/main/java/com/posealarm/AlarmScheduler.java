package com.posealarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 用 AlarmManager.setAlarmClock 排定闹钟：系统会在状态栏显示闹钟图标，并在低电耗模式下照常唤醒 */
public final class AlarmScheduler {

    private AlarmScheduler() {}

    public static void schedule(Context ctx, Alarm alarm) {
        cancel(ctx, alarm.id);
        if (!alarm.enabled) return;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long at = alarm.nextTrigger(System.currentTimeMillis());
        PendingIntent fire = fireIntent(ctx, alarm.id);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 没有精确闹钟权限时退而求其次，可能晚几分钟
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
            return;
        }
        PendingIntent show = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
    }

    public static void cancel(Context ctx, int id) {
        ctx.getSystemService(AlarmManager.class).cancel(fireIntent(ctx, id));
    }

    public static void rescheduleAll(Context ctx) {
        for (Alarm a : AlarmStore.load(ctx)) schedule(ctx, a);
    }

    private static PendingIntent fireIntent(Context ctx, int id) {
        Intent i = new Intent(ctx, AlarmReceiver.class).putExtra(AlarmReceiver.EXTRA_ID, id);
        return PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

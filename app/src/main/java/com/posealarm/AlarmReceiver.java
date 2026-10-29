package com.posealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 闹钟到点：开始响铃，并排好下一次 (只响一次的闹钟则关掉) */
public class AlarmReceiver extends BroadcastReceiver {

    static final String EXTRA_ID = "alarm_id";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Alarm alarm = AlarmStore.get(ctx, intent.getIntExtra(EXTRA_ID, 0));
        if (alarm == null || !alarm.enabled) return;
        if (alarm.days == 0) {
            alarm.enabled = false;
            AlarmStore.save(ctx, alarm);
        } else {
            AlarmScheduler.schedule(ctx, alarm);
        }
        AlarmService.start(ctx, alarm.mission, alarm.target);
    }
}

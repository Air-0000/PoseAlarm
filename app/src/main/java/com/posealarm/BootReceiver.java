package com.posealarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 重启、应用更新、改时间 / 时区后，系统排定的闹钟会丢失或错位，全部重新排一遍 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        AlarmScheduler.rescheduleAll(ctx);
    }
}

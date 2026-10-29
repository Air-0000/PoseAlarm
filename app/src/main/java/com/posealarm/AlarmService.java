package com.posealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * 响铃前台服务：循环播放闹铃、振动，音量逐渐加大且不允许被调到下限以下。
 * 没有“停止”“稍后提醒”按钮，只有 RingActivity 在任务完成后才会调用 stop()。
 */
public class AlarmService extends Service {

    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String EXTRA_MISSION = "mission";
    private static final String EXTRA_TARGET = "target";
    private static final String CHANNEL_ID = "ringing";
    private static final int NOTIFICATION_ID = 1;

    // 音量 RAMP_MS 内从 START_VOLUME 涨到最大；系统闹钟音量至少保持在 MIN_STREAM_RATIO
    private static final float START_VOLUME = 0.3f;
    private static final long RAMP_MS = 60_000;
    private static final float MIN_STREAM_RATIO = 0.7f;
    private static final long TICK_MS = 1_000;

    /** 正在响的任务，供 RingActivity 读取；为 null 表示没在响 */
    static volatile String ringingMission;
    static volatile int ringingTarget;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;
    private int originalStreamVolume = -1;
    private long ringStart;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            float t = Math.min(1f, (System.currentTimeMillis() - ringStart) / (float) RAMP_MS);
            float v = START_VOLUME + (1f - START_VOLUME) * t;
            if (player != null) player.setVolume(v, v);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int floor = Math.round(max * MIN_STREAM_RATIO);
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < floor) {
                try {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0);
                } catch (SecurityException ignored) {
                    // 勿扰模式下系统可能不允许改音量
                }
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context ctx, String mission, int target) {
        Intent i = new Intent(ctx, AlarmService.class).setAction(ACTION_START)
                .putExtra(EXTRA_MISSION, mission).putExtra(EXTRA_TARGET, target);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, AlarmService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            ringingMission = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ringingMission == null) {
            String mission = intent != null ? intent.getStringExtra(EXTRA_MISSION) : null;
            ringingMission = mission != null ? mission : PoseMission.SQUAT;
            ringingTarget = intent != null ? intent.getIntExtra(EXTRA_TARGET, 10) : 10;
        }
        startInForeground();
        if (player == null) startRinging();
        // 屏幕亮着、不在锁屏时全屏通知只会弹横幅，这里再尝试直接打开响铃界面
        try {
            startActivity(RingActivity.intent(this));
        } catch (RuntimeException ignored) {
        }
        // 被系统杀掉后重启，继续响
        return START_REDELIVER_INTENT;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "响铃中", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(this, 0, RingActivity.intent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("起床！")
                .setContentText("完成" + PoseMission.name(ringingMission) + " " + ringingTarget + " "
                        + PoseMission.unit(ringingMission) + "才能关闭闹钟")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startRinging() {
        ringStart = System.currentTimeMillis();
        audio = getSystemService(AudioManager.class);
        originalStreamVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoseAlarm:ringing");
        wakeLock.acquire();

        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.setVolume(START_VOLUME, START_VOLUME);
            player.prepare();
            player.start();
        } catch (Exception e) {
            // 系统没有可用铃声时只靠振动
            if (player != null) player.release();
            player = null;
        }

        vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 600}, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        handler.post(tick);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
        if (audio != null && originalStreamVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, originalStreamVolume, 0);
            } catch (SecurityException ignored) {
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

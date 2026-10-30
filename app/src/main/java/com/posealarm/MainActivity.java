package com.posealarm;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.TimePicker;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/** 主界面：闹钟列表、新建 / 编辑、权限提醒、试响 */
public class MainActivity extends AppCompatActivity {

    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    private static final int MAX_TARGET = 100;

    private MaterialToolbar toolbar;
    private View layoutPermission, layoutEmpty;
    private TextView tvPermission;
    private final List<Alarm> alarms = new ArrayList<>();
    private final AlarmAdapter adapter = new AlarmAdapter();

    /** 当前缺的第一个权限对应的处理动作 */
    private Runnable fixPermission;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> refreshPermissions());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        layoutPermission = findViewById(R.id.layout_permission);
        layoutEmpty = findViewById(R.id.layout_empty);
        tvPermission = findViewById(R.id.tv_permission);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btn_add).setOnClickListener(v -> editAlarm(null));
        findViewById(R.id.btn_test).setOnClickListener(v -> testRing());
        findViewById(R.id.btn_permission).setOnClickListener(v -> {
            if (fixPermission != null) fixPermission.run();
        });
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_check_update) {
                AppUpdateManager.getInstance().checkUpdate(this, true);
            } else if (id == R.id.action_download_links) {
                AppUpdateManager.getInstance().showDownloadLinksDialog(this);
            } else if (id == R.id.action_update_url) {
                AppUpdateManager.getInstance().showConfigureUrlDialog(this);
            } else {
                return false;
            }
            return true;
        });

        // 启动后静默检测线上新版本
        if (savedInstanceState == null) {
            toolbar.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    AppUpdateManager.getInstance().checkUpdate(MainActivity.this, false);
                }
            }, 1500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        refreshPermissions();
    }

    private void reload() {
        alarms.clear();
        alarms.addAll(AlarmStore.load(this));
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
        toolbar.setSubtitle(nextAlarmText());
    }

    private String nextAlarmText() {
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        for (Alarm a : alarms) if (a.enabled) next = Math.min(next, a.nextTrigger(now));
        if (next == Long.MAX_VALUE) return "没有开启的闹钟";
        long min = (next - now + 59_999) / 60_000;
        return "下一个闹钟：" + (min >= 60 ? (min / 60) + " 小时 " : "") + (min % 60) + " 分钟后";
    }

    // ---------- 权限 ----------

    private void refreshPermissions() {
        fixPermission = null;
        String msg = null;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            msg = "需要通知权限，闹钟才能在锁屏上弹出";
            fixPermission = () -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()) {
            msg = "需要允许全屏通知，闹钟响时才能直接亮屏显示";
            fixPermission = () -> openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
            msg = "需要允许精确闹钟，否则可能晚响几分钟";
            fixPermission = () -> openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        } else if (!granted(Manifest.permission.CAMERA)) {
            msg = "需要摄像头权限来识别你的动作";
            fixPermission = () -> permissionLauncher.launch(Manifest.permission.CAMERA);
        }
        layoutPermission.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        tvPermission.setText(msg);
        // 精确闹钟权限可能刚刚被授予，重新排一次
        AlarmScheduler.rescheduleAll(this);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action, Uri.fromParts("package", getPackageName(), null)));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    // ---------- 试响 ----------

    private void testRing() {
        String[] names = new String[PoseMission.TYPES.length];
        for (int i = 0; i < names.length; i++) {
            String t = PoseMission.TYPES[i];
            names[i] = PoseMission.name(t) + " " + testTarget(t) + " " + PoseMission.unit(t);
        }
        new AlertDialog.Builder(this)
                .setTitle("马上试响一次")
                .setItems(names, (d, which) -> {
                    String t = PoseMission.TYPES[which];
                    AlarmService.start(this, t, testTarget(t));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int testTarget(String type) {
        return PoseMission.isHold(type) ? 5 : 3;
    }

    // ---------- 新建 / 编辑 ----------

    private void editAlarm(Alarm existing) {
        Alarm a = new Alarm();
        if (existing != null) {
            a.id = existing.id;
            a.hour = existing.hour;
            a.minute = existing.minute;
            a.days = existing.days;
            a.mission = existing.mission;
            a.target = existing.target;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_alarm, null);
        TimePicker picker = view.findViewById(R.id.time_picker);
        picker.setIs24HourView(true);
        picker.setHour(a.hour);
        picker.setMinute(a.minute);

        ChipGroup days = view.findViewById(R.id.chip_days);
        for (int i = 0; i < 7; i++) {
            Chip c = newChip(DAY_NAMES[i]);
            c.setChecked((a.days & (1 << i)) != 0);
            days.addView(c);
        }

        TextView tvTarget = view.findViewById(R.id.tv_target);
        TextView tvTip = view.findViewById(R.id.tv_mission_tip);
        Runnable showTarget = () -> {
            tvTarget.setText(a.target + " " + PoseMission.unit(a.mission));
            tvTip.setText(PoseMission.tip(a.mission));
        };
        ChipGroup missions = view.findViewById(R.id.chip_missions);
        for (String t : PoseMission.TYPES) {
            Chip c = newChip(PoseMission.name(t));
            c.setTag(t);
            c.setChecked(t.equals(a.mission));
            c.setOnCheckedChangeListener((b, checked) -> {
                if (!checked || t.equals(a.mission)) return;
                a.mission = t;
                a.target = PoseMission.defaultTarget(t);
                showTarget.run();
            });
            missions.addView(c);
        }
        view.findViewById(R.id.btn_minus).setOnClickListener(v -> {
            a.target = Math.max(1, a.target - 1);
            showTarget.run();
        });
        view.findViewById(R.id.btn_plus).setOnClickListener(v -> {
            a.target = Math.min(MAX_TARGET, a.target + 1);
            showTarget.run();
        });
        showTarget.run();

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "新闹钟" : "编辑闹钟")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    a.hour = picker.getHour();
                    a.minute = picker.getMinute();
                    a.days = 0;
                    for (int i = 0; i < 7; i++) if (((Chip) days.getChildAt(i)).isChecked()) a.days |= 1 << i;
                    a.enabled = true;
                    AlarmStore.save(this, a);
                    AlarmScheduler.schedule(this, a);
                    reload();
                })
                .setNegativeButton("取消", null);
        if (existing != null) {
            b.setNeutralButton("删除", (d, w) -> {
                AlarmScheduler.cancel(this, existing.id);
                AlarmStore.delete(this, existing.id);
                reload();
            });
        }
        b.show();
    }

    private Chip newChip(String text) {
        Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
        c.setText(text);
        c.setCheckable(true);
        c.setCheckedIconVisible(false);
        c.setChipBackgroundColorResource(R.color.sel_selectable_bg);
        c.setTextColor(ContextCompat.getColorStateList(this, R.color.sel_selectable_text));
        c.setChipStrokeColorResource(R.color.sel_line);
        c.setChipStrokeWidthResource(R.dimen.stroke_regular);
        c.setShapeAppearanceModel(c.getShapeAppearanceModel().withCornerSize(0));
        c.setEnsureMinTouchTargetSize(false);
        return c;
    }

    // ---------- 列表 ----------

    private class AlarmAdapter extends RecyclerView.Adapter<AlarmAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final TextView time, mission, repeat;
            final MaterialSwitch enabled;

            Holder(View v) {
                super(v);
                time = v.findViewById(R.id.tv_time);
                mission = v.findViewById(R.id.tv_mission);
                repeat = v.findViewById(R.id.tv_repeat);
                enabled = v.findViewById(R.id.sw_enabled);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alarm, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Alarm a = alarms.get(position);
            h.time.setText(a.timeText());
            h.time.setAlpha(a.enabled ? 1f : 0.4f);
            h.mission.setText(a.missionText());
            h.repeat.setText(a.repeatText());
            h.enabled.setOnCheckedChangeListener(null);
            h.enabled.setChecked(a.enabled);
            h.enabled.setOnCheckedChangeListener((b, checked) -> {
                a.enabled = checked;
                AlarmStore.save(MainActivity.this, a);
                AlarmScheduler.schedule(MainActivity.this, a);
                h.time.setAlpha(checked ? 1f : 0.4f);
                toolbar.setSubtitle(nextAlarmText());
            });
            h.itemView.setOnClickListener(v -> editAlarm(a));
        }

        @Override
        public int getItemCount() {
            return alarms.size();
        }
    }
}

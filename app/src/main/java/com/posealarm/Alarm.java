package com.posealarm;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/** 一个闹钟：时间、重复日、关闹钟任务 */
public class Alarm {

    public int id;
    public int hour = 7;
    public int minute = 0;
    /** 重复日位掩码：bit0 = 周一 … bit6 = 周日；0 表示只响一次 */
    public int days = 0;
    public String mission = PoseMission.SQUAT;
    public int target = PoseMission.defaultTarget(PoseMission.SQUAT);
    public boolean enabled = true;

    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    /** 从 now 之后的下一次响铃时间 (毫秒) */
    public long nextTrigger(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
        if (days != 0) {
            for (int i = 0; i < 7 && (days & (1 << dayIndex(c))) == 0; i++) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        return c.getTimeInMillis();
    }

    /** Calendar 的星期转为 0 = 周一 … 6 = 周日 */
    static int dayIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    public String timeText() {
        return String.format("%02d:%02d", hour, minute);
    }

    public String repeatText() {
        if (days == 0) return "只响一次";
        if (days == 0x7F) return "每天";
        if (days == 0x1F) return "工作日";
        if (days == 0x60) return "周末";
        StringBuilder sb = new StringBuilder("每周");
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) sb.append(DAY_NAMES[i]);
        return sb.toString();
    }

    public String missionText() {
        return PoseMission.name(mission) + " " + target + " " + PoseMission.unit(mission);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id).put("hour", hour).put("minute", minute).put("days", days)
                .put("mission", mission).put("target", target).put("enabled", enabled);
    }

    static Alarm fromJson(JSONObject o) {
        Alarm a = new Alarm();
        a.id = o.optInt("id");
        a.hour = o.optInt("hour", 7);
        a.minute = o.optInt("minute");
        a.days = o.optInt("days");
        a.mission = o.optString("mission", PoseMission.SQUAT);
        a.target = o.optInt("target", PoseMission.defaultTarget(a.mission));
        a.enabled = o.optBoolean("enabled", true);
        return a;
    }
}

package com.posealarm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 闹钟列表存在 SharedPreferences 里 (JSON 数组) */
public final class AlarmStore {

    private static final String PREFS = "alarms";
    private static final String KEY_LIST = "list";
    private static final String KEY_NEXT_ID = "next_id";

    private AlarmStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Alarm> load(Context ctx) {
        List<Alarm> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(Alarm.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        list.sort(Comparator.comparingInt((Alarm a) -> a.hour * 60 + a.minute));
        return list;
    }

    public static synchronized Alarm get(Context ctx, int id) {
        for (Alarm a : load(ctx)) if (a.id == id) return a;
        return null;
    }

    /** 新闹钟 (id 为 0) 会分配 id；已有的按 id 覆盖 */
    public static synchronized void save(Context ctx, Alarm alarm) {
        List<Alarm> list = load(ctx);
        if (alarm.id == 0) {
            int next = prefs(ctx).getInt(KEY_NEXT_ID, 1);
            alarm.id = next;
            prefs(ctx).edit().putInt(KEY_NEXT_ID, next + 1).apply();
        }
        list.removeIf(a -> a.id == alarm.id);
        list.add(alarm);
        write(ctx, list);
    }

    public static synchronized void delete(Context ctx, int id) {
        List<Alarm> list = load(ctx);
        list.removeIf(a -> a.id == id);
        write(ctx, list);
    }

    private static void write(Context ctx, List<Alarm> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Alarm a : list) arr.put(a.toJson());
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }
}

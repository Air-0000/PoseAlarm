package com.posealarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class PoseMissionTest {

    /** 正面站立的人：肩宽 100，图像坐标 y 向下 */
    private static float[][] standing() {
        float[][] p = new float[33][];
        put(p, PoseMission.NOSE, 200, 80);
        put(p, PoseMission.L_SH, 150, 150);
        put(p, PoseMission.R_SH, 250, 150);
        put(p, PoseMission.L_EL, 140, 230);
        put(p, PoseMission.R_EL, 260, 230);
        put(p, PoseMission.L_WR, 135, 300);
        put(p, PoseMission.R_WR, 265, 300);
        put(p, PoseMission.L_HIP, 170, 320);
        put(p, PoseMission.R_HIP, 230, 320);
        put(p, PoseMission.L_KN, 170, 420);
        put(p, PoseMission.R_KN, 230, 420);
        put(p, PoseMission.L_AN, 170, 520);
        put(p, PoseMission.R_AN, 230, 520);
        return p;
    }

    private static float[][] squatting() {
        float[][] p = standing();
        // 髋部后移下沉，膝盖约 100°
        put(p, PoseMission.L_HIP, 270, 400);
        put(p, PoseMission.R_HIP, 330, 400);
        return p;
    }

    private static float[][] jackOpen() {
        float[][] p = standing();
        put(p, PoseMission.L_EL, 110, 90);
        put(p, PoseMission.R_EL, 290, 90);
        put(p, PoseMission.L_WR, 120, 30);
        put(p, PoseMission.R_WR, 280, 30);
        put(p, PoseMission.L_AN, 100, 520);
        put(p, PoseMission.R_AN, 300, 520);
        return p;
    }

    private static float[][] tPose() {
        float[][] p = standing();
        put(p, PoseMission.L_EL, 50, 152);
        put(p, PoseMission.R_EL, 350, 152);
        put(p, PoseMission.L_WR, -50, 155);
        put(p, PoseMission.R_WR, 450, 155);
        return p;
    }

    private static void put(float[][] p, int i, float x, float y) {
        p[i] = new float[]{x, y, 0.99f};
    }

    @Test
    public void squatCountsOnlyFullReps() {
        PoseMission m = new PoseMission(PoseMission.SQUAT, 2);
        long t = 0;
        m.update(standing(), t += 100);
        assertTrue(m.update(squatting(), t += 100).active);
        m.update(standing(), t += 100);
        assertEquals(1, m.value());
        // 站着不动不计数
        m.update(standing(), t += 100);
        assertEquals(1, m.value());
        m.update(squatting(), t += 100);
        m.update(standing(), t += 100);
        assertTrue(m.isDone());
    }

    @Test
    public void squatIgnoresMissingLegs() {
        PoseMission m = new PoseMission(PoseMission.SQUAT, 1);
        float[][] p = standing();
        p[PoseMission.L_AN][2] = 0.1f;
        p[PoseMission.R_AN][2] = 0.1f;
        assertFalse(m.update(p, 100).active);
        assertEquals(0, m.value());
    }

    @Test
    public void jumpingJackCounts() {
        PoseMission m = new PoseMission(PoseMission.JACK, 3);
        long t = 0;
        for (int i = 0; i < 3; i++) {
            m.update(standing(), t += 100);
            m.update(jackOpen(), t += 100);
        }
        m.update(standing(), t += 100);
        assertEquals(3, m.value());
        assertTrue(m.isDone());
    }

    @Test
    public void tPoseHoldResetsWhenBroken() {
        PoseMission m = new PoseMission(PoseMission.T_POSE, 3);
        long t = 0;
        for (int i = 0; i < 20; i++) m.update(tPose(), t += 100);
        assertEquals(1, m.value());
        // 放下超过 0.5 秒，重新计时
        for (int i = 0; i < 7; i++) m.update(standing(), t += 100);
        assertEquals(0, m.value());
        for (int i = 0; i < 32; i++) m.update(tPose(), t += 100);
        assertTrue(m.isDone());
    }

    @Test
    public void handsUpHold() {
        PoseMission m = new PoseMission(PoseMission.HANDS_UP, 2);
        float[][] up = jackOpen();
        long t = 0;
        assertFalse(m.update(standing(), t += 100).active);
        for (int i = 0; i < 22; i++) m.update(up, t += 100);
        assertTrue(m.isDone());
    }

    @Test
    public void nextTriggerRespectsRepeatDays() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 30, 8, 0, 0); // 周五 08:00
        c.set(Calendar.MILLISECOND, 0);
        Alarm a = new Alarm();
        a.hour = 7;
        a.minute = 0;
        a.days = 0x1F; // 工作日
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(a.nextTrigger(c.getTimeInMillis()));
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK));
        assertEquals(7, next.get(Calendar.HOUR_OF_DAY));

        a.days = 0; // 只响一次：明天
        next.setTimeInMillis(a.nextTrigger(c.getTimeInMillis()));
        assertEquals(Calendar.SATURDAY, next.get(Calendar.DAY_OF_WEEK));
    }
}

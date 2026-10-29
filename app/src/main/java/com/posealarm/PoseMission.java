package com.posealarm;

/**
 * 关闹钟任务：按人体关键点判断动作并计数 / 计时。
 * 不依赖 Android 和 ML Kit，关键点用 float[33][3] {x, y, 在画面内的可信度} 传入，方便单元测试。
 * 关键点编号与 ML Kit / MediaPipe Pose 一致，坐标为图像像素、y 轴向下。
 */
public final class PoseMission {

    public static final String SQUAT = "squat";
    public static final String JACK = "jack";
    public static final String HANDS_UP = "handsup";
    public static final String T_POSE = "tpose";
    public static final String[] TYPES = {SQUAT, JACK, HANDS_UP, T_POSE};

    static final int NOSE = 0, L_SH = 11, R_SH = 12, L_EL = 13, R_EL = 14, L_WR = 15, R_WR = 16,
            L_HIP = 23, R_HIP = 24, L_KN = 25, R_KN = 26, L_AN = 27, R_AN = 28;

    private static final float MIN_LIKELIHOOD = 0.5f;
    // 保持类动作中断超过这个时长就从 0 重新计时
    private static final long HOLD_GRACE_MS = 500;

    public static String name(String type) {
        switch (type) {
            case JACK: return "开合跳";
            case HANDS_UP: return "双手举高";
            case T_POSE: return "T 字站姿";
            default: return "深蹲";
        }
    }

    public static boolean isHold(String type) {
        return HANDS_UP.equals(type) || T_POSE.equals(type);
    }

    public static String unit(String type) {
        return isHold(type) ? "秒" : "个";
    }

    public static int defaultTarget(String type) {
        switch (type) {
            case JACK: return 15;
            case HANDS_UP:
            case T_POSE: return 15;
            default: return 10;
        }
    }

    public static String tip(String type) {
        switch (type) {
            case JACK: return "双手举过头顶、双脚分开，再收回到双手放下，算 1 个";
            case HANDS_UP: return "双臂伸直举过头顶并保持，放下会重新计时";
            case T_POSE: return "双臂向两侧水平伸直、和肩同高并保持，放下会重新计时";
            default: return "蹲到膝盖弯曲小于 105°，再站直，算 1 个";
        }
    }

    /** 一帧的判断结果：active 表示当前姿势到位，info 是给人看的提示 */
    public static final class Feedback {
        public final boolean active;
        public final String info;

        Feedback(boolean active, String info) {
            this.active = active;
            this.info = info;
        }
    }

    public final String type;
    public final int target;
    private int count;
    private long heldMs;
    private boolean seenActive;
    private long lastOkMs = Long.MIN_VALUE;
    private long lastMs = -1;

    public PoseMission(String type, int target) {
        this.type = type;
        this.target = Math.max(1, target);
    }

    public boolean isDone() {
        return isHold(type) ? heldMs >= target * 1000L : count >= target;
    }

    /** 显示用的当前进度：个数，或已保持的整秒数 */
    public int value() {
        return isHold(type) ? (int) Math.min(target, heldMs / 1000) : Math.min(target, count);
    }

    public float fraction() {
        float v = isHold(type) ? heldMs / 1000f : count;
        return Math.min(1f, v / target);
    }

    /** 送入一帧；pts 为 null 表示画面里没有人 */
    public Feedback update(float[][] pts, long nowMs) {
        long dt = lastMs < 0 ? 0 : nowMs - lastMs;
        lastMs = nowMs;
        if (isDone()) return new Feedback(true, "完成");
        if (pts == null) {
            if (isHold(type) && nowMs - lastOkMs > HOLD_GRACE_MS) heldMs = 0;
            return new Feedback(false, "画面里没看到人，站到镜头前");
        }
        switch (type) {
            case SQUAT: return reps(squatPhase(pts));
            case JACK: return reps(jackPhase(pts));
            default: {
                Feedback f = HANDS_UP.equals(type) ? handsUp(pts) : tPose(pts);
                if (f.active) {
                    heldMs += dt;
                    lastOkMs = nowMs;
                } else if (nowMs - lastOkMs > HOLD_GRACE_MS) {
                    heldMs = 0;
                }
                return f;
            }
        }
    }

    // ---------- 计次：先到位 (ACTIVE) 再回到起始姿势 (REST) 算 1 次 ----------

    private static final int UNKNOWN = 0, ACTIVE = 1, REST = 2, MID = 3;

    private static final class Phase {
        final int phase;
        final String info;

        Phase(int phase, String info) {
            this.phase = phase;
            this.info = info;
        }
    }

    private Feedback reps(Phase p) {
        if (p.phase == ACTIVE) seenActive = true;
        if (p.phase == REST && seenActive) {
            count++;
            seenActive = false;
        }
        String info = p.info + (seenActive ? " · 到位了，回到起始姿势计数" : "");
        return new Feedback(p.phase == ACTIVE, info);
    }

    private static Phase squatPhase(float[][] p) {
        float sum = 0;
        int n = 0;
        if (vis(p, L_HIP, L_KN, L_AN)) { sum += angle(p, L_HIP, L_KN, L_AN); n++; }
        if (vis(p, R_HIP, R_KN, R_AN)) { sum += angle(p, R_HIP, R_KN, R_AN); n++; }
        if (n == 0) return new Phase(UNKNOWN, "看不到腿，请退后让膝盖和脚踝入镜");
        float a = sum / n;
        int phase = a < 105 ? ACTIVE : a > 155 ? REST : MID;
        return new Phase(phase, String.format("膝盖角度 %.0f°", a));
    }

    private static Phase jackPhase(float[][] p) {
        if (!vis(p, L_SH, R_SH, L_WR, R_WR, NOSE)) return new Phase(UNKNOWN, "看不到上半身和双手");
        float sw = dist(p, L_SH, R_SH);
        float shY = (p[L_SH][1] + p[R_SH][1]) / 2;
        float noseY = p[NOSE][1];
        boolean armsUp = p[L_WR][1] < noseY && p[R_WR][1] < noseY;
        boolean armsDown = p[L_WR][1] > shY && p[R_WR][1] > shY;
        boolean legsOpen = true, legsClosed = true;
        String legInfo = "（脚不在画面里，只看手）";
        if (vis(p, L_AN, R_AN)) {
            float ad = Math.abs(p[L_AN][0] - p[R_AN][0]);
            legsOpen = ad > sw * 1.3f;
            legsClosed = ad < sw * 1.1f;
            legInfo = legsOpen ? "脚：分开" : legsClosed ? "脚：并拢" : "脚：半开";
        }
        int phase = armsUp && legsOpen ? ACTIVE : armsDown && legsClosed ? REST : MID;
        return new Phase(phase, "手：" + (armsUp ? "举起" : armsDown ? "放下" : "中间") + "  " + legInfo);
    }

    // ---------- 保持 ----------

    private static Feedback handsUp(float[][] p) {
        if (!vis(p, NOSE, L_SH, R_SH, L_EL, R_EL, L_WR, R_WR)) return new Feedback(false, "看不到双臂，请退后一点");
        float noseY = p[NOSE][1];
        boolean ok = p[L_WR][1] < noseY && p[R_WR][1] < noseY
                && p[L_EL][1] < p[L_SH][1] && p[R_EL][1] < p[R_SH][1];
        return new Feedback(ok, ok ? "姿势正确，保持住" : "把双手伸直举过头顶");
    }

    private static Feedback tPose(float[][] p) {
        if (!vis(p, L_SH, R_SH, L_EL, R_EL, L_WR, R_WR)) return new Feedback(false, "看不到双臂，请退后一点");
        float sw = dist(p, L_SH, R_SH);
        boolean level = Math.abs(p[L_WR][1] - p[L_SH][1]) < sw * 0.45f
                && Math.abs(p[R_WR][1] - p[R_SH][1]) < sw * 0.45f;
        boolean wide = Math.abs(p[L_WR][0] - p[R_WR][0]) > sw * 2.4f;
        boolean straight = angle(p, L_SH, L_EL, L_WR) > 150 && angle(p, R_SH, R_EL, R_WR) > 150;
        boolean ok = level && wide && straight;
        String info = ok ? "姿势正确，保持住" : !level ? "手臂和肩膀保持同一高度" : !straight ? "把手臂伸直" : "双臂再向两侧展开";
        return new Feedback(ok, info);
    }

    // ---------- 几何 ----------

    private static boolean vis(float[][] p, int... ids) {
        for (int i : ids) if (p[i] == null || p[i][2] < MIN_LIKELIHOOD) return false;
        return true;
    }

    private static float dist(float[][] p, int a, int b) {
        return (float) Math.hypot(p[a][0] - p[b][0], p[a][1] - p[b][1]);
    }

    /** a-b-c 在 b 处的夹角 (度) */
    static float angle(float[][] p, int a, int b, int c) {
        double x1 = p[a][0] - p[b][0], y1 = p[a][1] - p[b][1];
        double x2 = p[c][0] - p[b][0], y2 = p[c][1] - p[b][1];
        double len = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        if (len == 0) return 180;
        double cos = Math.max(-1, Math.min(1, (x1 * x2 + y1 * y2) / len));
        return (float) Math.toDegrees(Math.acos(cos));
    }
}

package com.posealarm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用内更新管理器 (与 CheckMarkScanner 的实现同一套思路)：
 * 1. 启动时静默检测、菜单里手动检测
 * 2. 新版本弹窗展示更新日志，支持强制更新
 * 3. 带进度的下载，校验完整性与 SHA-256，多个下载地址依次回退
 * 4. FileProvider 安装与「安装未知应用」权限引导
 * 5. 可自定义 version.json 地址 (例如局域网服务器)，内置 Gitee 与 GitHub 两条线路
 */
public class AppUpdateManager {

    private static final String PREF_NAME = "update_config";
    private static final String KEY_UPDATE_URL = "update_url";

    // 国内首选：Gitee 公开仓库里的 version.json，安装包放在该仓库的发行版附件
    public static final String GITEE_UPDATE_URL =
        "https://gitee.com/Air-0000/posealarm-update/raw/master/version.json";

    // 备用：GitHub 最新发行版里的 version.json 附件 (国内可能较慢或连不上)
    public static final String GITHUB_UPDATE_URL =
        "https://github.com/Air-0000/PoseAlarm/releases/latest/download/version.json";

    // 「其他下载地址」里展示的发行版页面
    public static final String GITEE_RELEASES_PAGE = "https://gitee.com/Air-0000/posealarm-update/releases";
    public static final String GITHUB_RELEASES_PAGE = "https://github.com/Air-0000/PoseAlarm/releases";

    // 局域网地址在外网必然连不上，用短超时尽快切到公网源
    private static final int LAN_CONNECT_TIMEOUT_MS = 3000;
    private static final int PUBLIC_CONNECT_TIMEOUT_MS = 8000;
    // 同时检测各更新源的总时限 (单个源的连接 + 读取超时之和以内)
    private static final long RACE_MAX_MS = 15000;

    private static final String TAG = "AppUpdateManager";

    private static AppUpdateManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 检测更新时同时请求各更新源用的线程池
    private final ExecutorService raceExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized AppUpdateManager getInstance() {
        if (instance == null) {
            instance = new AppUpdateManager();
        }
        return instance;
    }

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String apkUrl;
        public List<String> mirrorUrls = new ArrayList<>(); // apkUrl 下载失败时依次尝试的备用地址
        public String apkSize;
        public String updateLog;
        public boolean forceUpdate;
        public String sha256; // 安装包 SHA-256 (十六进制)，为空时不校验
        public String sourceUrl; // 本次检测成功的 version.json 地址

        public static UpdateInfo fromJson(String jsonStr) throws Exception {
            JSONObject json = new JSONObject(jsonStr);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.optInt("versionCode", 0);
            info.versionName = json.optString("versionName", "");
            info.apkUrl = json.optString("apkUrl", "");
            JSONArray mirrors = json.optJSONArray("mirrorUrls");
            if (mirrors != null) {
                for (int i = 0; i < mirrors.length(); i++) info.mirrorUrls.add(mirrors.optString(i));
            }
            info.apkSize = json.optString("apkSize", "");
            info.updateLog = json.optString("updateLog", "优化性能并修复若干已知问题。");
            info.forceUpdate = json.optBoolean("forceUpdate", false);
            info.sha256 = json.optString("sha256", "").trim();
            return info;
        }
    }

    /** 用户自定义的 version.json 地址；没填时为空字符串 */
    public static String getUpdateUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String url = sp.getString(KEY_UPDATE_URL, "");
        // 填的若是内置地址，当作没填，保持 Gitee → GitHub 的默认顺序
        if (UpdateSourceResolver.matchesAny(url, GITEE_UPDATE_URL, GITHUB_UPDATE_URL)) return "";
        return url;
    }

    /**
     * 依次尝试的更新源：用户在「更新服务器设置」里指定的地址 → Gitee → GitHub
     */
    public static List<String> getUpdateSources(Context context) {
        return UpdateSourceResolver.buildSources(getUpdateUrl(context), GITEE_UPDATE_URL, GITHUB_UPDATE_URL);
    }

    private static int connectTimeoutFor(String url) {
        return UpdateSourceResolver.isPrivateHost(url) ? LAN_CONNECT_TIMEOUT_MS : PUBLIC_CONNECT_TIMEOUT_MS;
    }

    public static void setUpdateUrl(Context context, String url) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_UPDATE_URL, url != null ? url.trim() : "").apply();
    }

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    /**
     * 检查版本更新
     * @param activity 上下文 Activity
     * @param isManual 是否由用户手动点击触发（手动触发时若无更新或网络故障会弹出提示，自动触发则静默）
     */
    public void checkUpdate(Activity activity, boolean isManual) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        if (isManual) {
            StyledToast.show(activity, "正在检查更新，请稍候...", Toast.LENGTH_SHORT);
        }

        List<String> sources = getUpdateSources(activity);

        executor.execute(() -> {
            StringBuilder errors = new StringBuilder();
            final UpdateInfo info = raceUpdateSources(activity, sources, errors);
            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (info == null) {
                    if (isManual) {
                        showCheckFailedDialog(activity);
                    }
                } else if (info.versionCode > getCurrentVersionCode(activity)) {
                    showUpdateDialog(activity, info);
                } else if (isManual) {
                    new AlertDialog.Builder(activity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本已是最新版 (v" + getCurrentVersionName(activity) + ")，无需更新。")
                        .setPositiveButton("我知道了", null)
                        .show();
                }
            });
        });
    }

    /**
     * 同时向所有更新源请求 version.json，按优先级选用 (规则见 UpdateSourceResolver.pickSource)：
     * 自定义地址优先，其次 Gitee (4 秒内回应)，再 GitHub。
     * 在后台线程调用；全部失败返回 null，失败原因追加到 errors。未被选中的请求各自超时结束，结果丢弃。
     */
    private UpdateInfo raceUpdateSources(Context context, List<String> sources, StringBuilder errors) {
        int n = sources.size();
        int[] states = new int[n];
        UpdateInfo[] infos = new UpdateInfo[n];
        String[] failures = new String[n];
        BlockingQueue<Integer> finished = new LinkedBlockingQueue<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            raceExecutor.execute(() -> {
                try {
                    infos[idx] = fetchUpdateInfo(context, sources.get(idx));
                } catch (Exception e) {
                    failures[idx] = e.getMessage();
                }
                finished.offer(idx);
            });
        }

        long start = SystemClock.elapsedRealtime();
        while (true) {
            long elapsed = SystemClock.elapsedRealtime() - start;
            int pick = UpdateSourceResolver.pickSource(sources, states, elapsed);
            if (pick >= 0) {
                Log.i(TAG, "更新源选用 " + sources.get(pick) + "，用时 " + elapsed + "ms，状态 " + Arrays.toString(states));
                return infos[pick];
            }
            if (pick == UpdateSourceResolver.PICK_NONE || elapsed > RACE_MAX_MS) {
                for (int i = 0; i < n; i++) {
                    errors.append(sources.get(i)).append(" -> ")
                          .append(states[i] == UpdateSourceResolver.STATE_FAILED ? failures[i] : "超时").append('\n');
                }
                Log.w(TAG, "所有更新源均不可用，用时 " + elapsed + "ms:\n" + errors);
                return null;
            }
            long wait = UpdateSourceResolver.nextDecisionDelay(sources, states, elapsed, RACE_MAX_MS - elapsed);
            try {
                Integer idx = finished.poll(wait, TimeUnit.MILLISECONDS);
                if (idx != null) {
                    states[idx] = (infos[idx] != null) ? UpdateSourceResolver.STATE_OK : UpdateSourceResolver.STATE_FAILED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 从单个更新源读取 version.json (在后台线程调用)，失败抛出异常。
     * Gitee raw 与 GitHub 发行版附件都会 302 跳转到 https 的存储地址，HttpURLConnection 会自动跟随
     */
    private UpdateInfo fetchUpdateInfo(Context context, String sourceUrl) throws Exception {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            conn = (HttpURLConnection) new URL(sourceUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutFor(sourceUrl));
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "PoseAlarm/" + getCurrentVersionName(context));
            conn.setRequestProperty("Accept", "application/json");

            int respCode = conn.getResponseCode();
            if (respCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + respCode);
            }
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            UpdateInfo info = UpdateInfo.fromJson(sb.toString());
            info.sourceUrl = sourceUrl;
            return info;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 弹出版本更新对话框
     */
    public void showUpdateDialog(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        String curVer = getCurrentVersionName(activity);
        String sizeStr = (info.apkSize != null && !info.apkSize.isEmpty()) ? " (" + info.apkSize + ")" : "";

        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本：v").append(info.versionName).append(sizeStr).append("\n");
        msg.append("当前版本：v").append(curVer).append("\n\n");
        msg.append("【更新说明】\n").append(info.updateLog != null ? info.updateLog : "优化系统功能");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setMessage(msg.toString())
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即更新安装", (dialog, which) -> downloadAndInstall(activity, info));

        // 强制更新时不给其他按钮：点了会关掉本弹窗，等于绕过强制更新
        if (!info.forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
            builder.setNeutralButton("其他下载地址", (dialog, which) -> showDownloadLinksDialog(activity));
        }

        builder.show();
    }

    /**
     * 下载并自动启动安装
     */
    public void downloadAndInstall(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) {
            StyledToast.show(activity, "更新下载地址无效", Toast.LENGTH_SHORT);
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_progress_title);
        ProgressBar pb = dialogView.findViewById(R.id.pb_update_progress);
        TextView tvStatus = dialogView.findViewById(R.id.tv_progress_status);

        tvTitle.setText("正在下载 起床动起来 v" + info.versionName);

        AtomicBoolean isCancelled = new AtomicBoolean(false);

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消下载", (dialog, which) -> {
                isCancelled.set(true);
                // 强制更新不能取消了事，回到更新弹窗
                if (info.forceUpdate) showUpdateDialog(activity, info);
            })
            .create();

        progressDialog.show();

        String sourceUrl = (info.sourceUrl != null) ? info.sourceUrl : GITEE_UPDATE_URL;
        List<String> candidates = UpdateSourceResolver.buildDownloadCandidates(sourceUrl, info.apkUrl, info.mirrorUrls);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                conn = openApkConnection(candidates);

                int totalLength = conn.getContentLength();
                is = conn.getInputStream();

                File downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) {
                    downloadDir = activity.getFilesDir();
                }
                File apkFile = new File(downloadDir, "PoseAlarm_v" + info.versionName + ".apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                fos = new FileOutputStream(apkFile);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int len;
                long downloaded = 0;
                long lastUpdateTime = 0;

                while ((len = is.read(buffer)) != -1) {
                    if (isCancelled.get()) {
                        apkFile.delete();
                        return;
                    }

                    fos.write(buffer, 0, len);
                    digest.update(buffer, 0, len);
                    downloaded += len;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdateTime > 100 || downloaded == totalLength) {
                        lastUpdateTime = now;
                        long curDownloaded = downloaded;
                        mainHandler.post(() -> {
                            if (progressDialog.isShowing()) {
                                if (totalLength > 0) {
                                    int progress = (int) (curDownloaded * 100 / totalLength);
                                    pb.setProgress(progress);
                                    float curMB = curDownloaded / (1024f * 1024f);
                                    float totalMB = totalLength / (1024f * 1024f);
                                    tvStatus.setText(String.format("正在下载: %.1fMB / %.1fMB (%d%%)", curMB, totalMB, progress));
                                } else {
                                    float curMB = curDownloaded / (1024f * 1024f);
                                    tvStatus.setText(String.format("已下载: %.1fMB", curMB));
                                }
                            }
                        });
                    }
                }

                fos.flush();

                // 连接中途断开时读取也可能正常结束，残缺的安装包不能交给系统安装器
                if (totalLength > 0 && downloaded != totalLength) {
                    apkFile.delete();
                    throw new Exception(String.format("安装包下载不完整 (%d / %d 字节)，网络可能中断", downloaded, totalLength));
                }
                if (info.sha256 != null && !info.sha256.isEmpty()
                        && !info.sha256.equalsIgnoreCase(toHex(digest.digest()))) {
                    apkFile.delete();
                    throw new Exception("安装包校验失败 (SHA-256 不一致)，文件可能损坏或被篡改");
                }

                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    StyledToast.show(activity, "下载完成，正在准备安装...", Toast.LENGTH_SHORT);
                    installApk(activity, apkFile);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    if (!isCancelled.get()) {
                        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage("更新安装包下载出错：" + e.getMessage() + "\n\n您可以检查网络连接后重试，或用浏览器从其他下载地址手动下载。")
                            .setPositiveButton("重试", (dialog, which) -> downloadAndInstall(activity, info))
                            .setNegativeButton("取消", (dialog, which) -> {
                                if (info.forceUpdate) showUpdateDialog(activity, info);
                            })
                            .setCancelable(!info.forceUpdate);
                        if (!info.forceUpdate) {
                            b.setNeutralButton("其他下载地址", (dialog, which) -> showDownloadLinksDialog(activity));
                        }
                        b.show();
                    }
                });
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (is != null) is.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        });
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 按候选顺序连接安装包地址，返回第一个 HTTP 200 的连接 (在后台线程调用)。
     * 非 200 (如 404) 直接换下一个，避免把错误页当安装包写入。
     */
    private HttpURLConnection openApkConnection(List<String> candidates) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (String candidate : candidates) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(candidate).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(connectTimeoutFor(candidate));
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept-Encoding", "identity");
                int respCode = conn.getResponseCode();
                if (respCode == HttpURLConnection.HTTP_OK) {
                    return conn;
                }
                errors.append("HTTP ").append(respCode);
            } catch (Exception e) {
                errors.append(e.getMessage());
            }
            errors.append(" (").append(candidate).append(")\n");
            if (conn != null) conn.disconnect();
        }
        throw new Exception("所有下载地址均不可用：\n" + errors.toString().trim());
    }

    /**
     * 适配全版本 Android 的 APK 自动安装器
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) {
            StyledToast.show(activity, "安装文件不存在，请重新下载", Toast.LENGTH_SHORT);
            return;
        }

        // Android 8.0 (API 26) 及以上需检查未知应用安装权限
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                .setTitle("需要开启安装权限")
                .setMessage("为完成自动升级，请允许本应用【安装未知应用】。\n开启后回到这里再点一次「立即更新安装」。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    try {
                        Uri packageUri = Uri.parse("package:" + activity.getPackageName());
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        StyledToast.show(activity, "打开设置失败，请手动到设置开启安装权限", Toast.LENGTH_LONG);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            StyledToast.show(activity, "启动安装器失败: " + e.getMessage(), Toast.LENGTH_LONG);
        }
    }

    /**
     * 其他下载地址：Gitee 与 GitHub 的发行版页面，可用浏览器打开或复制链接
     */
    public void showDownloadLinksDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String[] items = {
            "Gitee 发行版 (国内推荐)\n" + GITEE_RELEASES_PAGE,
            "GitHub 发行版\n" + GITHUB_RELEASES_PAGE,
        };
        String[] urls = {GITEE_RELEASES_PAGE, GITHUB_RELEASES_PAGE};
        new AlertDialog.Builder(activity)
            .setTitle("其他下载地址")
            .setItems(items, (dialog, which) -> openOrCopy(activity, urls[which]))
            .setNegativeButton("关闭", null)
            .show();
    }

    private static void openOrCopy(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            // 没有浏览器时复制链接
            ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("下载地址", url));
            StyledToast.show(activity, "已复制下载地址", Toast.LENGTH_SHORT);
        }
    }

    /**
     * 自定义更新地址配置弹窗
     */
    public void showConfigureUrlDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        EditText etUrl = new EditText(activity);
        etUrl.setHint("http://192.168.x.x:8080/version.json 或 https://...");
        etUrl.setText(getUpdateUrl(activity));
        etUrl.setSingleLine(false);
        etUrl.setMaxLines(3);
        etUrl.setPadding(30, 20, 30, 20);

        // 输入框左右留白，与对话框正文对齐，不贴着对话框描边
        FrameLayout urlContainer = new FrameLayout(activity);
        int sidePadding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        urlContainer.setPadding(sidePadding, 0, sidePadding, 0);
        urlContainer.addView(etUrl);

        new AlertDialog.Builder(activity)
            .setTitle("更新服务器设置")
            .setMessage("默认同时检测 Gitee 和 GitHub，优先用 Gitee。\n如需指定其他 version.json 地址 (会最优先使用)，可在此填写：")
            .setView(urlContainer)
            .setPositiveButton("保存并检查", (dialog, which) -> {
                setUpdateUrl(activity, etUrl.getText().toString().trim());
                StyledToast.show(activity, "已保存更新源，正在检测...", Toast.LENGTH_SHORT);
                checkUpdate(activity, true);
            })
            .setNeutralButton("恢复默认", (dialog, which) -> {
                setUpdateUrl(activity, "");
                StyledToast.show(activity, "已恢复默认更新源", Toast.LENGTH_SHORT);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 连接失败友好提示弹窗 (闹钟本身不需要联网)
     */
    private void showCheckFailedDialog(Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("暂时连不上更新服务器")
            .setMessage("Gitee 和 GitHub 都暂时连不上，请检查手机网络后重试。\n\n"
                        + "闹钟和动作识别全部在本机离线运行，不联网也能正常使用。")
            .setPositiveButton("我知道了", null)
            .setNeutralButton("重试检测", (dialog, which) -> checkUpdate(activity, true))
            .show();
    }
}

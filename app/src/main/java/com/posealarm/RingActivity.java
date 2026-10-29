package com.posealarm;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 响铃界面：打开摄像头，用 ML Kit 识别人体姿势，完成任务后才停止响铃。
 * 返回键和音量键在这里都被拦下。
 */
public class RingActivity extends AppCompatActivity {

    public static Intent intent(Context ctx) {
        return new Intent(ctx, RingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    private PreviewView previewView;
    private PoseOverlayView overlay;
    private TextView tvTitle, tvClock, tvCount, tvTarget, tvHint, tvState;
    private LinearProgressIndicator progress;
    private View layoutCameraError;
    private TextView tvCameraError;
    private MaterialButton btnDone;

    private PoseMission mission;
    private PoseDetector detector;
    private ExecutorService analysisExecutor;
    private boolean frontCamera;
    private boolean done;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Calendar c = Calendar.getInstance();
            tvClock.setText(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
            handler.postDelayed(this, 5_000);
        }
    };

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else showCameraError("没有摄像头权限，闹钟会一直响。请在设置里允许摄像头。", true);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_ring);

        previewView = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        tvTitle = findViewById(R.id.tv_title);
        tvClock = findViewById(R.id.tv_clock);
        tvCount = findViewById(R.id.tv_count);
        tvTarget = findViewById(R.id.tv_target);
        tvHint = findViewById(R.id.tv_hint);
        tvState = findViewById(R.id.tv_state);
        progress = findViewById(R.id.progress);
        layoutCameraError = findViewById(R.id.layout_camera_error);
        tvCameraError = findViewById(R.id.tv_camera_error);
        btnDone = findViewById(R.id.btn_done);

        String type = AlarmService.ringingMission;
        if (type == null) {
            // 闹钟已经不在响 (比如从旧通知点进来)
            finish();
            return;
        }
        mission = new PoseMission(type, AlarmService.ringingTarget);
        tvTitle.setText("起床！" + PoseMission.name(type) + " " + mission.target + " " + PoseMission.unit(type));
        tvHint.setText(PoseMission.tip(type));
        render(null);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (done) finish();
                else Toast.makeText(RingActivity.this, "完成任务才能关闭闹钟", Toast.LENGTH_SHORT).show();
            }
        });
        btnDone.setOnClickListener(v -> finish());
        findViewById(R.id.btn_camera_retry).setOnClickListener(v -> {
            if (hasCameraPermission()) {
                startCamera();
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                cameraPermission.launch(Manifest.permission.CAMERA);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
        });

        detector = PoseDetection.getClient(new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build());
        analysisExecutor = Executors.newSingleThreadExecutor();

        if (hasCameraPermission()) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(clockTick);
        // 从设置页授权回来
        if (!done && layoutCameraError.getVisibility() == View.VISIBLE && hasCameraPermission()) startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
    }

    /** 响铃时音量键不起作用 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (!done && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showCameraError(String msg, boolean show) {
        layoutCameraError.setVisibility(show ? View.VISIBLE : View.GONE);
        tvCameraError.setText(msg);
    }

    private void startCamera() {
        showCameraError("", false);
        tvState.setText("正在打开摄像头…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector;
                if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    frontCamera = true;
                } else {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                    frontCamera = false;
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                @SuppressWarnings("deprecation")
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, analysis);
                tvState.setText("站到镜头前，让身体入镜");
            } catch (Exception e) {
                showCameraError("摄像头打不开：" + e.getMessage() + "。闹钟会一直响，修好后点重试。", true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        Image image = proxy.getImage();
        if (image == null || done) {
            proxy.close();
            return;
        }
        int rotation = proxy.getImageInfo().getRotationDegrees();
        boolean swap = rotation == 90 || rotation == 270;
        int w = swap ? proxy.getHeight() : proxy.getWidth();
        int h = swap ? proxy.getWidth() : proxy.getHeight();
        detector.process(InputImage.fromMediaImage(image, rotation))
                .addOnSuccessListener(ContextCompat.getMainExecutor(this), pose -> onPose(pose, w, h))
                .addOnCompleteListener(task -> proxy.close());
    }

    private void onPose(Pose pose, int imageW, int imageH) {
        if (done) return;
        float[][] pts = null;
        if (!pose.getAllPoseLandmarks().isEmpty()) {
            pts = new float[33][];
            for (PoseLandmark lm : pose.getAllPoseLandmarks()) {
                int t = lm.getLandmarkType();
                if (t < 33) pts[t] = new float[]{lm.getPosition().x, lm.getPosition().y, lm.getInFrameLikelihood()};
            }
        }
        PoseMission.Feedback f = mission.update(pts, System.currentTimeMillis());
        overlay.setPose(pts, imageW, imageH, frontCamera, f.active);
        render(f);
        if (mission.isDone()) finishMission();
    }

    private void render(PoseMission.Feedback f) {
        tvCount.setText(String.valueOf(mission.value()));
        tvTarget.setText("/ " + mission.target + " " + PoseMission.unit(mission.type));
        progress.setProgressCompat(Math.round(mission.fraction() * 100), true);
        if (f != null) {
            tvState.setText(f.info);
            tvState.setTextColor(ContextCompat.getColor(this, f.active ? R.color.ok : R.color.hazard));
        }
    }

    private void finishMission() {
        done = true;
        AlarmService.stop(this);
        tvTitle.setText("完成！闹钟已停");
        tvState.setText("早上好。");
        tvState.setTextColor(ContextCompat.getColor(this, R.color.ok));
        tvHint.setVisibility(View.GONE);
        btnDone.setVisibility(View.VISIBLE);
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll();
        } catch (Exception ignored) {
        }
        overlay.setPose(null, 1, 1, false, false);
    }
}

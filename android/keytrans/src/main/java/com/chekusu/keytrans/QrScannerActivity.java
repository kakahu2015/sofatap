package com.chekusu.keytrans;

import android.Manifest;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only CameraX QR scanner for desktop HostTap pairing links. */
public final class QrScannerActivity extends ComponentActivity {
    static final String EXTRA_PAIRING_LINK = "com.chekusu.keytrans.PAIRING_LINK";
    private static final int REQUEST_CAMERA = 31;
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int MINT = Color.rgb(142, 227, 193);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> main.post(command);
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final MultiFormatReader reader = new MultiFormatReader();
    private PreviewView previewView;
    private TextView status;
    private LinearLayout permissionPanel;
    private ProcessCameraProvider cameraProvider;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private Preview preview;
    private ImageAnalysis analysis;
    private boolean cameraBound;
    private volatile boolean completed;
    private long lastAnalysisAt;
    private long lastInvalidNoticeAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureReader();
        buildScreen();
        requestCamera();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            permissionPanel.setVisibility(View.GONE);
            if (cameraProvider == null) startCamera();
            else bindUseCases();
        }
    }

    @Override protected void onPause() {
        unbindCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        unbindCamera();
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CAMERA) return;
        if (hasCameraPermission()) {
            permissionPanel.setVisibility(View.GONE);
            startCamera();
        } else {
            showPermissionRequired();
        }
    }

    private void configureReader() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new ScanGuide(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(10), dp(12), dp(10));
        topBar.setBackgroundColor(0xb9000000);
        TextView title = text(getString(R.string.qr_scan_title), 18, Color.WHITE, true);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button(R.string.qr_scan_cancel);
        close.setOnClickListener(view -> finish());
        topBar.addView(close, new LinearLayout.LayoutParams(-2, dp(42)));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(topBar, topParams);

        status = text(getString(R.string.qr_scan_instruction), 14, Color.WHITE, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(12), dp(18), dp(12));
        status.setBackgroundColor(0xb9000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        statusParams.leftMargin = dp(18);
        statusParams.rightMargin = dp(18);
        statusParams.bottomMargin = dp(20);
        root.addView(status, statusParams);

        permissionPanel = new LinearLayout(this);
        permissionPanel.setOrientation(LinearLayout.VERTICAL);
        permissionPanel.setGravity(Gravity.CENTER);
        permissionPanel.setPadding(dp(22), dp(20), dp(22), dp(16));
        permissionPanel.setBackground(roundRect(0xf2ffffff, 18));
        TextView permissionMessage = text(getString(R.string.qr_scan_camera_denied), 16, INK, true);
        permissionMessage.setGravity(Gravity.CENTER);
        permissionPanel.addView(permissionMessage, new LinearLayout.LayoutParams(-1, -2));
        Button settings = button(R.string.qr_scan_open_settings);
        settings.setOnClickListener(view -> openAppSettings());
        permissionPanel.addView(settings, topMargin(-1, dp(44), 14));
        Button cancel = button(R.string.qr_scan_cancel);
        cancel.setOnClickListener(view -> finish());
        permissionPanel.addView(cancel, topMargin(-1, dp(44), 6));
        permissionPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams permissionParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        permissionParams.leftMargin = dp(28);
        permissionParams.rightMargin = dp(28);
        root.addView(permissionPanel, permissionParams);
        setContentView(root);
    }

    private void requestCamera() {
        if (hasCameraPermission()) startCamera();
        else requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionRequired() {
        permissionPanel.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
    }

    private void openAppSettings() {
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getPackageName(), null));
        startActivity(settings);
    }

    private void startCamera() {
        if (cameraProvider != null) {
            bindUseCases();
            return;
        }
        if (cameraProviderFuture != null) return;
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                if (!isFinishing() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                    bindUseCases();
            } catch (Exception error) {
                showCameraUnavailable();
            }
        }, mainExecutor);
    }

    private void bindUseCases() {
        if (cameraProvider == null || cameraBound || !hasCameraPermission() || isFinishing()) return;
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        try {
            preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
            analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            cameraBound = true;
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.qr_scan_instruction);
            permissionPanel.setVisibility(View.GONE);
        } catch (Exception error) {
            showCameraUnavailable();
        }
    }

    private void unbindCamera() {
        if (cameraProvider != null && cameraBound) {
            cameraProvider.unbind(preview, analysis);
            cameraBound = false;
        }
    }

    private void showCameraUnavailable() {
        main.post(() -> {
            if (completed || isFinishing()) return;
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.qr_scan_camera_unavailable);
        });
    }

    private void analyzeFrame(ImageProxy image) {
        try {
            if (completed) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastAnalysisAt < 90) return;
            lastAnalysisAt = now;

            byte[] luminance = copyLuminancePlane(image);
            if (luminance == null) return;
            RotatedFrame frame = rotate(luminance, image.getWidth(), image.getHeight(), image.getImageInfo().getRotationDegrees());
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(frame.bytes, frame.width,
                frame.height, 0, 0, frame.width, frame.height, false);
            Result decoded = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            handleDecodedText(decoded.getText());
        } catch (ReaderException ignored) {
            // No QR code in this frame.
        } catch (RuntimeException ignored) {
            // A transient malformed frame is skipped; ImageProxy is still closed below.
        } finally {
            try { reader.reset(); }
            finally { image.close(); }
        }
    }

    private byte[] copyLuminancePlane(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ImageProxy.PlaneProxy plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int width = image.getWidth(), height = image.getHeight();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int base = buffer.position(), limit = buffer.limit();
        byte[] output = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            int outputRow = y * width;
            for (int x = 0; x < width; x++) {
                int index = row + x * pixelStride;
                if (index >= limit) return null;
                output[outputRow + x] = buffer.get(index);
            }
        }
        return output;
    }

    private void handleDecodedText(String raw) {
        final HostTapPairing pairing;
        try {
            pairing = HostTapPairing.parse(raw);
        } catch (IllegalArgumentException invalid) {
            showInvalidCode();
            return;
        }
        if (completed) return;
        completed = true;
        main.post(() -> {
            if (isFinishing()) return;
            Intent result = new Intent();
            result.putExtra(EXTRA_PAIRING_LINK, pairing.link);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void showInvalidCode() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastInvalidNoticeAt < 900) return;
        lastInvalidNoticeAt = now;
        main.post(() -> {
            if (!completed && !isFinishing()) status.setText(R.string.qr_scan_invalid);
        });
    }

    private static RotatedFrame rotate(byte[] input, int width, int height, int rotation) {
        int degrees = ((rotation % 360) + 360) % 360;
        if (degrees == 0) return new RotatedFrame(input, width, height);
        if (degrees == 180) {
            byte[] output = new byte[input.length];
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) output[(height - 1 - y) * width + (width - 1 - x)] = input[y * width + x];
            return new RotatedFrame(output, width, height);
        }
        if (degrees == 90 || degrees == 270) {
            byte[] output = new byte[input.length];
            int outputWidth = height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int targetX = degrees == 90 ? height - 1 - y : y;
                    int targetY = degrees == 90 ? x : width - 1 - x;
                    output[targetY * outputWidth + targetX] = input[y * width + x];
                }
            }
            return new RotatedFrame(output, height, width);
        }
        return new RotatedFrame(input, width, height);
    }

    private static final class RotatedFrame {
        final byte[] bytes;
        final int width, height;
        RotatedFrame(byte[] bytes, int width, int height) { this.bytes = bytes; this.width = width; this.height = height; }
    }

    private final class ScanGuide extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScanGuide(android.content.Context context) {
            super(context);
            paint.setColor(MINT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(dp(260), Math.min(getWidth() - dp(48), getHeight() - dp(180)));
            if (size <= 0) return;
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            float right = left + size, bottom = top + size, corner = Math.min(dp(28), size / 5f);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(16), dp(16), paint);
            paint.setStrokeWidth(dp(6));
            canvas.drawLine(left, top + corner, left, top, paint); canvas.drawLine(left, top, left + corner, top, paint);
            canvas.drawLine(right - corner, top, right, top, paint); canvas.drawLine(right, top, right, top + corner, paint);
            canvas.drawLine(left, bottom - corner, left, bottom, paint); canvas.drawLine(left, bottom, left + corner, bottom, paint);
            canvas.drawLine(right - corner, bottom, right, bottom, paint); canvas.drawLine(right, bottom - corner, right, bottom, paint);
            paint.setStrokeWidth(dp(4));
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(int label) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setTextColor(INK);
        value.setBackground(roundRect(0xfff2f2f7, 12));
        return value;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private LinearLayout.LayoutParams topMargin(int width, int height, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(margin);
        return params;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

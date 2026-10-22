package app.mealquo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class PlateCameraActivity extends ComponentActivity {
    private PreviewView preview;
    private ImageView frozen;
    private TextView status;
    private View shutter;
    private LifecycleCameraController camera;
    private boolean busy;
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        preview.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        frozen = new ImageView(this);
        frozen.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frozen.setVisibility(View.GONE);
        root.addView(frozen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xCC16302B);
        pill.setCornerRadius(dp(20));
        status.setBackground(pill);
        status.setText(t("Dotknij dania, żeby wyostrzyć", "Торкнись страви, щоб навести різкість", "Tap the dish to focus"));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(36);
        root.addView(status, sp);

        shutter = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(5), 0xFF16302B);
        shutter.setBackground(ring);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = dp(40);
        root.addView(shutter, bp);
        shutter.setOnClickListener(v -> takePhoto());

        TextView gallery = new TextView(this);
        gallery.setText("▦");
        gallery.setTextColor(Color.WHITE);
        gallery.setTextSize(26);
        gallery.setGravity(Gravity.CENTER);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setCornerRadius(dp(16));
        gbg.setColor(0x66000000);
        gallery.setBackground(gbg);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM | Gravity.START);
        gp.bottomMargin = dp(53);
        gp.leftMargin = dp(28);
        root.addView(gallery, gp);
        gallery.setOnClickListener(v -> pickGallery());

        setContentView(root);
        preview.post(this::openCamera);
    }

    private void openCamera() {
        camera = new LifecycleCameraController(this);
        camera.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY);
        camera.bindToLifecycle(this);
        preview.setController(camera);
    }

    private void takePhoto() {
        if (busy || done || camera == null) return;
        busy = true;
        shutter.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction(() ->
                shutter.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
        status.setText(t("Robię ostre zdjęcie…", "Роблю різке фото…", "Taking a sharp photo…"));
        camera.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                Bitmap bmp = fromProxy(image);
                image.close();
                if (bmp == null) {
                    busy = false;
                    status.setText(t("Nie wyszło. Spróbuj jeszcze raz.", "Не вийшло. Спробуй ще.", "That failed. Try again."));
                    return;
                }
                recognize(bmp);
            }

            @Override
            public void onError(ImageCaptureException exception) {
                busy = false;
                status.setText(t("Nie wyszło. Spróbuj jeszcze raz.", "Не вийшло. Спробуй ще.", "That failed. Try again."));
            }
        });
    }

    private void pickGallery() {
        if (busy || done) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Zdjęcie"), 41);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 41 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        busy = true;
        status.setText(t("Rozpoznaję danie…", "Розпізнаю страву…", "Reading the dish…"));
        new Thread(() -> {
            Bitmap bmp = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {}
            Bitmap shot = bmp;
            runOnUiThread(() -> {
                if (shot == null) {
                    busy = false;
                    status.setText(t("Nie wyszło. Spróbuj jeszcze raz.", "Не вийшло. Спробуй ще.", "That failed. Try again."));
                    return;
                }
                recognize(shot);
            });
        }).start();
    }

    private void recognize(Bitmap shot) {
        done = true;
        shutter.setVisibility(View.GONE);
        frozen.setImageBitmap(shot);
        frozen.setVisibility(View.VISIBLE);
        preview.setVisibility(View.GONE);
        if (camera != null) camera.unbind();
        status.setText(t("Zdjęcie zapisane", "Фото збережено", "Photo saved"));
        Bitmap ai = limit(shot, 1280);
        final String b64 = jpeg(ai, 85);
        if (ai != shot) ai.recycle();
        finishWith(b64, "");
    }

    private void finishWith(String b64, String json) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            File dir = new File(getCacheDir(), "plates");
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "plate.jpg"))) {
                fos.write(bytes);
            }
        } catch (Exception ignored) {}
        Intent data = new Intent();
        data.putExtra("json", json);
        setResult(RESULT_OK, data);
        runOnUiThread(this::finish);
    }

    private Bitmap fromProxy(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return null;
            Rect crop = image.getCropRect();
            if (crop != null) {
                int left = Math.max(0, crop.left);
                int top = Math.max(0, crop.top);
                int right = Math.min(bmp.getWidth(), crop.right);
                int bottom = Math.min(bmp.getHeight(), crop.bottom);
                if (right - left > 40 && bottom - top > 40) {
                    Bitmap cut = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top);
                    if (cut != bmp) bmp.recycle();
                    bmp = cut;
                }
            }
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                if (turned != bmp) bmp.recycle();
                bmp = turned;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap limit(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= max && h <= max) return src;
        float s = Math.min(max / (float) w, max / (float) h);
        return Bitmap.createScaledBitmap(src, Math.round(w * s), Math.round(h * s), true);
    }

    private String jpeg(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String t(String pl, String uk, String en) {
        if ("uk".equals(Gemini.lang)) return uk;
        if ("en".equals(Gemini.lang)) return en;
        return pl;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

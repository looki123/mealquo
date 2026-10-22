package app.mealquo;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.InputStream;

// Zdjęcia z galerii: dekodowanie z próbkowaniem (duże zdjęcia nie zajmują setek MB) i obrót według EXIF
final class Photos {
    static Bitmap fromUri(ContentResolver cr, Uri uri, int max) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception e) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample(bounds.outWidth, bounds.outHeight, max);
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (bmp == null) return null;
        int degrees = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) degrees = degrees(new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
        } catch (Exception ignored) {
        }
        return rotate(bmp, degrees);
    }

    // największa potęga 2, po której dłuższy bok nadal ma co najmniej max
    static int sample(int w, int h, int max) {
        int side = Math.max(w, h);
        int s = 1;
        while (side / (s * 2) >= max) s *= 2;
        return s;
    }

    static Bitmap rotate(Bitmap bmp, int degrees) {
        if (degrees == 0) return bmp;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        if (turned != bmp) bmp.recycle();
        return turned;
    }

    private static int degrees(int orientation) {
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        return 0;
    }
}

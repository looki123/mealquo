package app.mealquo;

import android.graphics.RectF;

import androidx.camera.core.ImageProxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;

/** On-device food look. No network. Coarse, but immediate. */
public class FoodEye {
    static class Hit {
        boolean food;
        boolean region;
        String kind = "";
        String pl = "jedzenie";
        String uk = "їжа";
        String en = "food";
        int grams = 150;
        int kcal = 180;
        int p = 8;
        int c = 16;
        int f = 7;
        RectF box = new RectF(0.2f, 0.22f, 0.8f, 0.72f);

        String label() {
            if ("uk".equals(Gemini.lang)) return uk;
            if ("en".equals(Gemini.lang)) return en;
            return pl;
        }

        String json() {
            try {
                JSONObject item = new JSONObject();
                item.put("name_pl", pl);
                item.put("name_uk", uk);
                item.put("name_en", en);
                item.put("grams", grams);
                item.put("kcal", kcal);
                item.put("p", p);
                item.put("c", c);
                item.put("f", f);
                JSONArray items = new JSONArray();
                items.put(item);
                JSONObject root = new JSONObject();
                root.put("ready", true);
                root.put("items", items);
                root.put("source", "phone");
                JSONObject b = new JSONObject();
                b.put("x", box.left);
                b.put("y", box.top);
                b.put("w", box.width());
                b.put("h", box.height());
                root.put("box", b);
                return root.toString();
            } catch (Exception e) {
                return "{\"items\":[]}";
            }
        }
    }

    static Hit look(ImageProxy image) {
        Hit hit = new Hit();
        try {
            ImageProxy.PlaneProxy yP = image.getPlanes()[0];
            ImageProxy.PlaneProxy uP = image.getPlanes()[1];
            ImageProxy.PlaneProxy vP = image.getPlanes()[2];
            ByteBuffer yBuf = yP.getBuffer();
            ByteBuffer uBuf = uP.getBuffer();
            ByteBuffer vBuf = vP.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int rot = image.getImageInfo().getRotationDegrees();
            int yRow = yP.getRowStride();
            int yPix = Math.max(1, yP.getPixelStride());
            int uRow = uP.getRowStride();
            int uPix = Math.max(1, uP.getPixelStride());
            int vRow = vP.getRowStride();
            int vPix = Math.max(1, vP.getPixelStride());

            int step = 8;
            float mass = 0f;
            float centerMass = 0f;
            int centerSeen = 0;
            float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
            float sweet = 0, wine = 0, soup = 0, meat = 0, veg = 0, bread = 0, dairy = 0, gold = 0, fried = 0;
            long sumY = 0;
            long sumY2 = 0;
            int samples = 0;
            int edges = 0;
            int prevY = -1;

            for (int y = step; y < h - step; y += step) {
                for (int x = step; x < w - step; x += step) {
                    int yi = y * yRow + x * yPix;
                    int cx = x / 2;
                    int cy = y / 2;
                    int ui = cy * uRow + cx * uPix;
                    int vi = cy * vRow + cx * vPix;
                    if (yi < 0 || ui < 0 || vi < 0 || yi >= yBuf.limit() || ui >= uBuf.limit() || vi >= vBuf.limit()) continue;
                    int Y = yBuf.get(yi) & 255;
                    int U = uBuf.get(ui) & 255;
                    int V = vBuf.get(vi) & 255;
                    float[] view = view(x / (float) w, y / (float) h, rot);
                    float vx = view[0];
                    float vy = view[1];
                    if (vx < 0.08f || vx > 0.92f || vy < 0.1f || vy > 0.86f) continue;
                    float dx = vx - 0.5f;
                    float dy = vy - 0.42f;
                    float near = 1f - Math.min(1f, (float) Math.hypot(dx * 1.1f, dy) / 0.48f);
                    boolean mid = vx > 0.3f && vx < 0.7f && vy > 0.26f && vy < 0.6f;
                    if (mid) centerSeen++;
                    int du = U - 128;
                    int dv = V - 128;
                    int chroma = Math.abs(du) + Math.abs(dv);
                    if (Y < 28 || Y > 242 || chroma < 26 || near < 0.12f) continue;
                    boolean foil = dv > 22 && Y > 150 && chroma > 34;
                    boolean winePx = dv > 42 && du < -12 && Y < 68 && chroma > 52;
                    boolean soupPx = dv > 16 && du > -18 && du < 16 && Y >= 78 && Y < 200 && chroma > 28;
                    boolean vegPx = du < -16 && dv < 2 && Y > 40 && chroma > 28;
                    boolean crust = dv > 22 && Y > 100 && Y <= 150 && chroma > 32;
                    boolean pink = dv > 24 && du < -4 && Y > 70 && Y < 180 && chroma > 36;
                    boolean brown = dv > 14 && du > -6 && du < 18 && Y >= 40 && Y < 125 && chroma >= 38;
                    boolean dairyPx = chroma < 34 && chroma >= 26 && Y > 175;
                    boolean fruit = dv > 16 && du < 8 && Y > 36 && Y < 210 && chroma > 30;
                    boolean breadPx = Y > 145 && dv > 6 && chroma >= 26 && chroma < 48;
                    if (!(foil || winePx || soupPx || vegPx || crust || pink || brown || dairyPx || breadPx || fruit)) continue;
                    float weight = near;
                    mass += weight;
                    samples++;
                    sumY += Y;
                    sumY2 += (long) Y * Y;
                    if (prevY >= 0 && Math.abs(Y - prevY) > 16) edges++;
                    prevY = Y;
                    if (mid) centerMass += weight;
                    if (vx < minX) minX = vx;
                    if (vy < minY) minY = vy;
                    if (vx > maxX) maxX = vx;
                    if (vy > maxY) maxY = vy;
                    if (foil) gold += weight;
                    else if (soupPx && !winePx) soup += weight;
                    else if (winePx) wine += weight;
                    else if (vegPx) veg += weight;
                    else if (crust) fried += weight;
                    else if (pink) meat += weight;
                    else if (brown) sweet += weight;
                    else if (dairyPx) dairy += weight;
                    else bread += weight;
                }
            }
            if (samples < 12 || mass < 6f) return hit;
            float bw = maxX - minX;
            float bh = maxY - minY;
            if (bw < 0.04f || bh < 0.04f || (bw > 0.9f && bh > 0.75f)) return hit;
            float pad = 0.03f;
            float left = minX;
            float top = minY;
            float right = maxX;
            float bottom = maxY;
            boolean logo = bw < 0.46f && bh < 0.42f && (gold + sweet) > mass * 0.12f;
            if (logo) {
                float cx = (minX + maxX) / 2f;
                float cy = (minY + maxY) / 2f;
                float canW = Math.min(0.46f, Math.max(0.16f, bw * 1.35f));
                float canH = Math.min(0.7f, Math.max(bh * 1.8f, canW * 2.05f));
                left = cx - canW / 2f;
                right = cx + canW / 2f;
                top = cy - canH * 0.46f;
                bottom = top + canH;
            }
            hit.box = new RectF(
                    Math.max(0.02f, left - pad),
                    Math.max(0.03f, top - pad),
                    Math.min(0.98f, right + pad),
                    Math.min(0.96f, bottom + pad));
            hit.region = true;
            String kind = "food";
            float best = 0f;
            float warm = gold + sweet;
            if (gold > mass * 0.2f && warm >= fried && warm >= meat && bw < 0.72f) kind = "choco";
            else {
                if (sweet > best && bw < 0.62f) { best = sweet; kind = "sweet"; }
                if (fried > best) { best = fried; kind = "fried"; }
                if (soup > best && (soup >= wine || bw > 0.34f || bh > 0.4f)) { best = soup; kind = "soup"; }
                if (wine > best && wine > soup * 1.6f && bw < 0.38f && bh < 0.46f) { best = wine; kind = "wine"; }
                if (veg > best) { best = veg; kind = "veg"; }
                if (bread > best && bw < 0.7f) { best = bread; kind = "bread"; }
                if (dairy > best) { best = dairy; kind = "dairy"; }
                if (meat > best && gold < meat * 0.35f) kind = "meat";
            }
            if ("food".equals(kind)) return hit;
            float won = "choco".equals(kind) ? warm : best;
            if (won < mass * 0.28f) return hit;
            if (("sweet".equals(kind) || "choco".equals(kind)) && bw > 0.62f) return hit;
            if ("wine".equals(kind) && (bw > 0.4f || soup > wine * 0.45f)) return hit;
            hit.food = true;
            hit.kind = kind;
            fill(hit, kind);
        } catch (Exception ignored) {}
        return hit;
    }

    private static float[] view(float nx, float ny, int rot) {
        if (rot == 90) return new float[]{ny, 1f - nx};
        if (rot == 270) return new float[]{1f - ny, nx};
        if (rot == 180) return new float[]{1f - nx, 1f - ny};
        return new float[]{nx, ny};
    }

    private static void fill(Hit hit, String kind) {
        switch (kind) {
            case "choco":
                hit.pl = "czekoladki"; hit.uk = "цукерки"; hit.en = "chocolates";
                hit.grams = 24; hit.kcal = 120; hit.p = 2; hit.c = 14; hit.f = 7;
                break;
            case "sweet":
                hit.pl = "słodycze"; hit.uk = "солодощі"; hit.en = "sweets";
                hit.grams = 30; hit.kcal = 140; hit.p = 2; hit.c = 16; hit.f = 8;
                break;
            case "wine":
                hit.pl = "wino"; hit.uk = "вино"; hit.en = "wine";
                hit.grams = 150; hit.kcal = 125; hit.p = 0; hit.c = 4; hit.f = 0;
                break;
            case "soup":
                hit.pl = "zupa"; hit.uk = "суп"; hit.en = "soup";
                hit.grams = 350; hit.kcal = 180; hit.p = 6; hit.c = 18; hit.f = 7;
                break;
            case "drink":
                hit.pl = "napój"; hit.uk = "напій"; hit.en = "drink";
                hit.grams = 250; hit.kcal = 90; hit.p = 0; hit.c = 22; hit.f = 0;
                break;
            case "veg":
                hit.pl = "warzywa"; hit.uk = "овочі"; hit.en = "vegetables";
                hit.grams = 150; hit.kcal = 45; hit.p = 2; hit.c = 7; hit.f = 0;
                break;
            case "fried":
                hit.pl = "danie panierowane"; hit.uk = "панірована страва"; hit.en = "breaded dish";
                hit.grams = 180; hit.kcal = 320; hit.p = 24; hit.c = 16; hit.f = 16;
                break;
            case "meat":
                hit.pl = "mięso"; hit.uk = "м'ясо"; hit.en = "meat";
                hit.grams = 150; hit.kcal = 240; hit.p = 28; hit.c = 0; hit.f = 14;
                break;
            case "bread":
                hit.pl = "pieczywo"; hit.uk = "випічка"; hit.en = "bread";
                hit.grams = 80; hit.kcal = 210; hit.p = 6; hit.c = 36; hit.f = 4;
                break;
            case "dairy":
                hit.pl = "nabiał"; hit.uk = "молочне"; hit.en = "dairy";
                hit.grams = 150; hit.kcal = 130; hit.p = 7; hit.c = 8; hit.f = 8;
                break;
            default:
                break;
        }
    }
}

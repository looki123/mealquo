package app.mealquo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Maps {
    static final String KEY = BuildConfig.MAPS_KEY;

    static class Shot {
        String uri;
        byte[] jpeg;
    }

    static List<Shot> photos(String query) {
        List<Shot> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        try {
            String q = query.trim();
            int at = q.indexOf("query=");
            if (at >= 0) {
                String rest = q.substring(at + 6);
                int amp = rest.indexOf('&');
                if (amp >= 0) rest = rest.substring(0, amp);
                q = java.net.URLDecoder.decode(rest, "UTF-8");
            }
            Double lat = null;
            Double lon = null;
            java.util.regex.Matcher coords = java.util.regex.Pattern.compile("(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)").matcher(q);
            if (coords.find()) {
                lat = Double.parseDouble(coords.group(1));
                lon = Double.parseDouble(coords.group(2));
                q = (q.substring(0, coords.start()) + q.substring(coords.end())).replaceAll("\\s+", " ").trim();
            }
            if (q.isEmpty()) q = query.trim();
            JSONObject body = new JSONObject();
            body.put("textQuery", q);
            body.put("maxResultCount", 1);
            if (lat != null && lon != null) {
                JSONObject bias = new JSONObject();
                JSONObject circle = new JSONObject();
                JSONObject center = new JSONObject();
                center.put("latitude", lat);
                center.put("longitude", lon);
                circle.put("center", center);
                circle.put("radius", 500);
                bias.put("circle", circle);
                body.put("locationBias", bias);
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL("https://places.googleapis.com/v1/places:searchText").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Goog-Api-Key", KEY);
            conn.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.photos");
            conn.setDoOutput(true);
            conn.getOutputStream().write(bytes);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return out;
            JSONObject root = new JSONObject(read(conn));
            JSONArray places = root.optJSONArray("places");
            if (places == null || places.length() == 0) return out;
            JSONArray photos = places.getJSONObject(0).optJSONArray("photos");
            if (photos == null) return out;
            int n = Math.min(6, photos.length());
            for (int i = 0; i < n; i++) {
                String name = photos.getJSONObject(i).optString("name", "");
                if (name.isEmpty()) continue;
                String metaUrl = "https://places.googleapis.com/v1/" + name
                        + "/media?maxHeightPx=360&skipHttpRedirect=true&key=" + URLEncoder.encode(KEY, "UTF-8");
                HttpURLConnection meta = (HttpURLConnection) new URL(metaUrl).openConnection();
                meta.setConnectTimeout(6000);
                meta.setReadTimeout(8000);
                meta.setRequestProperty("X-Goog-Api-Key", KEY);
                if (meta.getResponseCode() < 200 || meta.getResponseCode() >= 300) continue;
                String uri = new JSONObject(read(meta)).optString("photoUri", "");
                if (!uri.startsWith("http")) continue;
                Shot shot = new Shot();
                shot.uri = uri;
                shot.jpeg = download(uri);
                out.add(shot);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static byte[] download(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 500000) return null;
                raw.write(buf, 0, n);
            }
            return raw.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) raw.write(buf, 0, n);
        return raw.toString("UTF-8");
    }
}

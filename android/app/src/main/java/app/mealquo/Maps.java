package app.mealquo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
            String found = Net.google("https://places.googleapis.com/v1/places:searchText", body.toString(),
                    "places.displayName,places.photos", 12000);
            if (found.isEmpty()) return out;
            JSONArray places = new JSONObject(found).optJSONArray("places");
            if (places == null || places.length() == 0) return out;
            JSONArray photos = places.getJSONObject(0).optJSONArray("photos");
            if (photos == null) return out;
            int n = Math.min(6, photos.length());
            // adres i plik każdego zdjęcia równolegle (wcześniej 12 zapytań po kolei)
            List<Future<Shot>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String name = photos.getJSONObject(i).optString("name", "");
                if (name.isEmpty()) continue;
                jobs.add(Net.POOL.submit(() -> shot(name)));
            }
            for (Future<Shot> job : jobs) {
                try {
                    Shot shot = job.get(20, TimeUnit.SECONDS);
                    if (shot != null) out.add(shot);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Shot shot(String name) {
        try {
            String meta = Net.google("https://places.googleapis.com/v1/" + name
                    + "/media?maxHeightPx=360&skipHttpRedirect=true", null, null, 8000);
            if (meta.isEmpty()) return null;
            String uri = new JSONObject(meta).optString("photoUri", "");
            if (!uri.startsWith("http")) return null;
            Shot shot = new Shot();
            shot.uri = uri;
            shot.jpeg = download(uri);
            return shot;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] download(String address) {
        return Net.getBytes(address, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36", 8000, 500000);
    }
}

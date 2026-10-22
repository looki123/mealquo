package app.mealquo;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Live Gemini transcription. Partial text while speaking, final text on a short pause. */
public class LiveSpeech {
    interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onError();
    }

    private final Listener listener;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean finished;
    private WebSocket socket;
    private AudioRecord mic;
    private final StringBuilder committed = new StringBuilder();
    private String interim = "";

    LiveSpeech(Listener listener) {
        this.listener = listener;
    }

    void start(String languageCode) {
        running = true;
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + Gemini.KEY)
                .build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(setup(languageCode));
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handle(text);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                fail();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (running) fail();
            }
        });
    }

    void stop() {
        running = false;
        try { if (mic != null) mic.stop(); } catch (Exception ignored) {}
        if (socket != null) {
            try { socket.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}"); } catch (Exception ignored) {}
            socket.close(1000, "stop");
        }
    }

    private String setup(String languageCode) {
        try {
            JSONObject tr = new JSONObject();
            tr.put("languageCodes", new JSONArray().put(languageCode));
            JSONObject gen = new JSONObject();
            gen.put("responseModalities", new JSONArray().put("TEXT"));
            JSONObject setup = new JSONObject();
            setup.put("model", "models/gemini-3.5-transcribe-live");
            setup.put("generationConfig", gen);
            setup.put("inputAudioTranscription", tr);
            return new JSONObject().put("setup", setup).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void handle(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has("error")) { fail(); return; }
            if (o.has("setupComplete") && !ready) {
                ready = true;
                startMic();
                return;
            }
            JSONObject content = o.optJSONObject("serverContent");
            if (content == null) return;
            JSONObject live = content.optJSONObject("interimInputTranscription");
            if (live == null) live = content.optJSONObject("interim_input_transcription");
            if (live != null) {
                interim = live.optString("text", "");
                listener.onPartial(shown());
                return;
            }
            JSONObject fin = content.optJSONObject("inputTranscription");
            if (fin == null) fin = content.optJSONObject("input_transcription");
            if (fin != null) {
                String piece = fin.optString("text", "").trim();
                if (!piece.isEmpty()) {
                    if (committed.length() > 0) committed.append(' ');
                    committed.append(piece);
                }
                interim = "";
                String all = committed.toString().trim();
                listener.onPartial(all);
                if (!all.isEmpty()) finish(all);
            }
        } catch (Exception ignored) {}
    }

    private String shown() {
        String base = committed.toString().trim();
        if (interim == null || interim.isEmpty()) return base;
        return base.isEmpty() ? interim : base + " " + interim;
    }

    private void finish(String text) {
        if (finished) return;
        finished = true;
        running = false;
        listener.onFinal(text);
        stop();
    }

    private void fail() {
        if (finished) return;
        finished = true;
        running = false;
        listener.onError();
        try { if (mic != null) mic.stop(); } catch (Exception ignored) {}
    }

    private void startMic() {
        new Thread(() -> {
            int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int chunk = 3200;
            if (min < chunk) min = chunk;
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
            try {
                mic.startRecording();
                byte[] buf = new byte[chunk];
                while (running && ready) {
                    int n = mic.read(buf, 0, buf.length);
                    if (n <= 0 || socket == null) continue;
                    String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
                    JSONObject audio = new JSONObject();
                    audio.put("data", b64);
                    audio.put("mimeType", "audio/pcm;rate=16000");
                    JSONObject input = new JSONObject();
                    input.put("audio", audio);
                    socket.send(new JSONObject().put("realtimeInput", input).toString());
                }
            } catch (Exception e) {
                fail();
            } finally {
                try { mic.release(); } catch (Exception ignored) {}
            }
        }).start();
    }
}

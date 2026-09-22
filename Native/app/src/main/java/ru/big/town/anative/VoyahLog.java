package ru.big.town.anative;

import android.os.SystemClock;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small process-wide deduplicating/rate-limited logger for hot integration paths. */
final class VoyahLog {
    private static final int MAX_KEYS = 96;
    private static final long REPEAT_WINDOW_MS = 10_000L;
    private static final Map<String, Long> LAST = new LinkedHashMap<String, Long>(MAX_KEYS, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_KEYS;
        }
    };

    private VoyahLog() {}

    static void i(String tag, String key, String message) { write(Log.INFO, tag, key, message); }
    static void w(String tag, String key, String message) { write(Log.WARN, tag, key, message); }

    private static void write(int priority, String tag, String key, String message) {
        long now = SystemClock.elapsedRealtime();
        String identity = tag + '|' + key + '|' + message;
        synchronized (LAST) {
            Long previous = LAST.get(identity);
            if (previous != null && now - previous < REPEAT_WINDOW_MS) return;
            LAST.put(identity, now);
        }
        Log.println(priority, tag, message);
    }
}

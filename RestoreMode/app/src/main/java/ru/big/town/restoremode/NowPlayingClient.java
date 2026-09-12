package ru.big.town.restoremode;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.PlaybackState;
import android.net.Uri;

import java.io.InputStream;

/**
 * Клиент «сейчас играет» — единая точка доступа UI VoyahTune к метаданным текущего трека,
 * которые публикует Native ({@code NowPlayingService}/{@code NowPlayingProvider}).
 *
 * <p>Две модели использования:
 * <ul>
 *   <li><b>pull:</b> {@link #query(Context)} — прочитать снимок сейчас (напр. при открытии экрана);
 *       {@link #loadArt(Context)} — подгрузить обложку;</li>
 *   <li><b>push:</b> подписаться на broadcast {@link #ACTION_NOW_PLAYING} (extras те же поля) для живого
 *       обновления; при подписке вызвать {@link #requestRefresh(Context)}, чтобы Native сразу отдал текущий
 *       снимок.</li>
 * </ul>
 * Ничего не завязано на флейвор — работает и в full, и в light (Native priv-app в обоих).
 */
public final class NowPlayingClient {

    public static final String ACTION_NOW_PLAYING         = "ru.big.town.anative.NOW_PLAYING";
    public static final String ACTION_NOW_PLAYING_SOURCES = "ru.big.town.anative.NOW_PLAYING_SOURCES";
    public static final String ACTION_REQUEST_NOW_PLAYING = "ru.big.town.anative.REQUEST_NOW_PLAYING";

    private static final String AUTHORITY = "ru.big.town.anative.nowplaying";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri SOURCES_URI = Uri.parse("content://" + AUTHORITY + "/sources");
    public static final Uri ART_URI     = Uri.parse("content://" + AUTHORITY + "/art");

    private NowPlayingClient() {}

    /** Снимок текущего трека. Пустой title = ничего не играет / нет данных. */
    public static final class NowPlaying {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String packageName = "";
        public String appLabel = "";
        public int  state = PlaybackState.STATE_NONE;
        public long position = 0L;
        public long duration = 0L;
        public boolean hasArt = false;
        public long updatedAt = 0L;

        public boolean isPlaying() { return state == PlaybackState.STATE_PLAYING; }
        public boolean isEmpty()   { return title == null || title.isEmpty(); }
    }

    /** One package with a currently published MediaSession (paused sessions are included). */
    public static final class Source {
        public String packageName = "";
        public String appLabel = "";
        public String title = "";
        public String artist = "";
        public int state = PlaybackState.STATE_NONE;
        public boolean selected = false;
    }

    /** Читает снимок из провайдера Native. Возвращает пустой {@link NowPlaying} при недоступности. */
    public static NowPlaying query(Context ctx) {
        NowPlaying np = new NowPlaying();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(CONTENT_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                np.title       = str(c, "title");
                np.artist      = str(c, "artist");
                np.album       = str(c, "album");
                np.packageName = str(c, "package");
                np.appLabel    = str(c, "appLabel");
                np.state       = intOf(c, "state", PlaybackState.STATE_NONE);
                np.position    = longOf(c, "position");
                np.duration    = longOf(c, "duration");
                np.hasArt      = intOf(c, "hasArt", 0) == 1;
                np.updatedAt   = longOf(c, "updatedAt");
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return np;
    }

    /** Returns only real active MediaSession sources, never every installed music application. */
    public static java.util.List<Source> querySources(Context ctx) {
        java.util.List<Source> result = new java.util.ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SOURCES_URI, null, null, null, null);
            if (c != null) {
                int pkg = c.getColumnIndex("package");
                int label = c.getColumnIndex("appLabel");
                int title = c.getColumnIndex("title");
                int artist = c.getColumnIndex("artist");
                int state = c.getColumnIndex("state");
                int selected = c.getColumnIndex("selected");
                while (c.moveToNext()) {
                    Source source = new Source();
                    if (pkg >= 0) source.packageName = nz(c.getString(pkg));
                    if (label >= 0) source.appLabel = nz(c.getString(label));
                    if (title >= 0) source.title = nz(c.getString(title));
                    if (artist >= 0) source.artist = nz(c.getString(artist));
                    if (state >= 0) source.state = c.getInt(state);
                    if (selected >= 0) source.selected = c.getInt(selected) == 1;
                    if (!source.packageName.isEmpty()) result.add(source);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    /** Asks Native to make an active MediaSession package the selected source. */
    public static boolean selectSource(Context ctx, String packageName) {
        if (ctx == null || packageName == null || packageName.trim().isEmpty()) return false;
        try {
            android.os.Bundle result = ctx.getContentResolver().call(
                    CONTENT_URI, "select_source", packageName.trim(), null);
            return result != null && result.getBoolean("selected", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Снимок из extras broadcast'а {@link #ACTION_NOW_PLAYING} (без запроса к провайдеру). */
    public static NowPlaying fromBroadcast(Intent i) {
        NowPlaying np = new NowPlaying();
        if (i == null) return np;
        np.title       = nz(i.getStringExtra("title"));
        np.artist      = nz(i.getStringExtra("artist"));
        np.album       = nz(i.getStringExtra("album"));
        np.packageName = nz(i.getStringExtra("package"));
        np.appLabel    = nz(i.getStringExtra("appLabel"));
        np.state       = i.getIntExtra("state", PlaybackState.STATE_NONE);
        np.position    = i.getLongExtra("position", 0L);
        np.duration    = i.getLongExtra("duration", 0L);
        np.hasArt      = i.getBooleanExtra("hasArt", false);
        np.updatedAt   = i.getLongExtra("updatedAt", 0L);
        return np;
    }

    /** Подгружает обложку из провайдера Native для обычной карточки UI. */
    public static Bitmap loadArt(Context ctx) {
        return loadArt(ctx, 384);
    }

    /**
     * Decodes only the resolution requested by a consumer. Album art is received over Binder and
     * may originate from an arbitrary player, so decoding an original 2–4K image for a 66 dp card
     * is avoidable heap pressure.
     */
    public static Bitmap loadArt(Context ctx, int maxEdgePx) {
        if (ctx == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(ART_URI)) {
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (Exception ignored) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int requested = Math.max(1, maxEdgePx);
        int sample = 1;
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        while (edge / (sample * 2) >= requested) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        try (InputStream is = ctx.getContentResolver().openInputStream(ART_URI)) {
            return is == null ? null : BitmapFactory.decodeStream(is, null, decode);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Просит Native немедленно опубликовать текущий снимок (broadcast придёт слушателям). */
    public static void requestRefresh(Context ctx) {
        try {
            Intent i = new Intent(ACTION_REQUEST_NOW_PLAYING);
            i.setPackage("ru.big.town.anative");
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // -- helpers --
    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? nz(c.getString(i)) : "";
    }
    private static int intOf(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getInt(i) : def;
    }
    private static long longOf(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getLong(i) : 0L;
    }
    private static String nz(String s) { return s == null ? "" : s; }
}

package ru.big.town.restoremode;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered enabled cards for the OEM music-card region. */
final class CustomWidgetStore {
    static final String KEY = "customWidgetCards";
    static final String PREFIX = "widgets-v1|";

    static final class Card {
        final String kind;
        final String packageName;
        final int dpi;
        Card(String kind) { this(kind, null, 0); }
        Card(String kind, String packageName, int dpi) {
            this.kind = kind; this.packageName = packageName; this.dpi = dpi;
        }
        String encode() { return "app".equals(kind) ? "app:" + packageName + ':' + dpi : kind; }
    }

    private CustomWidgetStore() {}

    static List<Card> load(SharedPreferences prefs) {
        return decode(prefs.getString(KEY, PREFIX + "music"));
    }

    static String loadEncoded(SharedPreferences prefs) { return encode(load(prefs)); }

    static void save(SharedPreferences prefs, List<Card> cards) {
        prefs.edit().putString(KEY, encode(cards)).apply();
    }

    static List<Card> decode(String encoded) {
        List<Card> result = new ArrayList<>();
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            result.add(new Card("music"));
            return result;
        }
        Set<String> singletons = new HashSet<>();
        for (String raw : encoded.substring(PREFIX.length()).split("\\|")) {
            Card card = parseCard(raw.trim());
            if (card == null) continue;
            if (!"app".equals(card.kind) && !singletons.add(card.kind)) continue;
            result.add(card);
            if (result.size() == 12) break;
        }
        if (result.isEmpty()) result.add(new Card("music"));
        return result;
    }

    static String encode(List<Card> cards) {
        StringBuilder out = new StringBuilder(PREFIX);
        Set<String> singleton = new HashSet<>();
        int count = 0;
        if (cards != null) for (Card card : cards) {
            Card valid = card == null ? null : parseCard(card.encode());
            if (valid == null || (!"app".equals(valid.kind) && !singleton.add(valid.kind))) continue;
            if (count++ > 0) out.append('|');
            out.append(valid.encode());
            if (count == 12) break;
        }
        return count == 0 ? PREFIX + "music" : out.toString();
    }

    private static Card parseCard(String raw) {
        if ("music".equals(raw) || "trip".equals(raw) || "car".equals(raw)) return new Card(raw);
        if (!raw.startsWith("app:")) return null;
        int separator = raw.lastIndexOf(':');
        if (separator <= 4) return null;
        String pkg = raw.substring(4, separator);
        int dpi;
        try { dpi = Integer.parseInt(raw.substring(separator + 1)); }
        catch (NumberFormatException error) { return null; }
        return ClusterAppStore.validPackage(pkg) && (dpi == 0 || (dpi >= 100 && dpi <= 640))
                ? new Card("app", pkg, dpi) : null;
    }
}

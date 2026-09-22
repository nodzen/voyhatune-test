package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compact schema used across RestoreMode, Native and the launcher hook. */
final class WidgetCardPolicy {
    static final String PREFIX = "widgets-v1|";
    static final String MUSIC = "music";
    static final String TRIP = "trip";
    static final String CAR = "car";

    static final class Card {
        final String kind;
        final String packageName;
        final int dpi;
        Card(String kind, String packageName, int dpi) {
            this.kind = kind;
            this.packageName = packageName;
            this.dpi = dpi;
        }
        String encode() {
            return "app".equals(kind) ? "app:" + packageName + ':' + dpi : kind;
        }
    }

    private WidgetCardPolicy() {}

    static List<Card> parse(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return Collections.singletonList(new Card(MUSIC, null, 0));
        }
        List<Card> result = new ArrayList<>();
        Set<String> singletonKinds = new HashSet<>();
        String body = encoded.substring(PREFIX.length());
        for (String raw : body.split("\\|")) {
            Card card = parseCard(raw.trim());
            if (card == null) continue;
            if (!"app".equals(card.kind) && !singletonKinds.add(card.kind)) continue;
            result.add(card);
            if (result.size() == 12) break;
        }
        if (result.isEmpty()) result.add(new Card(MUSIC, null, 0));
        return result;
    }

    static String encode(List<Card> cards) {
        StringBuilder out = new StringBuilder(PREFIX);
        List<Card> valid = parse(PREFIX + joinRaw(cards));
        for (int i = 0; i < valid.size(); i++) {
            if (i > 0) out.append('|');
            out.append(valid.get(i).encode());
        }
        return out.toString();
    }

    static int nextIndex(int current, int direction, int count) {
        if (count <= 0) return 0;
        int normalized = current < 0 || current >= count ? 0 : current;
        return (normalized + (direction < 0 ? -1 : 1) + count) % count;
    }

    private static String joinRaw(List<Card> cards) {
        if (cards == null) return MUSIC;
        StringBuilder value = new StringBuilder();
        for (Card card : cards) {
            if (card == null) continue;
            if (value.length() > 0) value.append('|');
            value.append(card.encode());
        }
        return value.length() == 0 ? MUSIC : value.toString();
    }

    private static Card parseCard(String raw) {
        if (MUSIC.equals(raw) || TRIP.equals(raw) || CAR.equals(raw)) {
            return new Card(raw, null, 0);
        }
        if (!raw.startsWith("app:")) return null;
        int separator = raw.lastIndexOf(':');
        if (separator <= 4) return null;
        String pkg = raw.substring(4, separator);
        if (!ClusterLaunchPolicy.allows(pkg, pkg)) return null;
        int dpi;
        try { dpi = Integer.parseInt(raw.substring(separator + 1)); }
        catch (NumberFormatException error) { return null; }
        if (dpi != 0 && (dpi < 100 || dpi > 640)) return null;
        return new Card("app", pkg, dpi);
    }
}

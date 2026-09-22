package ru.big.town.anative;

import java.util.List;
import java.util.Locale;

/** Deterministic, firmware-safe selection of the OEM media display. */
final class ClusterDisplayPolicy {
    private static final String OEM_MEDIA_PACKAGE = "com.qinggan.instrumentcard";
    private static final String OEM_MEDIA_ACTIVITY =
            "com.qinggan.instrumentcard.ScreenActivity";

    static final class Candidate {
        final int id;
        final String name;
        final boolean presentation;
        final int width;
        final int height;

        Candidate(int id, String name, boolean presentation, int width, int height) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.presentation = presentation;
            this.width = width;
            this.height = height;
        }
    }

    static final class TaskCandidate {
        final int displayId;
        final String packageName;
        final String className;

        TaskCandidate(int displayId, String packageName, String className) {
            this.displayId = displayId;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
        }
    }

    private ClusterDisplayPolicy() {}

    static int choose(List<Candidate> displays) {
        int bestId = -1;
        int bestScore = Integer.MIN_VALUE;
        if (displays == null) return -1;
        for (Candidate display : displays) {
            if (display == null || display.id == 0 || display.width <= 0 || display.height <= 0) continue;
            String name = display.name.toLowerCase(Locale.ROOT);
            int score = 0;
            if (name.contains("cluster-media-display")) score += 100;
            else if (name.contains("cluster") && name.contains("media")) score += 80;
            else if (name.contains("instrument") && name.contains("media")) score += 70;
            else continue; // unknown geometry/name is deliberately fail-closed
            if (display.presentation) score += 10;
            // Media-card displays are landscape, but dimensions are never used as identity.
            if (display.width >= display.height) score += 2;
            if (score > bestScore) {
                bestScore = score;
                bestId = display.id;
            }
        }
        return bestId;
    }

    /** Private OEM displays may be absent from DisplayManager until this UID owns a task on them. */
    static int chooseOemTaskFallback(List<TaskCandidate> tasks) {
        if (tasks == null) return -1;
        for (TaskCandidate task : tasks) {
            if (task != null && task.displayId > 1
                    && OEM_MEDIA_PACKAGE.equals(task.packageName)
                    && OEM_MEDIA_ACTIVITY.equals(task.className)) {
                return task.displayId;
            }
        }
        return -1;
    }
}

package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android-free normalized view of one ActivityManager task query. */
final class SplitHostTaskSnapshot {
    static final class TaskRecord {
        final int taskId;
        /** Top activity package, or the base package when the top activity is unavailable. */
        final String packageName;
        /** A task can temporarily show another package on top (chooser/external activity). */
        final String basePackageName;
        final Integer displayId;

        TaskRecord(int taskId, String packageName, Integer displayId) {
            this(taskId, packageName, null, displayId);
        }

        TaskRecord(int taskId, String topPackageName, String basePackageName, Integer displayId) {
            this.taskId = taskId;
            this.packageName = topPackageName != null && !topPackageName.isEmpty()
                    ? topPackageName : basePackageName;
            this.basePackageName = basePackageName;
            this.displayId = displayId;
        }

        boolean belongsTo(String packageName) {
            if (packageName == null || packageName.isEmpty()) return false;
            return packageName.equals(this.packageName) || packageName.equals(basePackageName);
        }
    }

    private static final class PackageState {
        final Set<Integer> displayIds = new HashSet<>();
        boolean hasUnknownDisplay;
    }

    private final boolean known;
    private final List<TaskRecord> tasks;
    private final Map<String, PackageState> packages;

    private SplitHostTaskSnapshot(boolean known, List<TaskRecord> taskRecords) {
        this.known = known;
        this.tasks = Collections.unmodifiableList(new ArrayList<>(taskRecords));
        this.packages = new HashMap<>();
        if (!known) return;
        for (TaskRecord task : taskRecords) {
            if (task == null) continue;
            addPackageState(task.packageName, task.displayId);
            if (task.basePackageName != null && !task.basePackageName.equals(task.packageName)) {
                addPackageState(task.basePackageName, task.displayId);
            }
        }
    }

    private void addPackageState(String packageName, Integer displayId) {
        if (packageName == null || packageName.isEmpty()) return;
        PackageState state = packages.get(packageName);
        if (state == null) {
            state = new PackageState();
            packages.put(packageName, state);
        }
        if (displayId == null) state.hasUnknownDisplay = true;
        else state.displayIds.add(displayId);
    }

    static SplitHostTaskSnapshot unknown() {
        return new SplitHostTaskSnapshot(false, Collections.emptyList());
    }

    static SplitHostTaskSnapshot known(List<TaskRecord> tasks) {
        return new SplitHostTaskSnapshot(true,
                tasks == null ? Collections.emptyList() : tasks);
    }

    /**
     * Fail-open parity with the old watchdog: an unavailable snapshot or an unreadable display id
     * means "alive". A known task on another display only means the pane is dead.
     */
    boolean isAlive(String packageName, int displayId) {
        if (!known || packageName == null || packageName.isEmpty()) return true;
        PackageState state = packages.get(packageName);
        if (state == null) return false;
        return state.hasUnknownDisplay || state.displayIds.contains(displayId);
    }

    List<TaskRecord> tasks() {
        return tasks;
    }
}

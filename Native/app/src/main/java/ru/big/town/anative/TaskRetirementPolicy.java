package ru.big.town.anative;

/** Decides whether a task must be retired before a display transition. */
final class TaskRetirementPolicy {
    private TaskRetirementPolicy() {}

    static boolean shouldRetire(String requestedPackage, int targetDisplay,
                                SplitHostTaskSnapshot.TaskRecord task) {
        if (task == null || requestedPackage == null || !task.belongsTo(requestedPackage)) return false;
        // Unknown display is not destructive evidence. A task already on the target is reused.
        return task.displayId != null && task.displayId != targetDisplay;
    }
}

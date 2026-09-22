package ru.big.town.anative;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public class ClusterDisplayPolicyTest {
    @Test public void exactPurposeNameWinsWithoutFixedId() {
        assertEquals(7, ClusterDisplayPolicy.choose(Arrays.asList(
                new ClusterDisplayPolicy.Candidate(2, "Instrument Media", true, 800, 480),
                new ClusterDisplayPolicy.Candidate(7, "Cluster-Media-Display", true, 960, 360))));
    }

    @Test public void unknownFirmwareFailsClosed() {
        assertEquals(-1, ClusterDisplayPolicy.choose(Arrays.asList(
                new ClusterDisplayPolicy.Candidate(5, "Rear display", true, 1920, 1080))));
    }

    @Test public void privateDisplayFallsBackOnlyToKnownOemMediaTask() {
        assertEquals(9, ClusterDisplayPolicy.chooseOemTaskFallback(Arrays.asList(
                new ClusterDisplayPolicy.TaskCandidate(4,
                        "com.qinggan.app.launcher", "com.qinggan.app.launcher.MainActivity"),
                new ClusterDisplayPolicy.TaskCandidate(9,
                        "com.qinggan.instrumentcard",
                        "com.qinggan.instrumentcard.ScreenActivity"))));
        assertEquals(-1, ClusterDisplayPolicy.chooseOemTaskFallback(Arrays.asList(
                new ClusterDisplayPolicy.TaskCandidate(9,
                        "com.example.media", "com.example.media.ScreenActivity"))));
    }
}

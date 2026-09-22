package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class PerformancePackagePolicyTest {
    @Test public void optionalPackagesAreExplicitlyAllowlisted() {
        assertTrue(PerformancePackagePolicy.mayDisable("com.qinggan.otaservice"));
        assertTrue(PerformancePackagePolicy.mayDisable("com.qinggan.recognition.service"));
        assertTrue(PerformancePackagePolicy.mayDisable("com.qinggan.app.login"));
        assertTrue(PerformancePackagePolicy.mayDisable("com.adayo.service.dab"));
        assertTrue(PerformancePackagePolicy.mayDisable("com.qinggan.remotedebug"));
        assertFalse(PerformancePackagePolicy.mayDisable("com.qinggan.systempolicy"));
        assertFalse(PerformancePackagePolicy.mayDisable("com.android.systemui"));
        assertFalse(PerformancePackagePolicy.mayDisable("com.qinggan.tbox.service"));
        assertFalse(PerformancePackagePolicy.mayDisable("some.random.analytics"));
    }
}

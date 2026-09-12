package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FrozenAppStoreTest {
    @Test
    public void blacklistKeepsOnlyFreezablePackages() {
        List<String> result = FrozenAppStore.sanitize(Arrays.asList(
                " com.qinggan.app.music ",
                "com.qinggan.bluetoothphone",
                "com.qinggan.app.hiboard",
                "com.qinggan.app.hiboard",
                "bad/pkg"));

        assertEquals(Arrays.asList("com.qinggan.app.hiboard"), result);
        assertTrue(FrozenAppStore.isNeverFreeze("com.qinggan.app.music"));
        assertTrue(FrozenAppStore.isNeverFreeze("com.qinggan.bluetoothphone"));
        assertFalse(FrozenAppStore.isNeverFreeze("com.qinggan.app.dab"));
        assertFalse(FrozenAppStore.isNeverFreeze("com.adayo.service.dab"));
        assertFalse(FrozenAppStore.isNeverFreeze("com.qinggan.app.hiboard"));
    }

}

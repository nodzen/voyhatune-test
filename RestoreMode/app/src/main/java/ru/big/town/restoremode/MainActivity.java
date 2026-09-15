package ru.big.town.restoremode;

/**
 * Stable launcher component for VoyahTune.
 *
 * <p>The former dashboard duplicated the OEM launcher and kept receivers, timers and a Binder
 * connection alive solely to render widgets. The application is now a settings surface; extending
 * the settings shell preserves every explicit {@code restoremode.MainActivity} intent used by the
 * launcher hook and Native auto-launch without an intermediate redirect Activity.</p>
 */
public class MainActivity extends AdvanceActivity {
}

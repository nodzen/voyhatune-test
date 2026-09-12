package ru.big.town.anative;

import android.media.session.PlaybackState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NowPlayingSnapshotTest {

    @Test
    public void snapshotNormalizesNullableMetadataAsOneImmutableValue() {
        NowPlayingService.Snapshot snapshot = new NowPlayingService.Snapshot(
                null, null, null, null, null, PlaybackState.STATE_PLAYING,
                123L, 456L, true, 789L);

        assertEquals("", snapshot.title);
        assertEquals("", snapshot.artist);
        assertEquals("", snapshot.album);
        assertEquals("", snapshot.packageName);
        assertEquals("", snapshot.appLabel);
        assertEquals(PlaybackState.STATE_PLAYING, snapshot.state);
        assertEquals(123L, snapshot.position);
        assertEquals(456L, snapshot.duration);
        assertTrue(snapshot.hasArt);
        assertEquals(789L, snapshot.updatedAt);
    }

    @Test
    public void emptySnapshotHasNoTrackOrArtwork() {
        NowPlayingService.Snapshot snapshot = NowPlayingService.Snapshot.empty();

        assertEquals(PlaybackState.STATE_NONE, snapshot.state);
        assertEquals("", snapshot.title);
        assertFalse(snapshot.hasArt);
        assertTrue(snapshot.updatedAt > 0L);
    }
}

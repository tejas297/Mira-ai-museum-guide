package ai.mira.guide

import ai.mira.guide.beacon.visibleBeaconMinors
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real mode must list only beacons actually heard from; mock lists all. */
class BeaconFilterTest {

    @Test
    fun realModeShowsOnlySighted() {
        assertEquals(
            setOf(12, 15),
            visibleBeaconMinors(realMode = true, all = listOf(12, 15, 21), sighted = listOf(12, 15, 99))
        )
    }

    @Test
    fun realModeNoneSightedShowsNothing() {
        assertEquals(
            emptySet<Int>(),
            visibleBeaconMinors(realMode = true, all = listOf(12, 15), sighted = emptyList())
        )
    }

    @Test
    fun mockModeShowsEverything() {
        assertEquals(
            setOf(12, 15, 21),
            visibleBeaconMinors(realMode = false, all = listOf(12, 15, 21), sighted = listOf(12))
        )
    }
}

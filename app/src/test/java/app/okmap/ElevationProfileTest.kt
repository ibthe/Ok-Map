package app.okmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationProfileTest {
    @Test
    fun calculatesDistanceRangeAndCumulativeAscentDescent() {
        val summary = ElevationProfile.summarize(
            listOf(
                ElevationSample(0.0, 100.0),
                ElevationSample(100.0, 104.0),
                ElevationSample(200.0, 97.0),
                ElevationSample(300.0, 110.0)
            )
        )!!
        assertEquals(300.0, summary.distanceMeters, 0.001)
        assertEquals(97.0, summary.minElevationMeters, 0.001)
        assertEquals(110.0, summary.maxElevationMeters, 0.001)
        assertEquals(17.0, summary.ascentMeters, 0.001)
        assertEquals(7.0, summary.descentMeters, 0.001)
    }

    @Test
    fun ignoresSmallAltitudeNoiseAndInvalidSamples() {
        val summary = ElevationProfile.summarize(
            listOf(
                ElevationSample(0.0, 100.0),
                ElevationSample(10.0, 102.0),
                ElevationSample(20.0, 99.0),
                ElevationSample(30.0, Double.NaN),
                ElevationSample(40.0, 105.0)
            )
        )!!
        assertEquals(6.0, summary.ascentMeters, 0.001)
        assertEquals(0.0, summary.descentMeters, 0.001)
    }

    @Test
    fun needsAtLeastTwoValidSamples() {
        assertNull(ElevationProfile.summarize(listOf(ElevationSample(0.0, 100.0))))
        assertNull(ElevationProfile.summarize(listOf(ElevationSample(0.0, Double.NaN), ElevationSample(1.0, 10.0))))
    }
}

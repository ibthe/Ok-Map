package app.okmap

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRoutingTest {
    private fun syntheticGraph(): Pair<File, Double> {
        val aLat = 41.7000
        val aLon = 44.8000
        val bLat = 41.7000
        val bLon = 44.8100
        val cLat = 41.7100
        val cLon = 44.8050
        fun dm(la1: Double, lo1: Double, la2: Double, lo2: Double) =
            (Graph.haversine(la1, lo1, la2, lo2) * 10.0).roundToInt()

        val trackDm = dm(aLat, aLon, bLat, bLon)
        val paved1Dm = dm(aLat, aLon, cLat, cLon)
        val paved2Dm = dm(cLat, cLon, bLat, bLon)
        val bytes = ByteBuffer.allocate(240).order(ByteOrder.LITTLE_ENDIAN)

        intArrayOf(
            0x32474B4F, 3, 3, 3, 0, 6, 0, 0, 80,
            41695000, 44795000, 5000, 1, 1, 0,
            0, 0, 0, 0, 0
        ).forEach { bytes.putInt(it) }

        // Three nodes: start, end, north. Segment 0 is a direct unpaved track;
        // segments 1 and 2 form a longer but faster paved bypass.
        intArrayOf(41700000, 41700000, 41710000).forEach { bytes.putInt(it) }
        intArrayOf(44800000, 44810000, 44805000).forEach { bytes.putInt(it) }
        intArrayOf(0, 2, 4, 6).forEach { bytes.putInt(it) }
        intArrayOf(0, 1, 0, 2, 1, 2).forEach { bytes.putInt(it) }
        intArrayOf(0, 0, 2).forEach { bytes.putInt(it) }       // A
        intArrayOf(1, 2, 1).forEach { bytes.putInt(it) }       // B
        intArrayOf(trackDm, paved1Dm, paved2Dm).forEach { bytes.putInt(it) }
        intArrayOf(-1, -1, -1).forEach { bytes.putInt(it) }   // no street names
        intArrayOf(0, 0, 0).forEach { bytes.putInt(it) }      // shape starts
        byteArrayOf(80, 50, 50, 0).forEach { bytes.put(it) }  // km/h, padded
        byteArrayOf(171.toByte(), 31, 31, 0).forEach { bytes.put(it) } // track + two paved roads, padded
        repeat(3) { bytes.putShort(0) }
        bytes.putShort(0)                                    // padded shape counts
        bytes.putInt(0)                                      // name offsets
        bytes.putInt(0); bytes.putInt(0)                     // one empty grid cell
        bytes.putInt(0)                                      // no via-node restrictions
        bytes.putInt(0)                                      // no sequence restrictions
        assertEquals(240, bytes.position())

        val file = File.createTempFile("okmap-route-", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes.array())
        return file to trackDm / 10.0
    }

    @Test
    fun routeAlternativeFindsAReasonableDistinctPavedPath() {
        val (file, trackLength) = syntheticGraph()
        val graph = Graph(file)
        val start = Snap(0, 0.0, 0.0, 41.7000, 44.8000)
        val end = Snap(0, trackLength, 0.0, 41.7000, 44.8100)

        val primary = graph.route(start, end, avoidUnpaved = false)
        assertNotNull(primary)
        assertTrue(primary!!.any { it.seg == 0 })

        val alternative = graph.routeAlternative(start, end, avoidUnpaved = false, primary = primary)
        assertNotNull("a distinct reasonable paved alternative should be found", alternative)
        assertTrue(alternative!!.any { it.seg == 1 })
        assertTrue(alternative.any { it.seg == 2 })
        assertTrue(alternative.none { it.seg == 0 && abs(it.to - it.from) > 1.0 })
    }

    @Test
    fun avoidUnpavedComparesSameSegmentTrackAgainstPavedDetour() {
        val (file, trackLength) = syntheticGraph()
        val graph = Graph(file)
        val start = Snap(0, 0.0, 0.0, 41.7000, 44.8000)
        val end = Snap(0, trackLength, 0.0, 41.7000, 44.8100)

        val snapped = graph.snap(41.7000, 44.8000)
        assertNotNull("whole-network fallback should snap even when the nearby grid is empty", snapped)
        assertEquals(0, snapped!!.seg)

        val avoiding = graph.route(start, end, avoidUnpaved = true)
        assertNotNull(avoiding)
        val unpavedWhenAvoiding = avoiding!!.filter { it.seg == 0 }.sumOf { abs(it.to - it.from) }
        assertTrue("avoid-unpaved must take the faster paved bypass", unpavedWhenAvoiding < 1.0)
        assertTrue(avoiding.any { it.seg == 1 && abs(it.to - it.from) > 100.0 })
        assertTrue(avoiding.any { it.seg == 2 && abs(it.to - it.from) > 100.0 })

        val allowing = graph.route(start, end, avoidUnpaved = false)
        assertNotNull(allowing)
        val unpavedWhenAllowed = allowing!!.filter { it.seg == 0 }.sumOf { abs(it.to - it.from) }
        assertTrue("without the preference the faster direct track should be used",
            unpavedWhenAllowed > trackLength - 1.0)
    }
}

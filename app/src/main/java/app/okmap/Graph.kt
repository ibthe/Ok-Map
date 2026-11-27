package app.okmap

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Point on the road network closest to a given coordinate. [off] = metres along the segment from its node A. */
class Snap(val seg: Int, val off: Double, val dist: Double, val lat: Double, val lon: Double)

/** One piece of a route: a segment travelled from offset [from] to offset [to] (metres along A->B); [endNode] = node reached (-1 for the last leg). */
class Leg(val seg: Int, val from: Double, val to: Double, val endNode: Int)

/** Manoeuvre types of [Step.type]. */
object Maneuver {
    const val DEPART = 0
    const val CONTINUE = 1
    const val SLIGHT_LEFT = 2
    const val SLIGHT_RIGHT = 3
    const val LEFT = 4
    const val RIGHT = 5
    const val SHARP_LEFT = 6
    const val SHARP_RIGHT = 7
    const val UTURN = 8
    const val ROUNDABOUT = 9
    const val ARRIVE = 10
}

class Step(val type: Int, val text: String, val street: String, val pointIdx: Int, val exit: Int)

class Route(
    val lat: DoubleArray, val lon: DoubleArray, val cum: DoubleArray, val timeCum: DoubleArray,
    val steps: List<Step>, val seconds: Double
) {
    val distance: Double get() = cum[cum.size - 1]

    /** Remaining travel time at a distance along the route, including road-class/surface penalties. */
    fun remainingSeconds(along: Double): Double {
        if (cum.size < 2) return 0.0
        val d = along.coerceIn(0.0, distance)
        var lo = 0
        var hi = cum.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val span = cum[hi] - cum[lo]
        val f = if (span <= 0.0) 0.0 else (d - cum[lo]) / span
        val elapsed = timeCum[lo] + (timeCum[hi] - timeCum[lo]) * f
        return max(0.0, seconds - elapsed)
    }
}

/**
 * Offline road graph (file produced by tools/build_graph.py, format "OKG2") with a car router.
 * The file is memory-mapped, so the whole country costs almost no Java heap.
 * Algorithm and file layout are mirrored by tools/route_check.py, which is tested in CI.
 */
internal data class SequenceRestriction(val type: Int, val prefix: IntArray, val allowed: IntArray)

/** Aho-Corasick automaton over directed road arcs; supports overlapping via-way restrictions. */
internal class RestrictionAutomaton(restrictions: List<SequenceRestriction>) {
    private val edges = ArrayList<HashMap<Int, Int>>()
    private val fail = ArrayList<Int>()
    private val forbidden = ArrayList<Boolean>()
    private val localOnly = ArrayList<MutableList<Set<Int>>>()
    private val onlyAllowed = ArrayList<Set<Int>?>()

    init {
        addState()
        for (r in restrictions) {
            var state = 0
            for (arc in r.prefix) {
                var next = edges[state][arc]
                if (next == null) {
                    next = addState()
                    edges[state][arc] = next
                }
                state = next
            }
            if (r.type == 1) forbidden[state] = true
            else localOnly[state].add(r.allowed.toSet())
        }
        val queue = java.util.ArrayDeque<Int>()
        for (child in edges[0].values) queue.add(child)
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val f = fail[state]
            forbidden[state] = forbidden[state] || forbidden[f]
            val inherited = onlyAllowed[f]
            val local = localOnly[state]
            if (inherited != null || local.isNotEmpty()) {
                var allowed: MutableSet<Int>? = inherited?.toMutableSet()
                for (set in local) {
                    if (allowed == null) allowed = set.toMutableSet()
                    else allowed!!.retainAll(set)
                }
                onlyAllowed[state] = allowed ?: emptySet()
            }
            for ((arc, child) in edges[state]) {
                queue.add(child)
                var fallback = fail[state]
                while (fallback != 0 && !edges[fallback].containsKey(arc)) fallback = fail[fallback]
                fail[child] = edges[fallback][arc] ?: 0
            }
        }
    }

    private fun addState(): Int {
        val id = edges.size
        edges.add(HashMap())
        fail.add(0)
        forbidden.add(false)
        localOnly.add(ArrayList())
        onlyAllowed.add(null)
        return id
    }

    fun next(state0: Int, arc: Int): Int {
        var state = state0
        while (state != 0 && !edges[state].containsKey(arc)) state = fail[state]
        return edges[state][arc] ?: 0
    }

    fun isForbidden(state: Int) = forbidden[state]

    fun blocks(state: Int, arc: Int): Boolean {
        val allowed = onlyAllowed[state]
        if (allowed != null && arc !in allowed) return true
        return forbidden[next(state, arc)]
    }
}

class Graph(file: File) {
    private val buf: ByteBuffer
    val nodes: Int
    val segments: Int
    private val shapePts: Int
    private val adjLen: Int
    private val nameCount: Int
    private val nameBytes: Int
    val maxKmh: Int
    private val lat0: Int
    private val lon0: Int
    private val cell: Int
    private val gw: Int
    private val gh: Int
    private val cellDataLen: Int
    private val turnRestrictions = HashSet<Long>()
    private lateinit var restrictionAutomaton: RestrictionAutomaton

    private val oNLat: Int
    private val oNLon: Int
    private val oAdjStart: Int
    private val oAdj: Int
    private val oA: Int
    private val oB: Int
    private val oLen: Int
    private val oName: Int
    private val oSst: Int
    private val oKmh: Int
    private val oFlg: Int
    private val oScnt: Int
    private val oSlat: Int
    private val oSlon: Int
    private val oNameOff: Int
    private val oNameBytes: Int
    private val oCellStart: Int
    private val oCellData: Int

    init {
        val raf = RandomAccessFile(file, "r")
        buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
        raf.close()
        require(buf.getInt(0) == 0x32474B4F) { "graph.bin: wrong format" }
        val formatVersion = buf.getInt(4)
        require(formatVersion == 1 || formatVersion == 2 || formatVersion == 3) { "graph.bin: unsupported version $formatVersion" }
        nodes = buf.getInt(8)
        segments = buf.getInt(12)
        shapePts = buf.getInt(16)
        adjLen = buf.getInt(20)
        nameCount = buf.getInt(24)
        nameBytes = buf.getInt(28)
        maxKmh = max(30, buf.getInt(32))
        lat0 = buf.getInt(36)
        lon0 = buf.getInt(40)
        cell = buf.getInt(44)
        gw = buf.getInt(48)
        gh = buf.getInt(52)
        cellDataLen = buf.getInt(56)
        require(nodes > 0 && segments > 0 && shapePts >= 0 && adjLen >= 0 &&
            nameCount >= 0 && nameBytes >= 0 && cell > 0 && gw > 0 && gh > 0 &&
            cellDataLen >= 0 && segments <= Int.MAX_VALUE / 2 &&
            gw.toLong() * gh.toLong() < Int.MAX_VALUE / 4) { "graph.bin: invalid header dimensions" }

        fun pad4(v: Int) = v + ((4 - v % 4) % 4)
        var p = 80
        oNLat = p; p += 4 * nodes
        oNLon = p; p += 4 * nodes
        oAdjStart = p; p += 4 * (nodes + 1)
        oAdj = p; p += 4 * adjLen
        oA = p; p += 4 * segments
        oB = p; p += 4 * segments
        oLen = p; p += 4 * segments
        oName = p; p += 4 * segments
        oSst = p; p += 4 * segments
        oKmh = p; p += pad4(segments)
        oFlg = p; p += pad4(segments)
        oScnt = p; p += pad4(2 * segments)
        oSlat = p; p += 4 * shapePts
        oSlon = p; p += 4 * shapePts
        oNameOff = p; p += 4 * (nameCount + 1)
        oNameBytes = p; p += pad4(nameBytes)
        oCellStart = p; p += 4 * (gw * gh + 1)
        oCellData = p
        val baseEndLong = oCellData.toLong() + 4L * cellDataLen
        require(baseEndLong >= oCellData.toLong() && baseEndLong <= buf.limit().toLong()) {
            "graph.bin: truncated cell index"
        }
        val sequenceRestrictions = ArrayList<SequenceRestriction>()
        if (formatVersion == 1) {
            require(baseEndLong == buf.limit().toLong()) { "graph.bin: unexpected trailing data in v1" }
        } else {
            require(baseEndLong + 4L <= buf.limit().toLong()) { "graph.bin: missing turn section" }
            val turnOffset = baseEndLong.toInt()
            val count = buf.getInt(turnOffset)
            val turnEndLong = turnOffset.toLong() + 4L + count.toLong() * 8L
            require(count >= 0 && turnEndLong <= buf.limit().toLong()) { "graph.bin: invalid turn section size" }
            var q = turnOffset + 4
            repeat(count) {
                val incoming = buf.getInt(q); q += 4
                val outgoing = buf.getInt(q); q += 4
                require(incoming >= 0 && incoming < segments * 2 && outgoing >= 0 && outgoing < segments * 2) {
                    "graph.bin: invalid turn arc"
                }
                turnRestrictions.add((incoming.toLong() shl 32) or (outgoing.toLong() and 0xffffffffL))
            }
            if (formatVersion == 2) {
                require(turnEndLong == buf.limit().toLong()) { "graph.bin: unexpected trailing data in v2" }
            } else {
                require(turnEndLong + 4L <= buf.limit().toLong()) { "graph.bin: missing sequence section" }
                q = turnEndLong.toInt()
                val sequenceCount = buf.getInt(q); q += 4
                require(sequenceCount >= 0) { "graph.bin: invalid sequence count" }
                repeat(sequenceCount) {
                    require(q.toLong() + 12L <= buf.limit().toLong()) { "graph.bin: truncated sequence header" }
                    val type = buf.getInt(q); q += 4
                    val prefixCount = buf.getInt(q); q += 4
                    val allowedCount = buf.getInt(q); q += 4
                    require((type == 1 || type == 2) && prefixCount > 0 && allowedCount >= 0 &&
                        (type != 1 || allowedCount == 0) && (type != 2 || allowedCount > 0)) {
                        "graph.bin: invalid sequence header"
                    }
                    val total = prefixCount.toLong() + allowedCount.toLong()
                    require(total <= Int.MAX_VALUE / 4 && q.toLong() + total * 4L <= buf.limit().toLong()) {
                        "graph.bin: truncated sequence arcs"
                    }
                    val prefix = IntArray(prefixCount) { buf.getInt(q + it * 4) }
                    q += prefixCount * 4
                    val allowed = IntArray(allowedCount) { buf.getInt(q + it * 4) }
                    q += allowedCount * 4
                    require((prefix.asSequence() + allowed.asSequence()).all { it >= 0 && it < segments * 2 }) {
                        "graph.bin: sequence arc out of range"
                    }
                    sequenceRestrictions.add(SequenceRestriction(type, prefix, allowed))
                }
                require(q == buf.limit()) { "graph.bin: unexpected trailing sequence data" }
            }
        }
        restrictionAutomaton = RestrictionAutomaton(sequenceRestrictions)
    }

    // ---- raw accessors
    private fun nLat(i: Int) = buf.getInt(oNLat + 4 * i) / 1e6
    private fun nLon(i: Int) = buf.getInt(oNLon + 4 * i) / 1e6
    private fun adjStart(i: Int) = buf.getInt(oAdjStart + 4 * i)
    private fun adj(i: Int) = buf.getInt(oAdj + 4 * i)
    private fun segA(k: Int) = buf.getInt(oA + 4 * k)
    private fun segB(k: Int) = buf.getInt(oB + 4 * k)
    private fun segLen(k: Int) = buf.getInt(oLen + 4 * k) / 10.0
    private fun segName(k: Int) = buf.getInt(oName + 4 * k)
    private fun segSst(k: Int) = buf.getInt(oSst + 4 * k)
    private fun segKmh(k: Int) = buf.get(oKmh + k).toInt() and 0xFF
    private fun segFlags(k: Int) = buf.get(oFlg + k).toInt() and 0xFF
    private fun segCnt(k: Int) = buf.getShort(oScnt + 2 * k).toInt() and 0xFFFF

    private fun canForward(k: Int) = segFlags(k) and 1 != 0     // A -> B
    private fun canBackward(k: Int) = segFlags(k) and 2 != 0    // B -> A
    private fun isRoundabout(k: Int) = segFlags(k) and 64 != 0
    private fun classId(k: Int) = (segFlags(k) shr 2) and 15

    fun streetName(k: Int): String {
        val i = segName(k)
        if (i < 0 || i >= nameCount) return ""
        val a = buf.getInt(oNameOff + 4 * i)
        val b = buf.getInt(oNameOff + 4 * (i + 1))
        val bytes = ByteArray(b - a)
        for (j in bytes.indices) bytes[j] = buf.get(oNameBytes + a + j)
        return String(bytes, Charsets.UTF_8)
    }

    private fun ptCount(k: Int) = segCnt(k) + 2
    private fun ptLat(k: Int, i: Int): Double = when (i) {
        0 -> nLat(segA(k))
        ptCount(k) - 1 -> nLat(segB(k))
        else -> buf.getInt(oSlat + 4 * (segSst(k) + i - 1)) / 1e6
    }

    private fun ptLon(k: Int, i: Int): Double = when (i) {
        0 -> nLon(segA(k))
        ptCount(k) - 1 -> nLon(segB(k))
        else -> buf.getInt(oSlon + 4 * (segSst(k) + i - 1)) / 1e6
    }

    private fun penalty(k: Int, avoidUnpaved: Boolean): Double {
        var m = if (classId(k) == 10) 2.5 else 1.0
        if (segFlags(k) and 128 != 0) m *= if (avoidUnpaved) 3.0 else 1.3
        return m
    }

    private fun cost(k: Int, meters: Double, avoidUnpaved: Boolean) =
        meters / (max(5, segKmh(k)) / 3.6) * penalty(k, avoidUnpaved)

    // ------------------------------------------------------------------ snapping
    /** Closest road segment to the point. Searches the grid around it first, then the whole network (never null for a non-empty graph). */
    fun snap(lat: Double, lon: Double): Snap? {
        val cy = (Math.floor(lat * 1e6).toLong() - lat0) / cell
        val cx = (Math.floor(lon * 1e6).toLong() - lon0) / cell
        val k = cos(Math.toRadians(lat))
        val cellMeters = cell * 1e-6 * 111320.0 * min(1.0, k)
        var best: Snap? = null
        for (r in 0 until 40) {
            for (y in (cy - r).toInt()..(cy + r).toInt()) {
                for (x in (cx - r).toInt()..(cx + r).toInt()) {
                    if (max(abs(y - cy.toInt()), abs(x - cx.toInt())) != r) continue
                    if (y < 0 || y >= gh || x < 0 || x >= gw) continue
                    val c = y * gw + x
                    val from = buf.getInt(oCellStart + 4 * c)
                    val to = buf.getInt(oCellStart + 4 * (c + 1))
                    for (i in from until to) {
                        val seg = buf.getInt(oCellData + 4 * i)
                        val s = snapToSegment(seg, lat, lon, k)
                        if (best == null || s.dist < best.dist) best = s
                    }
                }
            }
            val b = best
            if (b != null && b.dist <= r * cellMeters) break
        }
        return best ?: nearestAnywhere(lat, lon)
    }

    /** No road in the neighbourhood: take the nearest junction of the whole network and snap to its closest segment. */
    private fun nearestAnywhere(lat: Double, lon: Double): Snap? {
        if (nodes == 0) return null
        val k = cos(Math.toRadians(lat))
        var bn = -1
        var bd = Double.MAX_VALUE
        for (i in 0 until nodes) {
            val dy = nLat(i) - lat
            val dx = (nLon(i) - lon) * k
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; bn = i }
        }
        if (bn < 0) return null
        var best: Snap? = null
        for (i in adjStart(bn) until adjStart(bn + 1)) {
            val s = snapToSegment(adj(i), lat, lon, k)
            if (best == null || s.dist < best.dist) best = s
        }
        return best
    }

    private fun snapToSegment(seg: Int, lat: Double, lon: Double, k: Double): Snap {
        var off = 0.0
        var bestD = Double.MAX_VALUE
        var bestOff = 0.0
        var bLat = 0.0
        var bLon = 0.0
        val n = ptCount(seg)
        var pLat = ptLat(seg, 0)
        var pLon = ptLon(seg, 0)
        for (i in 1 until n) {
            val qLat = ptLat(seg, i)
            val qLon = ptLon(seg, i)
            val ax = (pLon - lon) * k
            val ay = pLat - lat
            val dx = (qLon - pLon) * k
            val dy = qLat - pLat
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
            val d = hypot(ax + t * dx, ay + t * dy) * 111320.0
            val sl = haversine(pLat, pLon, qLat, qLon)
            if (d < bestD) {
                bestD = d
                bestOff = off + t * sl
                bLat = pLat + (qLat - pLat) * t
                bLon = pLon + (qLon - pLon) * t
            }
            off += sl
            pLat = qLat
            pLon = qLon
        }
        return Snap(seg, bestOff, haversine(lat, lon, bLat, bLon), bLat, bLon)
    }

    // ------------------------------------------------------------------ routing (A*)
    private class Heap {
        var keys = DoubleArray(1024)
        var vals = LongArray(1024)
        var size = 0
        var topKey = 0.0

        fun push(k: Double, v: Long) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                vals = vals.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]
                vals[i] = vals[p]
                i = p
            }
            keys[i] = k
            vals[i] = v
        }

        fun pop(): Long {
            val res = vals[0]
            topKey = keys[0]
            size--
            if (size > 0) {
                val k = keys[size]
                val v = vals[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && keys[c + 1] < keys[c]) c++
                    if (keys[c] >= k) break
                    keys[i] = keys[c]
                    vals[i] = vals[c]
                    i = c
                }
                keys[i] = k
                vals[i] = v
            }
            return res
        }
    }

    /** Fastest route between two snapped points, or null if they are not connected (one-way streets respected). */
    private fun forbiddenTurn(inArc: Int, outArc: Int): Boolean =
        turnRestrictions.contains((inArc.toLong() shl 32) or (outArc.toLong() and 0xffffffffL))

    /** Fastest route using directed-arc states; the incoming road is retained at every junction. */
    fun route(s: Snap, e: Snap, avoidUnpaved: Boolean): List<Leg>? {
        val inf = Double.MAX_VALUE
        val heap = Heap()
        val g = HashMap<Long, Double>()
        val prev = HashMap<Long, Long>()
        val closed = HashSet<Long>()
        val vmax = maxKmh / 3.6
        fun h(node: Int) = haversine(nLat(node), nLon(node), e.lat, e.lon) / vmax
        fun key(arc: Int, automaton: Int) =
            (arc.toLong() shl 32) or (automaton.toLong() and 0xffffffffL)
        fun arcOf(state: Long) = (state ushr 32).toInt()
        fun automatonOf(state: Long) = state.toInt()
        val ks = s.seg
        val ke = e.seg
        val ls = segLen(ks)
        val le = segLen(ke)
        if (ks == ke) {
            if (e.off >= s.off && canForward(ks)) return listOf(Leg(ks, s.off, e.off, -1))
            if (e.off < s.off && canBackward(ks)) return listOf(Leg(ks, s.off, e.off, -1))
        }
        fun arcEnd(arc: Int): Int = if ((arc and 1) == 0) segB(arc / 2) else segA(arc / 2)

        fun addStart(arc: Int, node: Int, partialCost: Double) {
            val ac = restrictionAutomaton.next(0, arc)
            if (restrictionAutomaton.isForbidden(ac)) return
            val state = key(arc, ac)
            if (partialCost < (g[state] ?: inf)) {
                g[state] = partialCost
                heap.push(partialCost + h(node), state)
            }
        }
        val startAtNode = s.off <= 1.0 || ls - s.off <= 1.0
        val startNode = when {
            s.off <= 1.0 -> segA(ks)
            ls - s.off <= 1.0 -> segB(ks)
            else -> -1
        }
        if (startAtNode) {
            // At a junction there is no incoming road. Seed every legal outgoing arc
            // directly, otherwise a zero-length fake arrival can trigger a turn restriction.
            for (i in adjStart(startNode) until adjStart(startNode + 1)) {
                val k = adj(i)
                if (segA(k) == startNode && canForward(k)) {
                    addStart(2 * k, segB(k), cost(k, segLen(k), avoidUnpaved))
                } else if (segB(k) == startNode && canBackward(k)) {
                    addStart(2 * k + 1, segA(k), cost(k, segLen(k), avoidUnpaved))
                }
            }
        } else {
            if (canBackward(ks)) addStart(2 * ks + 1, segA(ks), cost(ks, s.off, avoidUnpaved))
            if (canForward(ks)) addStart(2 * ks, segB(ks), cost(ks, ls - s.off, avoidUnpaved))
        }

        var best = inf
        var bestState = -1L
        var bestEndArc = -1
        var directStartLeg: Leg? = null
        if (startAtNode && startNode == segA(ke) && canForward(ke)) {
            best = cost(ke, e.off, avoidUnpaved)
            directStartLeg = Leg(ke, 0.0, e.off, -1)
        }
        if (startAtNode && startNode == segB(ke) && canBackward(ke)) {
            val direct = cost(ke, le - e.off, avoidUnpaved)
            if (direct < best) {
                best = direct
                directStartLeg = Leg(ke, le, e.off, -1)
            }
        }
        while (heap.size > 0) {
            val state = heap.pop()
            if (heap.topKey >= best) break
            if (!closed.add(state)) continue
            val inArc = arcOf(state)
            val acState = automatonOf(state)
            val node = arcEnd(inArc)
            val currentCost = g[state] ?: continue
            if (node == segA(ke) && canForward(ke)) {
                val endArc = 2 * ke
                val tail = cost(ke, e.off, avoidUnpaved)
                if (!forbiddenTurn(inArc, endArc) && !restrictionAutomaton.blocks(acState, endArc) &&
                    currentCost + tail < best) {
                    best = currentCost + tail; bestState = state; bestEndArc = endArc
                }
            }
            if (node == segB(ke) && canBackward(ke)) {
                val endArc = 2 * ke + 1
                val tail = cost(ke, le - e.off, avoidUnpaved)
                if (!forbiddenTurn(inArc, endArc) && !restrictionAutomaton.blocks(acState, endArc) &&
                    currentCost + tail < best) {
                    best = currentCost + tail; bestState = state; bestEndArc = endArc
                }
            }
            for (i in adjStart(node) until adjStart(node + 1)) {
                val k = adj(i)
                val outArc: Int
                val next: Int
                if (segA(k) == node && canForward(k)) { outArc = 2 * k; next = segB(k) }
                else if (segB(k) == node && canBackward(k)) { outArc = 2 * k + 1; next = segA(k) }
                else continue
                if (forbiddenTurn(inArc, outArc) || restrictionAutomaton.blocks(acState, outArc)) continue
                val nextAc = restrictionAutomaton.next(acState, outArc)
                if (restrictionAutomaton.isForbidden(nextAc)) continue
                val nextState = key(outArc, nextAc)
                if (nextState in closed) continue
                val candidate = currentCost + cost(k, segLen(k), avoidUnpaved)
                if (candidate < (g[nextState] ?: inf)) {
                    g[nextState] = candidate
                    prev[nextState] = state
                    heap.push(candidate + h(next), nextState)
                }
            }
        }
        if (bestState < 0L) return directStartLeg?.let { listOf(it) }
        val states = ArrayList<Int>()
        var state = bestState
        while (state >= 0L) {
            states.add(arcOf(state))
            state = prev[state] ?: -1L
        }
        states.reverse()
        val legs = ArrayList<Leg>()
        val firstArc = states[0]
        if (startAtNode) {
            val firstSeg = firstArc / 2
            val firstLen = segLen(firstSeg)
            legs.add(if ((firstArc and 1) == 0) Leg(firstSeg, 0.0, firstLen, segB(firstSeg))
                     else Leg(firstSeg, firstLen, 0.0, segA(firstSeg)))
        } else {
            legs.add(if ((firstArc and 1) == 0) Leg(ks, s.off, ls, segB(ks))
                     else Leg(ks, s.off, 0.0, segA(ks)))
        }
        for (i in 1 until states.size) {
            val arc = states[i]
            val k = arc / 2
            val len = segLen(k)
            legs.add(if ((arc and 1) == 0) Leg(k, 0.0, len, segB(k)) else Leg(k, len, 0.0, segA(k)))
        }
        legs.add(if ((bestEndArc and 1) == 0) Leg(ke, 0.0, e.off, -1) else Leg(ke, le, e.off, -1))
        return legs
    }
    // ------------------------------------------------------------------ route geometry + instructions
    private class DList {
        var a = DoubleArray(256)
        var n = 0
        fun add(v: Double) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
    }

    private fun appendSlice(k: Int, from: Double, to: Double, la: DList, lo: DList) {
        val m = ptCount(k)
        val cl = DoubleArray(m)
        for (i in 1 until m) cl[i] = cl[i - 1] + haversine(ptLat(k, i - 1), ptLon(k, i - 1), ptLat(k, i), ptLon(k, i))
        val total = cl[m - 1]
        fun interp(off0: Double, out: DoubleArray) {
            val off = max(0.0, min(total, off0))
            var i = 1
            while (i < m - 1 && cl[i] < off) i++
            val span = cl[i] - cl[i - 1]
            val t = if (span <= 0.0) 0.0 else (off - cl[i - 1]) / span
            out[0] = ptLat(k, i - 1) + (ptLat(k, i) - ptLat(k, i - 1)) * t
            out[1] = ptLon(k, i - 1) + (ptLon(k, i) - ptLon(k, i - 1)) * t
        }

        val lowO = min(from, to)
        val highO = max(from, to)
        val tl = ArrayList<Double>()
        val tn = ArrayList<Double>()
        val tmp = DoubleArray(2)
        interp(lowO, tmp); tl.add(tmp[0]); tn.add(tmp[1])
        for (i in 1 until m - 1) if (cl[i] > lowO + 0.5 && cl[i] < highO - 0.5) { tl.add(ptLat(k, i)); tn.add(ptLon(k, i)) }
        interp(highO, tmp); tl.add(tmp[0]); tn.add(tmp[1])
        if (from > to) { tl.reverse(); tn.reverse() }
        for (i in tl.indices) {
            if (la.n > 0 && abs(la.a[la.n - 1] - tl[i]) < 1e-9 && abs(lo.a[lo.n - 1] - tn[i]) < 1e-9) continue
            la.add(tl[i]); lo.add(tn[i])
        }
    }

    private fun outwardOk(k: Int, node: Int) =
        (segA(k) == node && canForward(k)) || (segB(k) == node && canBackward(k))

    /** Number of other drivable ways leaving [node] except the two route segments. */
    private fun alternatives(node: Int, inSeg: Int, outSeg: Int): Int {
        var n = 0
        for (i in adjStart(node) until adjStart(node + 1)) {
            val k = adj(i)
            if (k != inSeg && k != outSeg && outwardOk(k, node)) n++
        }
        return n
    }

    private fun hasExitRoad(node: Int): Boolean {
        for (i in adjStart(node) until adjStart(node + 1)) {
            val k = adj(i)
            if (!isRoundabout(k) && outwardOk(k, node)) return true
        }
        return false
    }

    private fun legEndNode(l: Leg): Int = l.endNode

    private fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1)
        val p2 = Math.toRadians(la2)
        val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun turnAngle(inB: Double, outB: Double): Double {
        var d = (outB - inB + 540.0) % 360.0 - 180.0
        if (d <= -180.0) d += 360.0
        return d
    }

    private fun maneuverText(type: Int, street: String, exit: Int): String {
        val onto = if (street.isNotEmpty()) " на $street" else ""
        return when (type) {
            Maneuver.DEPART -> if (street.isNotEmpty()) "Начните движение по $street" else "Начните движение"
            Maneuver.CONTINUE -> if (street.isNotEmpty()) "Продолжайте движение по $street" else "Продолжайте движение прямо"
            Maneuver.SLIGHT_LEFT -> "Плавно поверните налево$onto"
            Maneuver.SLIGHT_RIGHT -> "Плавно поверните направо$onto"
            Maneuver.LEFT -> "Поверните налево$onto"
            Maneuver.RIGHT -> "Поверните направо$onto"
            Maneuver.SHARP_LEFT -> "Резко поверните налево$onto"
            Maneuver.SHARP_RIGHT -> "Резко поверните направо$onto"
            Maneuver.UTURN -> "Развернитесь"
            Maneuver.ROUNDABOUT -> "На круговом движении съезжайте на $exit-м съезде$onto"
            else -> "Вы прибыли в пункт назначения"
        }
    }

    fun buildRoute(legs: List<Leg>, avoidUnpaved: Boolean): Route {
        val la = DList()
        val lo = DList()
        val boundary = IntArray(legs.size)         // index of the last point of each leg in the polyline
        for ((i, l) in legs.withIndex()) {
            appendSlice(l.seg, l.from, l.to, la, lo)
            boundary[i] = la.n - 1
        }
        val n = la.n
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + haversine(la.a[i - 1], lo.a[i - 1], la.a[i], lo.a[i])
        // Distribute each leg's penalized travel time over its actual polyline pieces.
        val timeCum = DoubleArray(n)
        var legStart = 0
        for ((i, leg) in legs.withIndex()) {
            val legEnd = boundary[i].coerceAtLeast(legStart).coerceAtMost(n - 1)
            val secondsPerMeter = cost(leg.seg, abs(leg.to - leg.from), avoidUnpaved) /
                max(1e-6, abs(leg.to - leg.from))
            for (j in legStart + 1..legEnd) {
                timeCum[j] = timeCum[j - 1] + (cum[j] - cum[j - 1]) * secondsPerMeter
            }
            legStart = legEnd
        }
        val totalSeconds = timeCum.lastOrNull() ?: 0.0

        val steps = ArrayList<Step>()
        steps.add(Step(Maneuver.DEPART, maneuverText(Maneuver.DEPART, streetName(legs[0].seg), 0),
            streetName(legs[0].seg), 0, 0))

        fun bearingIn(idx: Int): Double {            // direction of travel in the last ~20 m before point idx
            var j = idx
            while (j > 0 && cum[idx] - cum[j] < 20.0) j--
            return bearing(la.a[j], lo.a[j], la.a[idx], lo.a[idx])
        }

        fun bearingOut(idx: Int): Double {           // direction of travel in the first ~20 m after point idx
            var j = idx
            while (j < n - 1 && cum[j] - cum[idx] < 20.0) j++
            return bearing(la.a[idx], lo.a[idx], la.a[j], lo.a[j])
        }

        var inRound = isRoundabout(legs[0].seg)
        for (i in 0 until legs.size - 1) {
            val a = legs[i]
            val b = legs[i + 1]
            val node = legEndNode(a)
            val idx = boundary[i]
            val nameA = streetName(a.seg)
            val nameB = streetName(b.seg)
            val rb = isRoundabout(b.seg)
            val ra = isRoundabout(a.seg)

            if (rb && !ra) {                         // entering a roundabout: count the exit up front
                var exits = 0
                var exitName = nameB
                var j = i + 1
                while (j < legs.size) {
                    if (j + 1 >= legs.size) break
                    val nx = legs[j + 1]
                    val nd = legEndNode(legs[j])
                    if (!isRoundabout(nx.seg)) { exitName = streetName(nx.seg); break }
                    if (hasExitRoad(nd)) exits++
                    j++
                }
                steps.add(Step(Maneuver.ROUNDABOUT, maneuverText(Maneuver.ROUNDABOUT, exitName, exits + 1), exitName, idx, exits + 1))
                inRound = true
                continue
            }
            if (inRound) {
                if (!rb) inRound = false             // leaving: already announced at the entry
                continue
            }
            val ang = turnAngle(bearingIn(idx), bearingOut(idx))
            val aa = abs(ang)
            val alts = alternatives(node, a.seg, b.seg)
            val nameChanged = nameB.isNotEmpty() && nameB != nameA
            val type = when {
                aa > 160.0 -> Maneuver.UTURN
                aa >= 120.0 -> if (ang < 0) Maneuver.SHARP_LEFT else Maneuver.SHARP_RIGHT
                aa >= 50.0 -> if (ang < 0) Maneuver.LEFT else Maneuver.RIGHT
                aa >= 22.0 && (alts > 0 || nameChanged) -> if (ang < 0) Maneuver.SLIGHT_LEFT else Maneuver.SLIGHT_RIGHT
                nameChanged && alts > 0 -> Maneuver.CONTINUE
                else -> -1
            }
            if (type >= 0) steps.add(Step(type, maneuverText(type, nameB, 0), nameB, idx, 0))
        }
        steps.add(Step(Maneuver.ARRIVE, maneuverText(Maneuver.ARRIVE, "", 0), "", n - 1, 0))
        return Route(la.a.copyOf(n), lo.a.copyOf(n), cum, timeCum, steps, totalSeconds)
    }

    companion object {
        fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val p = Math.PI / 180.0
            val a = sin((la2 - la1) * p / 2).let { it * it } +
                cos(la1 * p) * cos(la2 * p) * sin((lo2 - lo1) * p / 2).let { it * it }
            return 12742000.0 * Math.asin(min(1.0, sqrt(a)))
        }
    }
}

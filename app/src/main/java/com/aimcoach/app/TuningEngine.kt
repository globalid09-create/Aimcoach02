package com.aimcoach.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One General + DPI combination. */
data class Setting(val g: Int, val d: Int) {
    val key: String get() = "$g:$d"
}

/** Headshots and total detected hits for one Setting. */
class PairStats(var headshots: Int = 0, var hits: Int = 0) {
    val percent: Double get() = if (hits == 0) 0.0 else headshots * 100.0 / hits
}

/**
 * Pure bookkeeping + advice logic. It never touches the game or the phone's settings:
 * the player changes settings in Free Fire and tells the engine through the panel buttons.
 */
class TuningEngine private constructor(context: Context) {

    companion object {
        const val MIN_HITS = 12          // trust a pair only after this many detected hits
        const val MARGIN = 3.0           // percentage points that count as better / worse
        const val DPI_UNIT = 10          // DPI change per panel step (x1)
        const val DPI_MIN = 50
        const val DPI_MAX = 5000
        val STEPS = intArrayOf(5, 3, 2, 1) // step size shrinks after each reversal on an axis

        private const val PREFS = "aimcoach_state"
        private const val KEY = "state"
        private const val G = 0
        private const val D = 1
        private const val DEFAULT_ADVICE = "Set your values, then press LEARN."

        @Volatile private var instance: TuningEngine? = null

        fun get(context: Context): TuningEngine =
            instance ?: synchronized(this) {
                instance ?: TuningEngine(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var cur = Setting(50, 400)
    private var base: Setting? = null              // pair that the current pair is compared against
    private val stats = HashMap<String, PairStats>()
    private val dir = IntArray(2)                  // last advised direction per axis (+1 / -1 / 0)
    private val stepIdx = IntArray(2)              // index into STEPS per axis
    private val reversals = IntArray(2)            // direction reversals per axis
    private var verdictKey: String? = null         // pair for which the current advice was given
    private var advice: String = DEFAULT_ADVICE

    init {
        load()
    }

    // ---------- settings the player tells us about ----------

    @Synchronized fun current(): Setting = cur

    @Synchronized fun setCurrent(g: Int, d: Int) {
        cur = Setting(g.coerceIn(1, 200), d.coerceIn(DPI_MIN, DPI_MAX))
        save()
    }

    @Synchronized fun changeGeneral(steps: Int) {
        cur = cur.copy(g = (cur.g + steps).coerceIn(1, 200))
        save()
    }

    @Synchronized fun changeDpi(steps: Int) {
        cur = cur.copy(d = (cur.d + steps * DPI_UNIT).coerceIn(DPI_MIN, DPI_MAX))
        save()
    }

    // ---------- learning ----------

    @Synchronized fun recordHit(headshot: Boolean) {
        val s = stats.getOrPut(cur.key) { PairStats() }
        s.hits++
        if (headshot) s.headshots++
        save()
    }

    @Synchronized fun flush() = save()

    @Synchronized fun reset() {
        stats.clear()
        base = null
        dir.fill(0); stepIdx.fill(0); reversals.fill(0)
        verdictKey = null
        advice = DEFAULT_ADVICE
        save()
    }

    // ---------- display helpers ----------

    @Synchronized fun statusLine(): String = "General ${cur.g}   DPI ${cur.d}"

    @Synchronized fun statsLine(): String {
        val s = statsOf(cur)
        val need = if (s.hits < MIN_HITS) "  (need $MIN_HITS+ hits)" else ""
        return "HS ${s.headshots}/${s.hits} = ${f1(s.percent)}%$need"
    }

    @Synchronized fun bestLine(): String {
        val b = bestInternal() ?: return "Best: none yet (needs $MIN_HITS hits on a pair)"
        return "Best: G${b.first.g} D${b.first.d} = ${f1(b.second.percent)}% (HS ${b.second.headshots}/${b.second.hits})"
    }

    @Synchronized fun lastAdvice(): String = advice

    // ---------- the advice ----------

    @Synchronized fun check(): String {
        val cs = statsOf(cur)
        if (cs.hits < MIN_HITS) {
            return "Need ${MIN_HITS - cs.hits} more hits at General ${cur.g}, DPI ${cur.d}. Press LEARN."
        }
        if (verdictKey == cur.key) return advice // already judged; waiting for the player to change a setting

        val b = base
        if (b == null) {
            base = cur
            verdictKey = cur.key
            if (dir[G] == 0) dir[G] = 1
            advice = "Baseline saved: ${f1(cs.percent)}% at G${cur.g}/D${cur.d}. Try ${phrase(G, 1, STEPS[stepIdx[G]])}."
            save()
            return advice
        }
        if (b.key == cur.key) return advice // back on the base pair; follow the stored advice

        val bs = statsOf(b)
        val dg = cur.g - b.g
        val dd = cur.d - b.d
        if (dg != 0 && dd != 0) {
            return "You changed both settings. Go back to General ${b.g}, DPI ${b.d}, then change only one."
        }
        val axis = if (dg != 0) G else D
        val moveDir = if ((if (axis == G) dg else dd) > 0) 1 else -1
        val delta = cs.percent - bs.percent
        val deltaText = String.format(Locale.US, "%+.1f", delta)

        when {
            delta > MARGIN -> {
                dir[axis] = moveDir
                base = cur
                advice = "Better ($deltaText pts). Go ${phrase(axis, moveDir, STEPS[stepIdx[axis]], more = true)}."
            }
            delta < -MARGIN -> {
                reversals[axis]++
                stepIdx[axis] = minOf(stepIdx[axis] + 1, STEPS.size - 1)
                dir[axis] = -moveDir
                // base stays on the better pair the player is about to return to
                advice = "Worse ($deltaText pts). Undo it (back to G${b.g}/D${b.d}), then " +
                    phrase(axis, -moveDir, STEPS[stepIdx[axis]]).replaceFirstChar { it.lowercase() } + "."
            }
            else -> {
                val other = 1 - axis
                val od = if (dir[other] != 0) dir[other] else 1
                base = cur
                advice = "About the same ($deltaText pts). Try ${phrase(other, od, STEPS[stepIdx[other]])}."
            }
        }

        if (reversals[G] >= 2 && reversals[D] >= 2) {
            val best = bestInternal()
            if (best != null && best.first == cur) {
                advice = "Sweet spot found: General ${cur.g}, DPI ${cur.d} (${f1(cs.percent)}% headshots). Keep these."
            }
        }
        verdictKey = cur.key
        save()
        return advice
    }

    // ---------- internals ----------

    private fun statsOf(s: Setting): PairStats = stats[s.key] ?: PairStats()

    private fun bestInternal(): Pair<Setting, PairStats>? {
        var best: Pair<Setting, PairStats>? = null
        for ((k, v) in stats) {
            if (v.hits < MIN_HITS) continue
            val parts = k.split(":")
            if (parts.size != 2) continue
            val s = Setting(parts[0].toIntOrNull() ?: continue, parts[1].toIntOrNull() ?: continue)
            val b = best
            if (b == null || v.percent > b.second.percent ||
                (v.percent == b.second.percent && v.hits > b.second.hits)
            ) best = Pair(s, v)
        }
        return best
    }

    private fun phrase(axis: Int, direction: Int, steps: Int, more: Boolean = false): String {
        val word = if (direction > 0) "up" else "down"
        val tail = if (more) " more" else ""
        return if (axis == G) {
            "General $word $steps$tail"
        } else {
            "DPI $word $steps$tail (panel x$steps = ${steps * DPI_UNIT} DPI)"
        }
    }

    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    private fun save() {
        try {
            val o = JSONObject()
            o.put("g", cur.g).put("d", cur.d)
            base?.let { o.put("bg", it.g).put("bd", it.d) }
            o.put("dirG", dir[G]).put("dirD", dir[D])
            o.put("stepG", stepIdx[G]).put("stepD", stepIdx[D])
            o.put("revG", reversals[G]).put("revD", reversals[D])
            val st = JSONObject()
            for ((k, v) in stats) st.put(k, JSONArray().put(v.headshots).put(v.hits))
            o.put("stats", st)
            o.put("vk", verdictKey ?: "")
            o.put("advice", advice)
            prefs.edit().putString(KEY, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun load() {
        val s = prefs.getString(KEY, null) ?: return
        try {
            val o = JSONObject(s)
            cur = Setting(o.optInt("g", 50), o.optInt("d", 400))
            if (o.has("bg")) base = Setting(o.getInt("bg"), o.getInt("bd"))
            dir[G] = o.optInt("dirG"); dir[D] = o.optInt("dirD")
            stepIdx[G] = o.optInt("stepG").coerceIn(0, STEPS.size - 1)
            stepIdx[D] = o.optInt("stepD").coerceIn(0, STEPS.size - 1)
            reversals[G] = o.optInt("revG"); reversals[D] = o.optInt("revD")
            val st = o.optJSONObject("stats")
            if (st != null) {
                val keys = st.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = st.getJSONArray(k)
                    stats[k] = PairStats(a.getInt(0), a.getInt(1))
                }
            }
            verdictKey = o.optString("vk", "").ifEmpty { null }
            advice = o.optString("advice", DEFAULT_ADVICE)
        } catch (_: Exception) {
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.fritkot.compass

import java.util.Calendar

/** Whether a fritkot is currently open, going by its OSM `opening_hours` tag. */
enum class OpenState { OPEN, CLOSED, UNKNOWN }

/**
 * @param state see [OpenState]
 * @param minutesUntilClose only set when [state] is [OpenState.OPEN] and the
 *        closing time is known (i.e. not "24/7"); used to flag "closing soon".
 */
data class OpeningStatus(val state: OpenState, val minutesUntilClose: Int? = null)

/**
 * A deliberately small, pragmatic interpreter for OSM's `opening_hours`
 * syntax — NOT the full spec (see https://wiki.openstreetmap.org/wiki/Key:opening_hours),
 * which also covers public/school holidays, month and week-of-year ranges,
 * sunrise/sunset keywords, comments, and more. Implementing that fully is a
 * project in its own right (there's a dedicated `opening_hours.js` library
 * for exactly this reason).
 *
 * What IS supported, because it covers the huge majority of real-world
 * values for small food shops:
 *   - "24/7"
 *   - weekday selectors: Mo, Tu, We, Th, Fr, Sa, Su — single days, ranges
 *     (Mo-Fr), and comma lists (Sa,Su), or omitted entirely (applies every day)
 *   - time ranges: "11:00-14:00", comma-separated multiples
 *     ("11:00-14:00,17:00-22:00"), and ranges that cross midnight
 *     ("18:00-02:00")
 *   - "off" / "closed" for a day selector
 *   - ";"-separated rules, later rules overriding earlier ones for the same
 *     day (so "Tu-Su 11:00-22:00; Mo off" works as expected)
 *
 * Anything containing syntax outside that subset (public holidays "PH",
 * school holidays "SH", month names, "week", parentheses, quoted comments,
 * etc.) is deliberately treated as [OpenState.UNKNOWN] rather than guessed
 * at — showing no status is far better than confidently showing the wrong
 * one for a business's real opening hours.
 */
object OpeningHours {

    private val DAY_CODES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    // Substrings that indicate opening_hours syntax beyond what this parser
    // understands. If any of these appear, bail out to UNKNOWN rather than
    // risk misreading the value.
    private val UNSUPPORTED_MARKERS = listOf(
        "PH", "SH", "week", "(", ")", "\"", "+",
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val TIME_RANGE = Regex("^([0-2]?\\d):([0-5]\\d)-([0-2]?\\d):([0-5]\\d)$")

    fun status(openingHours: String?, now: Calendar = Calendar.getInstance()): OpeningStatus {
        val trimmed = openingHours?.trim()
        if (trimmed.isNullOrEmpty()) return OpeningStatus(OpenState.UNKNOWN)

        if (trimmed.equals("24/7", ignoreCase = true)) {
            return OpeningStatus(OpenState.OPEN)
        }

        if (UNSUPPORTED_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) {
            return OpeningStatus(OpenState.UNKNOWN)
        }

        val weekly = parseWeeklyIntervals(trimmed) ?: return OpeningStatus(OpenState.UNKNOWN)

        // Calendar.DAY_OF_WEEK is SUNDAY=1 .. SATURDAY=7; remap to Mo=0..Su=6.
        val todayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val yesterdayIndex = (todayIndex + 6) % 7
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val candidates = ArrayList<Pair<Int, Int>>()
        candidates.addAll(weekly[todayIndex].orEmpty())
        // An interval from yesterday that crosses midnight (e.g. 22:00-02:00,
        // stored as 1320-1560) can still be "open" early today — shift it
        // back a day so it's expressed in today's 0..1440 frame.
        weekly[yesterdayIndex].orEmpty().forEach { (start, end) ->
            candidates.add(start - 1440 to end - 1440)
        }

        for ((start, end) in candidates) {
            if (nowMinutes in start until end) {
                return OpeningStatus(OpenState.OPEN, minutesUntilClose = end - nowMinutes)
            }
        }
        return OpeningStatus(OpenState.CLOSED)
    }

    /** Returns, per weekday index (Mo=0..Su=6), the list of open intervals in minutes-since-midnight (end may exceed 1440 for overnight spans). Null if the value can't be confidently parsed. */
    private fun parseWeeklyIntervals(spec: String): Map<Int, List<Pair<Int, Int>>>? {
        val result = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for (i in 0..6) result[i] = mutableListOf()

        val segments = spec.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        for (segment in segments) {
            if (segment.equals("24/7", ignoreCase = true)) {
                for (i in 0..6) {
                    result[i]!!.clear()
                    result[i]!!.add(0 to 1440)
                }
                continue
            }

            val spaceIdx = segment.indexOf(' ')
            val firstToken = if (spaceIdx > 0) segment.substring(0, spaceIdx) else segment
            val rest = if (spaceIdx > 0) segment.substring(spaceIdx + 1).trim() else ""

            val hasDaySelector = isDaySelector(firstToken)
            val dayToken = if (hasDaySelector) firstToken else null
            val timeToken = if (hasDaySelector) rest else segment
            if (hasDaySelector && timeToken.isEmpty()) return null

            val days = if (dayToken != null) parseDaySelector(dayToken) ?: return null else (0..6).toList()

            if (timeToken.equals("off", ignoreCase = true) || timeToken.equals("closed", ignoreCase = true)) {
                for (d in days) result[d]!!.clear()
                continue
            }

            val intervals = parseTimeRanges(timeToken) ?: return null
            for (d in days) {
                result[d]!!.clear()
                result[d]!!.addAll(intervals)
            }
        }
        return result
    }

    private fun isDaySelector(token: String): Boolean {
        if (token.isEmpty()) return false
        return token.split(",").all { group ->
            val parts = group.split("-")
            when (parts.size) {
                1 -> DAY_CODES.contains(parts[0])
                2 -> DAY_CODES.contains(parts[0]) && DAY_CODES.contains(parts[1])
                else -> false
            }
        }
    }

    private fun parseDaySelector(token: String): List<Int>? {
        val days = sortedSetOf<Int>()
        for (group in token.split(",")) {
            val parts = group.split("-")
            when (parts.size) {
                1 -> {
                    val idx = DAY_CODES.indexOf(parts[0])
                    if (idx < 0) return null
                    days.add(idx)
                }
                2 -> {
                    val startIdx = DAY_CODES.indexOf(parts[0])
                    val endIdx = DAY_CODES.indexOf(parts[1])
                    if (startIdx < 0 || endIdx < 0) return null
                    var i = startIdx
                    while (true) {
                        days.add(i)
                        if (i == endIdx) break
                        i = (i + 1) % 7
                    }
                }
                else -> return null
            }
        }
        return days.toList()
    }

    private fun parseTimeRanges(token: String): List<Pair<Int, Int>>? {
        if (token.isEmpty()) return null
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (part in token.split(",")) {
            val m = TIME_RANGE.matchEntire(part.trim()) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            val start = h1.toInt() * 60 + m1.toInt()
            var end = h2.toInt() * 60 + m2.toInt()
            if (end <= start) end += 1440 // crosses midnight
            ranges.add(start to end)
        }
        return ranges
    }
}

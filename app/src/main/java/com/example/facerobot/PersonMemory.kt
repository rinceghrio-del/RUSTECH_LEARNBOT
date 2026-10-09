package com.example.facerobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * MEMORYA NG BAWAT TAO.
 *
 * Bawat naka-enroll na mukha (rusty, ghrio, sauty, mama...) ay may profile dito, naka-key sa
 * parehong pangalan na ginagamit ng FaceStore. Lokal lang ito sa phone (SharedPreferences, JSON) -
 * walang internet at walang ipinapadala kahit saan.
 *
 * Itinatala:
 *  - kailan unang nakita, kailan huling nakita, at ilang "pagdalaw" na (visits)
 *  - ano ang madalas niyang sabihing command
 *  - anong DFPlayer track ang madalas na tumutunog sa mga command niya (paborito)
 *
 * Ang "pagdalaw" (visit) = bagong dating matapos ang VISIT_GAP_MS na hindi nakita. Kaya hindi
 * nadadagdagan ang bilang habang nasa harap lang siya ng camera.
 */
class PersonMemory(context: Context) {

    companion object {
        private const val PREFS_NAME = "person_memory"
        private const val KEY_PROFILES = "profiles_json"

        const val VISIT_GAP_MS = 10 * 60_000L                 // 10 minutong hindi nakita = bagong pagdalaw
        const val LONG_ABSENCE_MS = 3 * 24 * 60 * 60_000L     // 3 araw o higit = "ngayon lang ulit kita nakita"
        private const val SAVE_EVERY_MS = 60_000L             // huwag mag-save sa bawat 600ms na recognition
        private const val MAX_COMMAND_ENTRIES = 40
        private const val MAX_TRACK_ENTRIES = 40
        const val MIN_USES_FOR_FAVORITE = 2

        /** "5 minuto" / "3 oras" / "2 araw" (o English kung en = true). */
        fun agoLabel(ms: Long, en: Boolean): String {
            val minutes = ms / 60_000L
            val hours = ms / 3_600_000L
            val days = ms / 86_400_000L
            return when {
                minutes < 1 -> if (en) "a moment" else "sandali"
                minutes < 60 -> if (en) "$minutes minute${if (minutes == 1L) "" else "s"}" else "$minutes minuto"
                hours < 24 -> if (en) "$hours hour${if (hours == 1L) "" else "s"}" else "$hours oras"
                else -> if (en) "$days day${if (days == 1L) "" else "s"}" else "$days araw"
            }
        }
    }

    class Profile(val name: String) {
        var firstSeen = 0L
        var lastSeen = 0L
        var prevSeen = 0L          // lastSeen bago nagsimula ang kasalukuyang pagdalaw (0 = wala pa)
        var visits = 0
        var lastGreetedVisit = 0   // visits value noong huling binati (para hindi ulitin sa parehong pagdalaw)
        val commandCounts = mutableMapOf<String, Int>()
        val trackCounts = mutableMapOf<Int, Int>()
    }

    /** Ibinabalik ng onSeen() - ito ang batayan ng personalized na bati. */
    data class Seen(
        val firstEver: Boolean,
        val newVisit: Boolean,
        val absenceMs: Long,               // gaano katagal bago ang pagdalaw na ito (0 kung first ever / hindi bagong pagdalaw)
        val sameDayReturn: Boolean,        // bumalik sa parehong araw
        val alreadyGreetedThisVisit: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profiles = LinkedHashMap<String, Profile>()
    private var lastSavedAt = 0L

    init {
        load()
    }

    private fun keyOf(name: String) = name.trim().lowercase()

    // ---------- Load / save ----------

    private fun load() {
        profiles.clear()
        val json = prefs.getString(KEY_PROFILES, null) ?: return
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val p = Profile(o.getString("name"))
                p.firstSeen = o.optLong("first", 0L)
                p.lastSeen = o.optLong("last", 0L)
                p.prevSeen = o.optLong("prev", 0L)
                p.visits = o.optInt("visits", 0)
                p.lastGreetedVisit = o.optInt("greetedVisit", 0)
                o.optJSONObject("cmds")?.let { c ->
                    val keys = c.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        p.commandCounts[k] = c.optInt(k, 0)
                    }
                }
                o.optJSONObject("tracks")?.let { t ->
                    val keys = t.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        k.toIntOrNull()?.let { track -> p.trackCounts[track] = t.optInt(k, 0) }
                    }
                }
                profiles[keyOf(p.name)] = p
            }
        } catch (e: Exception) {
            // Sira ang naka-save na JSON - magsimula na lang ulit sa blangko imbes na mag-crash.
            profiles.clear()
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (p in profiles.values) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("first", p.firstSeen)
            o.put("last", p.lastSeen)
            o.put("prev", p.prevSeen)
            o.put("visits", p.visits)
            o.put("greetedVisit", p.lastGreetedVisit)
            val c = JSONObject()
            for ((k, v) in p.commandCounts) c.put(k, v)
            o.put("cmds", c)
            val t = JSONObject()
            for ((k, v) in p.trackCounts) t.put(k.toString(), v)
            o.put("tracks", t)
            array.put(o)
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        lastSavedAt = System.currentTimeMillis()
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // ---------- Pagkakita ----------

    /**
     * Tawagin sa bawat pagkakilala ng mukha. Ina-update ang profile at sinasabi kung bagong
     * pagdalaw ito at gaano katagal siyang hindi nakita.
     */
    fun onSeen(name: String, now: Long = System.currentTimeMillis()): Seen {
        val key = keyOf(name)
        var p = profiles[key]
        var firstEver = false
        if (p == null) {
            p = Profile(name.trim())
            p.firstSeen = now
            profiles[key] = p
            firstEver = true
        }

        val hadBefore = p.lastSeen != 0L
        val gap = if (hadBefore) now - p.lastSeen else 0L
        val newVisit = firstEver || !hadBefore || gap > VISIT_GAP_MS

        var absence = 0L
        var sameDay = false
        if (newVisit) {
            if (hadBefore) {
                absence = gap
                sameDay = isSameDay(p.lastSeen, now)
                p.prevSeen = p.lastSeen
            }
            p.visits++
        }
        p.lastSeen = now

        if (newVisit || now - lastSavedAt > SAVE_EVERY_MS) persist()

        return Seen(
            firstEver = firstEver,
            newVisit = newVisit,
            absenceMs = absence,
            sameDayReturn = sameDay,
            alreadyGreetedThisVisit = p.lastGreetedVisit == p.visits
        )
    }

    fun markGreeted(name: String) {
        val p = profiles[keyOf(name)] ?: return
        p.lastGreetedVisit = p.visits
        persist()
    }

    // ---------- Paggamit ng utos / track ----------

    private fun trimMap(map: MutableMap<String, Int>, max: Int) {
        while (map.size > max) {
            val weakest = map.minByOrNull { it.value }?.key ?: return
            map.remove(weakest)
        }
    }

    fun recordCommand(name: String?, trigger: String) {
        if (name.isNullOrBlank() || trigger.isBlank()) return
        val p = profiles[keyOf(name)] ?: return
        val k = trigger.trim().lowercase()
        p.commandCounts[k] = (p.commandCounts[k] ?: 0) + 1
        trimMap(p.commandCounts, MAX_COMMAND_ENTRIES)
        persist()
    }

    fun recordTrack(name: String?, track: Int) {
        if (name.isNullOrBlank()) return
        val p = profiles[keyOf(name)] ?: return
        p.trackCounts[track] = (p.trackCounts[track] ?: 0) + 1
        while (p.trackCounts.size > MAX_TRACK_ENTRIES) {
            val weakest = p.trackCounts.minByOrNull { it.value }?.key ?: break
            p.trackCounts.remove(weakest)
        }
        persist()
    }

    // ---------- Pagbasa ----------

    fun profile(name: String?): Profile? =
        if (name.isNullOrBlank()) null else profiles[keyOf(name)]

    fun all(): List<Profile> = profiles.values.sortedByDescending { it.lastSeen }

    /** Pinakamadalas na track, kung nagamit na ng hindi bababa sa MIN_USES_FOR_FAVORITE beses. */
    fun favoriteTrack(name: String?): Int? {
        val best = profile(name)?.trackCounts?.maxByOrNull { it.value } ?: return null
        return if (best.value >= MIN_USES_FOR_FAVORITE) best.key else null
    }

    /** Pinakamadalas na command, kung nagamit na ng hindi bababa sa MIN_USES_FOR_FAVORITE beses. */
    fun topCommand(name: String?): String? {
        val best = profile(name)?.commandCounts?.maxByOrNull { it.value } ?: return null
        return if (best.value >= MIN_USES_FOR_FAVORITE) best.key else null
    }

    fun forget(name: String) {
        profiles.remove(keyOf(name))
        persist()
    }

    /**
     * Maikling tala tungkol sa kausap na idinadagdag sa system prompt ni Gemini, para magamit
     * niya ang alam ng robot sa tao. Walang laman kung wala pang profile.
     */
    fun promptNotes(name: String?, now: Long = System.currentTimeMillis()): String {
        val p = profile(name) ?: return ""
        val parts = mutableListOf<String>()
        if (p.visits > 0) parts.add("nakita mo na siya ng ${p.visits} beses")
        if (p.prevSeen != 0L) {
            parts.add("huli mo siyang nakita bago ito mga ${agoLabel(now - p.prevSeen, false)} na ang nakalipas")
        }
        topCommand(name)?.let { parts.add("madalas niyang sabihin: \"${it.take(60)}\"") }
        if (parts.isEmpty()) return ""
        return "${p.name}: ${parts.joinToString("; ")}."
    }
}

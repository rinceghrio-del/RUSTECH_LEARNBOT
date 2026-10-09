package com.example.facerobot

import android.os.Handler
import android.os.Looper

/**
 * TEACH MODE - tinuturuan ang robot gamit ang boses, walang rebuild ng APK.
 *
 *   "matuto ka"  ->  "Ano ang sasabihin mo sa akin?"  ->  (trigger)
 *                ->  "Ano ang isasagot ko?"           ->  (reply)
 *                ->  "Kapag sinabi mong ..., sasagot ako ng .... Tama ba?"
 *                ->  "oo" = ise-save sa CommandStore, "hindi" = kanselado, "ulitin" = ulit sa umpisa
 *
 * Ang MainActivity ang tumatawag ng [handle] sa bawat narinig ni Vosk, bago ang lahat ng ibang
 * command handling. Kapag ibinalik nito ang true, ubos na ang narinig (huwag nang ipasa sa iba).
 *
 * KALIGTASAN:
 *  - Ang "stop / hinto / tigil" ay HINDI kailanman nilalamon: kinakansela ang pagtuturo at
 *    ibinabalik ang false para ang normal na FORCE_STOP flow ang gumana.
 *  - Hindi tinatanggap na trigger ang mga salitang pang-hinto, at ang mga parirala ng pagtuturo mismo.
 *  - May timeout ang bawat hakbang - kung walang sumagot, kusang titigil (kasi naririnig ng robot
 *    ang LAHAT ng usapan sa bahay, dahil wala nang wake word).
 */
class TeachMode(
    private val store: CommandStore,
    private val say: (String) -> Unit,
    private val show: (String) -> Unit,
    private val eyes: (Eyes) -> Unit,
    private val tr: (String) -> String,
    private val isEnabled: () -> Boolean,
    private val onLearned: (trigger: String, reply: String, owner: String, result: CommandStore.TeachResult) -> Unit
) {
    enum class Eyes { LISTENING, HAPPY, SAD, CLEAR }

    private enum class State { IDLE, WAIT_TRIGGER, WAIT_REPLY, WAIT_CONFIRM }

    companion object {
        private const val STEP_TIMEOUT_MS = 25_000L
        private const val MIN_TRIGGER_LEN = 4
        private const val MAX_TRIGGER_LEN = 80
        private const val MAX_REPLY_LEN = 220
        private const val MAX_UNCLEAR = 2
        private const val MAX_TRIGGER_RETRIES = 3

        private val ENTER_PHRASES = listOf(
            "matuto ka", "turuan kita", "tuturuan kita", "may ituturo ako", "mag aral ka", "mag-aral ka",
            "magaral ka", "teach mode", "learn mode", "i will teach you"
        )
        private val LIST_PHRASES = listOf(
            "ano ang tinuro ko", "ano tinuro ko", "anong tinuro ko", "ano ang itinuro ko", "anong itinuro ko",
            "ano ang natutunan mo", "ano natutunan mo", "anong natutunan mo"
        )
        private val CANCEL_PHRASES = listOf(
            "kanselahin", "kansel", "cancel", "tama na", "huwag na", "wag na", "forget it"
        )
        private val STOP_WORDS = setOf("hinto", "stop", "tigil")
        private val YES_WORDS = setOf("oo", "opo", "sige", "tama", "yes", "yeah", "ok", "okay", "oke", "save", "sure", "korek")
        private val NO_WORDS = setOf("hindi", "hinde", "mali", "ayaw", "ayoko", "no", "huwag", "wag")
        private val RETRY_WORDS = setOf("ulit", "ulitin", "again", "retry")
    }

    private var state = State.IDLE
    private var owner = ""
    private var trigger = ""
    private var reply = ""
    private var unclearCount = 0
    private var triggerRetries = 0
    private var deadline = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (state != State.IDLE) {
            val msg = tr("Sige, hindi na muna ako matututo ngayon.")
            finish(Eyes.CLEAR)
            say(msg)
            show(msg)
        }
    }

    /** True habang may tinuturo - ginagamit ng MainActivity para huwag sumabat ang mga greeting. */
    val isActive: Boolean get() = state != State.IDLE

    // ---------- Pagsisimula ----------

    /** Pwedeng tawagin direkta (hal. mula sa menu card) o kusa kapag narinig ang "matuto ka". */
    fun start(who: String?) {
        owner = who?.trim().orEmpty()
        trigger = ""
        reply = ""
        unclearCount = 0
        triggerRetries = 0
        state = State.WAIT_TRIGGER
        prompt(tr("Sige, tuturuan mo ako! Ano ang sasabihin mo sa akin?"))
        show(tr("🎓 Teach Mode: sabihin ang ituturo mo. (sabihing \"kanselahin\" para huminto)"))
        eyes(Eyes.LISTENING)
    }

    private fun prompt(text: String) {
        deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, STEP_TIMEOUT_MS)
        say(text)
    }

    private fun finish(finalEyes: Eyes) {
        handler.removeCallbacks(timeoutRunnable)
        state = State.IDLE
        trigger = ""
        reply = ""
        unclearCount = 0
        triggerRetries = 0
        eyes(finalEyes)
    }

    /** Pwedeng tawagin sa onDestroy() ng Activity. */
    fun shutdown() {
        handler.removeCallbacks(timeoutRunnable)
        state = State.IDLE
    }

    // ---------- Pangunahing handler ----------

    /**
     * @param candidates ang N-best na narinig ni Vosk (una ang pinakamalamang)
     * @param who pangalan ng nagsasalita (kung kilala sa mukha), para sa "owner" ng itinuro
     * @return true kung nilamon ng Teach Mode ang narinig
     */
    fun handle(candidates: List<String>, who: String?): Boolean {
        val heard = candidates.map { clean(it) }.filter { it.isNotEmpty() }
        if (heard.isEmpty()) return false

        if (state == State.IDLE) return handleIdle(heard, who)

        // Kaligtasan muna: STOP ay laging para sa normal na flow.
        if (heard.any { h -> tokens(h).any { it in STOP_WORDS } }) {
            finish(Eyes.CLEAR)
            return false
        }

        // Lampas na sa oras (kung hindi pa naitawag ng Handler).
        if (System.currentTimeMillis() > deadline) {
            finish(Eyes.CLEAR)
            return false
        }

        if (heard.any { h -> CANCEL_PHRASES.any { hasPhrase(h, it) } }) {
            val msg = tr("Sige, kinansela ko na.")
            finish(Eyes.CLEAR)
            say(msg)
            show(msg)
            return true
        }

        when (state) {
            State.WAIT_TRIGGER -> onTrigger(heard.first())
            State.WAIT_REPLY -> onReply(heard.first())
            State.WAIT_CONFIRM -> onConfirm(heard)
            State.IDLE -> {}
        }
        return true
    }

    private fun handleIdle(heard: List<String>, who: String?): Boolean {
        if (heard.any { h -> LIST_PHRASES.any { hasPhrase(h, it) } }) {
            val mine = heard.any { h -> "ko" in tokens(h) }
            sayTaught(if (mine) who else null, mine)
            return true
        }
        if (!isEnabled()) return false
        if (heard.any { h -> ENTER_PHRASES.any { hasPhrase(h, it) } }) {
            start(who)
            return true
        }
        return false
    }

    // ---------- Mga hakbang ----------

    private fun onTrigger(text: String) {
        val problem = validateTrigger(text)
        if (problem != null) {
            triggerRetries++
            if (triggerRetries >= MAX_TRIGGER_RETRIES) {
                val msg = tr("Mukhang nahihirapan tayo. Subukan natin ulit mamaya.")
                finish(Eyes.SAD)
                say(msg)
                show(msg)
            } else {
                prompt(problem)
                show(problem)
            }
            return
        }
        trigger = text
        state = State.WAIT_REPLY
        val msg = tr("Narinig ko: {0}. Ano naman ang isasagot ko?").replace("{0}", trigger)
        prompt(msg)
        show("🎓 \"$trigger\" → ?")
    }

    private fun onReply(text: String) {
        if (text.length > MAX_REPLY_LEN) {
            val msg = tr("Masyadong mahaba yan. Paikliin mo ng kaunti.")
            prompt(msg)
            show(msg)
            return
        }
        reply = text
        state = State.WAIT_CONFIRM
        unclearCount = 0
        val msg = tr("Kapag sinabi mong {0}, sasagot ako ng {1}. Tama ba?")
            .replace("{0}", trigger).replace("{1}", reply)
        prompt(msg)
        show("🎓 \"$trigger\" → \"$reply\"  (oo / hindi / ulitin)")
    }

    private fun onConfirm(heard: List<String>) {
        val allTokens = heard.flatMap { tokens(it) }.toSet()

        if (allTokens.any { it in RETRY_WORDS }) {
            trigger = ""
            reply = ""
            triggerRetries = 0
            unclearCount = 0
            state = State.WAIT_TRIGGER
            val msg = tr("Sige, ulit tayo. Ano ang sasabihin mo sa akin?")
            prompt(msg)
            show(msg)
            return
        }

        if (allTokens.any { it in NO_WORDS }) {
            val msg = tr("Sige, hindi ko ise-save.")
            finish(Eyes.SAD)
            say(msg)
            show(msg)
            return
        }

        if (allTokens.any { it in YES_WORDS }) {
            save()
            return
        }

        unclearCount++
        if (unclearCount >= MAX_UNCLEAR) {
            val msg = tr("Hindi ko maintindihan, kaya hindi ko na ise-save.")
            finish(Eyes.SAD)
            say(msg)
            show(msg)
        } else {
            val msg = tr("Oo o hindi lang po. Tama ba?")
            prompt(msg)
            show(msg)
        }
    }

    private fun save() {
        val t = trigger
        val r = reply
        val o = owner
        val result = store.teach(t, r, o)
        val msg = when (result) {
            CommandStore.TeachResult.NEW -> tr("Natutunan ko na yan!")
            CommandStore.TeachResult.VARIANT_ADDED -> tr("Alam ko na yan, pero dinagdagan ko ng isa pang sagot!")
            CommandStore.TeachResult.ALREADY_KNOWN -> tr("Alam ko na yan, ganyan din ang sagot ko.")
        }
        finish(Eyes.HAPPY)
        say(msg)
        show("🎓 $msg")
        onLearned(t, r, o, result)
    }

    private fun sayTaught(ownerName: String?, mine: Boolean) {
        val taught = store.taughtBy(ownerName)
        val msg = if (taught.isEmpty()) {
            if (mine && ownerName != null) tr("Wala ka pang naituturo sa akin. Sabihin mo lang, matuto ka.")
            else tr("Wala pa akong natututunan. Sabihin mo lang, matuto ka.")
        } else {
            val shown = taught.takeLast(5).reversed().joinToString(", ") { it.triggerVariants().firstOrNull() ?: it.trigger }
            val more = taught.size - 5
            val base = tr("Ito ang mga natutunan ko: {0}.").replace("{0}", shown)
            if (more > 0) base + " " + tr("At {0} pa.").replace("{0}", more.toString()) else base
        }
        say(msg)
        show("🎓 $msg")
    }

    // ---------- Validation ----------

    private fun validateTrigger(t: String): String? {
        if (t.length < MIN_TRIGGER_LEN) return tr("Masyadong maikli yan. Subukan mo ulit.")
        if (t.length > MAX_TRIGGER_LEN) return tr("Masyadong mahaba yan. Paikliin mo ng kaunti.")
        if (tokens(t).any { it in STOP_WORDS }) return tr("Hindi pwede yan dahil pang-hinto yan. Iba na lang.")
        if (ENTER_PHRASES.any { hasPhrase(t, it) } || LIST_PHRASES.any { hasPhrase(t, it) }) {
            return tr("Yan ang pang-turo ko na. Iba na lang.")
        }
        return null
    }

    // ---------- Text helpers ----------

    /** Maliit na titik, walang bantas (maliban ' at -), isang espasyo lang sa pagitan ng salita. */
    private fun clean(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s'\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

    /** Tugma ang buong salita/parirala (hindi basta substring) - hal. "no" ay hindi tutugma sa "ano". */
    private fun hasPhrase(text: String, phrase: String): Boolean =
        " $text ".contains(" $phrase ")
}

package org.openipc.devourer.dashboard

import java.util.concurrent.ConcurrentHashMap
import org.openipc.devourer.radio.OpenRadio

/**
 * The last thing the control plane learned about each open radio.
 *
 * The dashboard reads this instead of asking the bridge, and that is the whole
 * design. A page polling once a second would otherwise put a bridge round trip
 * on the same serialized control connection the model's tool calls use — so
 * watching the instrument would slow the instrument, and a stalled bridge
 * would take the dashboard down with it exactly when it was most wanted.
 *
 * The cost is honest and stated on the page: these values are as fresh as the
 * last operation that touched the radio, not live.
 */
public class RadioBook {
    private val radios = ConcurrentHashMap<Int, Entry>()

    public data class Entry(val radio: OpenRadio, val seenAtEpochMs: Long)

    public fun record(radio: OpenRadio) {
        radios[radio.session] = Entry(radio, System.currentTimeMillis())
    }

    public fun forget(session: Int) {
        radios.remove(session)
    }

    public fun all(): List<Entry> = radios.values.sortedBy { it.radio.session }

    public fun get(session: Int): OpenRadio? = radios[session]?.radio
}

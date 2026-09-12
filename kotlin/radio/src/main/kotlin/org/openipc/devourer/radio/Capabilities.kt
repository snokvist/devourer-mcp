package org.openipc.devourer.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an opened adapter reports about itself.
 *
 * Every field here comes from devourer's own `AdapterCaps` / `TxCaps` /
 * `TxPowerCaps`, resolved from the chip identity at construction. That is the
 * whole reason this project needs no chipset table: the adapter answers, and
 * an operation is offered or refused on its answer.
 */
@Serializable
public data class RadioCapabilities(
    val supported: Boolean = false,
    val chip: String = "",
    @SerialName("marketing_names") val marketingNames: String = "",
    @SerialName("chip_id") val chipId: Int = 0,
    val generation: String = "",
    val variant: String = "",
    val transport: String = "",
    @SerialName("tx_chains") val txChains: Int = 0,
    @SerialName("rx_chains") val rxChains: Int = 0,
    @SerialName("bandwidths_mhz") val bandwidthsMhz: List<String> = emptyList(),
    @SerialName("tune_2g4") val tune2g4: BandRange = BandRange(),
    @SerialName("tune_5g") val tune5g: BandRange = BandRange(),
    @SerialName("characterized_2g4") val characterized2g4: BandRange = BandRange(),
    @SerialName("characterized_5g") val characterized5g: BandRange = BandRange(),
    @SerialName("ldpc_rx_ht") val ldpcRxHt: Boolean = false,
    @SerialName("ldpc_rx_vht") val ldpcRxVht: Boolean = false,
    @SerialName("vht_2g4_ok") val vht2g4: Boolean = false,
    val tx: TxCapabilities = TxCapabilities(),
    @SerialName("tx_power") val txPower: TxPowerCapabilities = TxPowerCapabilities(),
    /** Boolean capability gates: can this adapter do X at all. */
    val features: Map<String, Boolean> = emptyMap(),
    /** Numeric parameters of those capabilities: ranges, steps, defaults. */
    val parameters: Map<String, Int> = emptyMap(),
) {
    public val widths: Set<Int> get() = bandwidthsMhz.mapNotNull { it.toIntOrNull() }.toSet()

    public fun hasFeature(name: String): Boolean = features[name] == true

    public fun parameter(name: String): Int? = parameters[name]

    /**
     * Whether [mhz] is within a span the chip can *tune*, which is not the same
     * as a span whose TX power is calibrated. Devourer's 5 GHz synthesizer
     * reaches well past the regulatory channels; outside
     * [characterized5g] the radio still tunes but power is extrapolated.
     */
    public fun canTune(mhz: Int): Boolean =
        tune2g4.contains(mhz) || tune5g.contains(mhz)

    public fun isCharacterized(mhz: Int): Boolean =
        characterized2g4.contains(mhz) || characterized5g.contains(mhz)
}

@Serializable
public data class BandRange(
    val valid: Boolean = false,
    @SerialName("min_mhz") val minMhz: Int = 0,
    @SerialName("max_mhz") val maxMhz: Int = 0,
) {
    public fun contains(mhz: Int): Boolean = valid && mhz in minMhz..maxMhz
}

@Serializable
public data class TxCapabilities(
    val supported: Boolean = false,
    @SerialName("spatial_streams") val spatialStreams: Int = 0,
    @SerialName("stbc_ok") val stbc: Boolean = false,
    @SerialName("ldpc_ok") val ldpc: Boolean = false,
    @SerialName("sgi_ok") val shortGi: Boolean = false,
    @SerialName("bw_max_mhz") val maxWidthMhz: Int = 20,
)

/**
 * The TX-power knob, as an index/offset model rather than dBm.
 *
 * [stepMeasured] is the field that decides whether a power sweep produces
 * evidence or just numbers: when false, the dB-per-step slope was never
 * validated on air for this family, and any absolute claim built on it is an
 * extrapolation.
 */
@Serializable
public data class TxPowerCapabilities(
    val supported: Boolean = false,
    @SerialName("index_max") val indexMax: Int = 0,
    @SerialName("step_qdb") val stepQdb: Int = 0,
    @SerialName("step_measured") val stepMeasured: Boolean = false,
    @SerialName("offset_min_qdb") val offsetMinQdb: Int = 0,
    @SerialName("offset_max_qdb") val offsetMaxQdb: Int = 0,
    @SerialName("rate_diffs") val rateDiffs: Boolean = false,
    @SerialName("rate_diffs_measured") val rateDiffsMeasured: Boolean = false,
)

/**
 * Refusal to perform an operation the hardware cannot do.
 *
 * Thrown instead of degrading silently. A monitor asked for 160 MHz on an
 * 80 MHz part must fail here: quietly capturing at 80 and reporting success
 * would produce a capture whose every conclusion about bandwidth is wrong.
 */
public class CapabilityException(
    public val capability: String,
    public val radio: String,
    message: String,
) : RuntimeException("$radio cannot $capability: $message")

package org.openipc.devourer.characterize

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.radio.RadioManager

/**
 * Which physical adapter this is, and how confidently we know.
 *
 * Harder than it looks, and the honesty matters more than the answer. Two
 * MT7612U adapters on this bench share a USB id, a product string and a serial
 * of `000000000`. Nothing in their descriptors tells them apart. What does is
 * the EEPROM MAC — but a MediaTek only reads its EEPROM during chip init, so
 * that address is unreadable until the adapter has been brought up, while a
 * Realtek has it from construction.
 *
 * So identity has two tiers, and a record says which it used. A characterization
 * keyed on bus/port survives a reboot only if nothing was replugged; one keyed
 * on the MAC follows the hardware into any port. Silently mixing them would let
 * one adapter's evidence be filed under another's name.
 */
@Serializable
public data class AdapterIdentity(
    /** EEPROM MAC. The only identifier that follows the physical adapter. */
    @SerialName("permanent_mac") val permanentMac: String? = null,
    @SerialName("usb_id") val usbId: String,
    /** Physical position: bus and dotted port chain. Stable until replugged. */
    val locator: String,
    val product: String = "",
    val serial: String = "",
    val confidence: IdentityConfidence,
) {
    /**
     * The key this adapter's evidence is filed under.
     *
     * MAC-keyed where we have one; position-keyed otherwise, with the prefix
     * making the weaker basis visible in the filename itself.
     */
    public val key: String
        get() = permanentMac?.let { "mac-" + it.replace(":", "") }
            ?: "port-${usbId.replace(":", "")}-${locator.replace(Regex("[^A-Za-z0-9]"), "")}"

    public fun describe(): String = buildString {
        append(permanentMac ?: usbId)
        append(" at ").append(locator)
        if (confidence != IdentityConfidence.PERMANENT_MAC) {
            append(" [").append(confidence.explanation).append("]")
        }
    }

    public companion object {
        public fun of(radio: RadioManager.OpenRadio): AdapterIdentity {
            val mac = radio.permanentMac?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }
            return AdapterIdentity(
                permanentMac = mac,
                usbId = radio.device.usbId,
                locator = radio.device.locator,
                product = radio.device.product,
                serial = radio.device.serial,
                confidence = when {
                    mac != null -> IdentityConfidence.PERMANENT_MAC
                    radio.device.serial.isNotBlank() &&
                        radio.device.serial.trim('0').isNotEmpty() ->
                        IdentityConfidence.USB_SERIAL
                    else -> IdentityConfidence.PHYSICAL_PORT
                },
            )
        }
    }
}

@Serializable
public enum class IdentityConfidence(public val explanation: String) {
    /** Reads the adapter's own EEPROM address. Follows the hardware anywhere. */
    PERMANENT_MAC("identified by EEPROM MAC"),

    /**
     * A USB serial that looks like it might be unique. Weaker than it sounds:
     * many adapters ship a constant, and two identical units then share it.
     */
    USB_SERIAL("identified by USB serial, which may not be unique"),

    /**
     * Only the physical port. Correct until something is replugged, and unable
     * to distinguish two identical adapters swapped between ports.
     */
    PHYSICAL_PORT("identified only by physical port — replugging invalidates this"),
}

package org.openipc.devourer.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.radio.BridgeClient
import org.openipc.devourer.radio.RadioManager
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The one hardware-tagged test: the real bridge on real adapters.
 *
 * This is the JUnit home for the checks that need a radio, so their absence is
 * never mistaken for a pass. It is excluded unless `-PwithHardware` is given
 * (see the root `build.gradle.kts`), and even then it skips — rather than
 * fails — when the bridge is not running or no adapter is attached, which is
 * the `UNAVAILABLE` case, not a failure.
 *
 * It walks the loop every MCP radio tool depends on: list, open, describe,
 * bring up through the monitor path, tear down. The Python harnesses
 * (`tools/smoke-test.py`, the acceptance matrix and the per-feature scripts)
 * remain the full hardware runs; this is the seed for moving them onto the tag.
 *
 *     tools/host/bridge-ctl.sh start
 *     ./gradlew :mcp:test -PwithHardware --tests '*HardwareSmokeTest*'
 */
@Tag("hardware")
class HardwareSmokeTest {

    private val socket: Path = Path.of(
        System.getenv("DEVOURER_BRIDGE_SOCK")
            ?: "${System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"}/devourer-mcp/bridge.sock",
    )

    @Test
    fun `discovers, describes and monitors a real adapter`(): Unit = runBlocking {
        assumeTrue(
            socket.exists(),
            "no bridge socket at $socket — start it with tools/host/bridge-ctl.sh start",
        )
        BridgeClient(socket).use { bridge ->
            val radios = RadioManager(bridge)
            radios.connect()
            val devices = radios.list().devices
            assumeTrue(devices.isNotEmpty()) { "no Devourer-capable adapters attached" }

            val device = devices.first()
            val opened = radios.open(device.bus, device.address)
            try {
                check(opened.capabilities.chip.isNotBlank()) {
                    "open returned a session with no chip identity"
                }
                val described = radios.describe(opened.session)
                check(described.capabilities.chip == opened.capabilities.chip) {
                    "describe disagrees with open about the chip: " +
                        "${described.capabilities.chip} vs ${opened.capabilities.chip}"
                }
                // Bring the radio up and down through the same gate the MCP
                // tool uses. A backend that cannot tune refuses here rather
                // than reporting a monitor that never started.
                val channel = if (described.capabilities.tune2g4.valid) 6 else 36
                val monitor = radios.startMonitor(
                    opened.session,
                    ChannelSpec(channel = channel, width = ChannelWidth.ofMhz(20)),
                )
                check(monitor.isNotEmpty()) { "monitor_start returned an empty reply" }
                val stats = radios.stats(opened.session)
                check(stats.frames >= 0) { "monitor stats reported a negative frame count" }
                radios.stopMonitor(opened.session)
            } finally {
                radios.close(opened.session)
            }
        }
    }
}

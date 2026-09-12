package org.openipc.devourer.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.openipc.devourer.radio.BridgeClient
import org.openipc.devourer.radio.RadioManager

/**
 * The MCP server process.
 *
 * stdio is the MCP transport, so **nothing else may write to stdout** — a
 * single stray line makes the JSON-RPC stream unparseable and the session dies
 * with no useful error. All diagnostics go to stderr.
 */
public fun main(args: Array<String>): Unit = runBlocking {
    // Take the real stdout for the protocol and point System.out at stderr,
    // before anything else can write a byte.
    //
    // This is not belt-and-braces. kotlin-logging prints an initialization
    // banner to stdout the first time a logger is created, somewhere inside the
    // MCP SDK's own dependencies — one line, enough to make the first JSON-RPC
    // response unparseable and kill the session with no useful error. Any
    // library on the classpath can do the same. Owning the descriptor is the
    // only fix that does not depend on every dependency behaving.
    val protocolOut = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

    val socketPath = argValue(args, "--bridge-socket")
        ?.let { Path.of(it) }
        ?: defaultSocketPath()
    val exportDir = argValue(args, "--export-dir")
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.dir"), "var", "captures")
    Files.createDirectories(exportDir)

    System.err.println("devourer-mcp starting: bridge=$socketPath exports=$exportDir")

    val bridge = BridgeClient(socketPath)
    val hello = try {
        bridge.connect()
    } catch (e: Exception) {
        // Failing loudly here is right: a server that starts without a bridge
        // would advertise radio tools it cannot honour, and the model would
        // learn that from a confusing failure three calls later.
        System.err.println(
            """
            Cannot reach devourer-bridge at $socketPath
              ${e.message}

            Start it first:
              tools/host/bridge-ctl.sh start
            """.trimIndent(),
        )
        return@runBlocking
    }

    System.err.println(
        "bridge protocol ${hello.protocol.major}.${hello.protocol.minor}, " +
            "devourer ${hello.devourerCommit.take(12)}, " +
            "backends compiled: ${hello.backends.filter { it.compiled }.joinToString(", ") { it.name }}",
    )

    val scope = CoroutineScope(SupervisorJob())
    val radios = RadioManager(bridge)
    val captures = CaptureService(radios, scope)

    val server = Server(
        serverInfo = Implementation(
            name = "devourer-mcp",
            version = VERSION,
            title = "Devourer wireless instrument",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
        instructions = INSTRUCTIONS,
    )
    Tools(radios, captures, exportDir).registerAll(server)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                runCatching { captures.stopAll() }
                runCatching { bridge.close() }
            }
            scope.cancel()
        },
    )

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOut.asSink().buffered(),
    )
    val session = server.createSession(transport)

    // Stay alive until the client closes the session. The server owns radios
    // and captures; exiting early would strand a claimed adapter mid-capture.
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

private const val VERSION = "0.1.0"

/**
 * Given to the model at session start.
 *
 * The verification vocabulary is here rather than left implicit because the
 * single most likely way this instrument misleads someone is by letting
 * "the backend compiled" slide into "the hardware works".
 */
private val INSTRUCTIONS = """
    A programmable 802.11 instrument: real USB Wi-Fi adapters driven through OpenIPC Devourer.

    Workflow: radio_list -> radio_open -> monitor_start -> capture_summary / capture_query /
    frame_inspect. Frames stay on the host; these tools return summaries, references and exports.

    Two things to hold onto:

    1. Evidence has levels, and compiling is not one of the hardware ones.
       IMPLEMENTED_IN_SOURCE < BUILDS < DETECTED < INITIALIZED < RX_VERIFIED < TX_VERIFIED
       < CHARACTERIZED. A backend being compiled in says nothing about hardware. tx_send reporting
       success means the TX path accepted the frame, NOT that it reached the air — only an
       independent monitor radio establishes TX_VERIFIED.

    2. Absent is not zero. Chips fill different subsets of the PHY-status block: an MT7612U reports
       no RX timestamp and strips the FCS; a Realtek fills the timestamp but often leaves SNR empty.
       These tools omit fields a chip did not populate. Do not read a missing field as a measured
       zero, and do not average unreported values.

    Capabilities come from the adapter itself (radio_open), not from a chipset table. Operations an
    adapter cannot do are refused rather than silently downgraded.

    This is for owned and authorized hardware. Keep transmissions bounded.
""".trimIndent()

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun defaultSocketPath(): Path {
    val runtime = System.getenv("XDG_RUNTIME_DIR") ?: "/tmp"
    return Path.of(runtime, "devourer-mcp", "bridge.sock")
}

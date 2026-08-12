package com.shahar.shaharsat.ble

import com.shahar.shaharsat.data.Command
import com.shahar.shaharsat.data.CommandResponse
import com.shahar.shaharsat.data.encodeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Outcome of [CommandSender.send] — mirrors docs/COMMANDS.md §2's error codes via [CommandResponse.err]. */
sealed interface CommandOutcome {
    data class Success(val response: CommandResponse) : CommandOutcome
    data class Failed(val response: CommandResponse) : CommandOutcome
    data object NotConnected : CommandOutcome
    data object TimedOut : CommandOutcome
}

/**
 * Assigns a monotonic sequence number to every outgoing command and
 * matches the eventual Response-characteristic notification back to it.
 *
 * This exists specifically so a resend from Compose recomposition can
 * never re-drive an actuator: the firmware rejects a repeated `seq`
 * (ERR_DUPLICATE_SEQ) at the protocol level, and this class is what
 * guarantees each *intended* send gets a fresh one. Never construct the
 * JSON payload anywhere else — always go through [send]. See
 * docs/BLE_PROTOCOL.md §7.3 and docs/COMMANDS.md §3.
 */
class CommandSender(
    private val bleClient: BleClient,
    private val responses: SharedFlow<CommandResponse>,
    private val scope: CoroutineScope
) {
    private val seqCounter = AtomicInteger(1)

    /** Suspends until the matching response arrives or [timeoutMs] elapses. Never auto-retries. */
    suspend fun send(command: Command, timeoutMs: Long = 5_000L): CommandOutcome {
        val seq = seqCounter.getAndIncrement().let { if (it > 65535) { seqCounter.set(1); 1 } else it }
        val payload = encodeCommand(command, seq)

        if (!bleClient.sendCommand(payload)) {
            return CommandOutcome.NotConnected
        }

        val response = withTimeoutOrNull(timeoutMs) {
            responses.filter { it.seq == seq }.first()
        } ?: return CommandOutcome.TimedOut

        return if (response.ok) CommandOutcome.Success(response) else CommandOutcome.Failed(response)
    }

    /** Fire-and-observe variant for callers that don't want to suspend (e.g. a Composable's onClick). */
    fun sendAsync(command: Command, onResult: (CommandOutcome) -> Unit = {}): Job =
        scope.launch { onResult(send(command)) }
}

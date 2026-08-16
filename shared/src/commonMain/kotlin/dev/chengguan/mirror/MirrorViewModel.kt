package dev.chengguan.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chengguan.mirror.security.AuthResult
import dev.chengguan.mirror.security.DeviceAuthenticator
import dev.chengguan.mirror.security.PAIRING_KEY
import dev.chengguan.mirror.security.STATUS_EXPANDED_KEY
import dev.chengguan.mirror.security.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class MirrorUiState(
    val locked: Boolean = true,
    val authenticating: Boolean = false,
    val canAuthenticate: Boolean = false,
    val pairing: Pairing? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val status: String? = "Unlock, then tap Scan QR or paste the pairing URL.",
    val sessionStatus: SessionStatus? = null,
    val statusExpanded: Boolean = true,
)

class MirrorViewModel(
    private val store: SecureStore,
    private val authenticator: DeviceAuthenticator,
    private val apiFactory: (Pairing) -> MirrorApi = { platformMirrorApi(it) },
    private val incomingLink: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MirrorUiState(
            canAuthenticate = authenticator.canAuthenticate,
            statusExpanded = store.read(STATUS_EXPANDED_KEY)?.decodeToString() != "0",
        ),
    )
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    init {
        incomingLink?.let { applyLink(it) }
        viewModelScope.launch {
            while (isActive) {
                delay(2_500)
                refresh()
            }
        }
    }

    fun unlock() {
        if (_state.value.authenticating || !_state.value.locked) return
        viewModelScope.launch {
            _state.value = _state.value.copy(authenticating = true)
            when (authenticator.authenticate("Unlock Mirror")) {
                AuthResult.Success, AuthResult.Unavailable -> {
                    loadStoredPairing()
                    _state.value = _state.value.copy(locked = false, authenticating = false)
                    refresh()
                }
                AuthResult.Cancelled ->
                    _state.value = _state.value.copy(authenticating = false)
                AuthResult.Failed ->
                    _state.value = _state.value.copy(
                        authenticating = false,
                        status = "Could not confirm it is you.",
                    )
            }
        }
    }

    fun lock() {
        _state.value = _state.value.copy(locked = true, messages = emptyList(), draft = "")
    }

    fun onDraft(value: String) {
        if (value.length > dev.chengguan.mirror.security.InputLimits.DRAFT_MAX) return
        _state.value = _state.value.copy(draft = value)
    }

    fun applyLink(raw: String) {
        val pairing = parsePairing(raw)
        if (pairing == null) {
            _state.value = _state.value.copy(status = "That pairing code is not valid.")
            return
        }
        if (pairing.requireApple && !canProveAppleId()) {
            _state.value = _state.value.copy(
                status = "This pairing is iPhone-only. It is locked to an Apple ID.",
            )
            return
        }
        store.write(PAIRING_KEY, encodePairing(pairing).encodeToByteArray())
        _state.value = _state.value.copy(
            pairing = pairing,
            status = if (pairing.requireApple) "Confirm with your Apple ID…" else "Paired. Fetching this session…",
        )
        viewModelScope.launch {
            if (pairing.requireApple && !proveApple(pairing)) return@launch
            refresh()
        }
    }

    fun send() {
        val pairing = _state.value.pairing ?: return
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return
        val optimistic = collapseDuplicateUserTurns(_state.value.messages + ChatMessage("user", text))
        _state.value = _state.value.copy(
            sending = true,
            draft = "",
            messages = optimistic,
            status = "Sending…",
        )
        viewModelScope.launch {
            try {
                apiFactory(pairing).send(text).fold(
                    onSuccess = {
                        _state.value = _state.value.copy(sending = false, status = null)
                    },
                    onFailure = {
                        _state.value = _state.value.copy(
                            sending = false,
                            status = "Could not send. Is the Mac companion still running on Wi-Fi?",
                        )
                    },
                )
            } catch (_: Throwable) {
                _state.value = _state.value.copy(
                    sending = false,
                    status = "Could not send. Is the Mac companion still running on Wi-Fi?",
                )
            }
        }
    }

    private fun loadStoredPairing() {
        val raw = store.read(PAIRING_KEY)?.decodeToString() ?: return
        parsePairing(raw)?.let { _state.value = _state.value.copy(pairing = it) }
    }

    private suspend fun refresh() {
        val pairing = _state.value.pairing ?: return
        if (_state.value.locked) return
        val api = apiFactory(pairing)
        runCatching {
            val first = api.pull()
            val pulled = if (first.isFailure && pairing.requireApple && isAppleRequired(first.exceptionOrNull())) {
                if (!proveApple(pairing)) return
                api.pull()
            } else {
                first
            }
            pulled.onSuccess { snap ->
                _state.value = _state.value.copy(
                    messages = collapseDuplicateUserTurns(snap.messages),
                    sessionStatus = snap.status ?: _state.value.sessionStatus,
                    status = if (snap.status != null) null else _state.value.status,
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    status = "Could not reach the Mac. Keep Tailscale on, and leave the companion running.",
                )
            }
        }
    }

    private suspend fun proveApple(pairing: Pairing): Boolean {
        val token = requestAppleIdentityToken()
        if (token == null) {
            _state.value = _state.value.copy(
                pairing = null,
                status = "Apple ID is required for this pairing.",
            )
            store.delete(PAIRING_KEY)
            return false
        }
        val bound = apiFactory(pairing).bindApple(token)
        if (bound.isFailure) {
            _state.value = _state.value.copy(
                pairing = null,
                status = "That Apple ID is not allowed for this pairing.",
            )
            store.delete(PAIRING_KEY)
            return false
        }
        _state.value = _state.value.copy(status = "Paired. Fetching this session…")
        return true
    }

    private suspend fun requestAppleIdentityToken(): String? =
        suspendCancellableCoroutine { continuation ->
            val host = AppleSignIn.host
            if (host == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            host.signIn { token, _ ->
                if (continuation.isActive) continuation.resume(token?.trim()?.take(8_192))
            }
        }

    private fun isAppleRequired(error: Throwable?): Boolean {
        val msg = error?.message.orEmpty()
        return msg.contains("403") || msg.contains("apple", ignoreCase = true)
    }

    fun toggleStatusExpanded() {
        val next = !_state.value.statusExpanded
        _state.value = _state.value.copy(statusExpanded = next)
        store.write(STATUS_EXPANDED_KEY, (if (next) "1" else "0").encodeToByteArray())
    }

    fun unpair() {
        store.delete(PAIRING_KEY)
        _state.value = _state.value.copy(
            pairing = null,
            messages = emptyList(),
            draft = "",
            status = "Unpaired. Tap Scan QR or paste the pairing URL.",
        )
    }
}

fun encodePairing(pairing: Pairing): String =
    "grok-mirror://v1?host=${pairing.host}&port=${pairing.port}" +
        "&sid=${pairing.sessionId}&tok=${pairing.token}&fp=${pairing.fingerprint}" +
        if (pairing.requireApple) "&apple=1" else ""

/** Inbox + session log can both carry the live phone turn. Collapse only
 *  consecutive identical user lines so a later repeat still shows. */
fun collapseDuplicateUserTurns(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.size < 2) return messages
    val out = ArrayList<ChatMessage>(messages.size)
    for (message in messages) {
        val prev = out.lastOrNull()
        if (prev != null && prev.role == "user" && message.role == "user" && prev.text == message.text) {
            continue
        }
        out.add(message)
    }
    return out
}

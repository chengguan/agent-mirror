package dev.chengguan.mirror

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chengguan.mirror.security.AuthResult
import dev.chengguan.mirror.security.DeviceAuthenticator
import dev.chengguan.mirror.security.InputLimits
import dev.chengguan.mirror.security.PAIRING_KEY
import dev.chengguan.mirror.security.ProtectedFiles
import dev.chengguan.mirror.security.STATUS_EXPANDED_KEY
import dev.chengguan.mirror.security.SecureStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

data class MirrorUiState(
    val locked: Boolean = true,
    val authenticating: Boolean = false,
    val canAuthenticate: Boolean = false,
    val sessions: List<SessionRecord> = emptyList(),
    val activeId: String? = null,
    val adding: Boolean = false,
    val pairing: Pairing? = null,
    val archived: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val status: String? = "Unlock, then tap Scan QR or paste the pairing URL.",
    val sessionStatus: SessionStatus? = null,
    val statusExpanded: Boolean = true,
    val backgroundRefresh: Boolean = true,
    val pendingUnpairId: String? = null,
    val renamingId: String? = null,
)

class MirrorViewModel(
    private val store: SecureStore,
    private val authenticator: DeviceAuthenticator,
    private val files: ProtectedFiles,
    private val apiFactory: (Pairing) -> MirrorApi = { platformMirrorApi(it) },
    private val incomingLink: String? = null,
) : ViewModel() {

    private val threads = mutableMapOf<String, List<ChatMessage>>()
    private val counts = mutableMapOf<String, Int>()
    private val drafts = mutableMapOf<String, String>()
    private val inFlight = mutableSetOf<String>()
    private val sendJobs = mutableMapOf<String, Job>()
    private val sessionLock = Mutex()
    private val persistLock = Mutex()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    private fun MirrorUiState.withSending(): MirrorUiState =
        copy(sending = activeId != null && activeId in inFlight)

    private fun publish(transform: (MirrorUiState) -> MirrorUiState) {
        _state.update { transform(it).withSending() }
    }

    init {
        incomingLink?.let { applyLink(it) }
        viewModelScope.launch {
            while (isActive) {
                delay(2_500)
                poll()
            }
        }
    }

    fun unlock() {
        if (_state.value.authenticating || !_state.value.locked) return
        viewModelScope.launch {
            _state.value = _state.value.copy(authenticating = true)
            when (authenticator.authenticate("Unlock Mirror")) {
                AuthResult.Success, AuthResult.Unavailable -> {
                    _state.value = loadCatalog().copy(
                        locked = false,
                        authenticating = false,
                        canAuthenticate = _state.value.canAuthenticate,
                        statusExpanded = store.read(STATUS_EXPANDED_KEY)?.decodeToString() != "0",
                        backgroundRefresh = store.read(BACKGROUND_REFRESH_KEY)?.decodeToString() != "0",
                    ).withSending()
                    showActive()
                    poll()
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
        persistActiveDraft()
        persistCatalog()
        _state.update {
            it.copy(
                locked = true,
                messages = emptyList(),
                sending = false,
                pendingUnpairId = null,
                renamingId = null,
            )
        }
    }

    fun onDraft(value: String) {
        if (value.length > InputLimits.DRAFT_MAX) return
        _state.value = _state.value.copy(draft = value)
    }

    fun selectSession(id: String) {
        persistActiveDraft()
        if (id == ADD_TAB_ID) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sessionLock.withLock {
                    publish {
                        it.copy(
                            adding = true,
                            pairing = null,
                            archived = false,
                            messages = emptyList(),
                            draft = "",
                            sessionStatus = null,
                            status = "Scan a QR or paste a pairing URL to add a session.",
                        )
                    }
                }
            }
            return
        }
        val rec = _state.value.sessions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionLock.withLock {
                publish {
                    it.copy(
                        activeId = id,
                        adding = false,
                        pairing = rec.pairing,
                        archived = rec.archived,
                        sessionStatus = null,
                        sessions = it.sessions.map { row ->
                            if (row.id == id) row.copy(unread = false) else row
                        },
                    )
                }
                showActiveLocked()
            }
            persistCatalog()
            if (!rec.archived) {
                refreshSession(rec, active = true)
            }
        }
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
        persistActiveDraft()
        val existing = _state.value.sessions.firstOrNull { it.id == pairing.sessionId }
        val rec = SessionRecord(
            id = pairing.sessionId,
            name = existing?.name?.takeIf { it.isNotBlank() } ?: defaultSessionName(pairing),
            pairing = pairing,
            archived = false,
        )
        val sessions = if (existing == null) {
            if (_state.value.sessions.size >= MAX_SESSIONS) {
                _state.value = _state.value.copy(status = "Remove a session before adding another.")
                return
            }
            _state.value.sessions + rec
        } else {
            _state.value.sessions.map { if (it.id == rec.id) rec else it }
        }
        _state.value = _state.value.copy(
            sessions = sessions,
            activeId = rec.id,
            adding = false,
            pairing = pairing,
            archived = false,
            status = if (pairing.requireApple) "Confirm with your Apple ID…" else "Paired. Fetching this session…",
        )
        persistCatalog()
        store.write(PAIRING_KEY, encodePairing(pairing).encodeToByteArray())
        viewModelScope.launch {
            if (pairing.requireApple && !proveApple(pairing, rec.id)) return@launch
            refreshSession(rec, active = true)
            if (liveRow(rec.id)?.pairing?.token == pairing.token) {
                files.delete(archiveFileName(rec.id))
            }
        }
    }

    fun send() {
        val pairing = _state.value.pairing ?: return
        val sessionId = _state.value.activeId ?: return
        if (_state.value.archived) return
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var started = false
            try {
                started = sessionLock.withLock {
                    if (sessionId in inFlight || liveRow(sessionId) == null) return@withLock false
                    val job = coroutineContext[Job] ?: return@withLock false
                    inFlight.add(sessionId)
                    sendJobs[sessionId] = job
                    val previous = threads[sessionId] ?: _state.value.messages
                    val optimistic = collapseDuplicateUserTurns(previous + ChatMessage("user", text))
                    threads[sessionId] = optimistic
                    persistDraft(sessionId, "")
                    publish {
                        it.copy(
                            draft = if (it.activeId == sessionId) "" else it.draft,
                            messages = if (it.activeId == sessionId) optimistic else it.messages,
                            status = if (it.activeId == sessionId) "Sending…" else it.status,
                        )
                    }
                    true
                }
                if (!started) return@launch
                val ok = try {
                    apiFactory(pairing).send(text).isSuccess
                } catch (_: Throwable) {
                    false
                }
                sessionLock.withLock {
                    if (!ok) {
                        threads[sessionId] = rollbackOptimisticUser(
                            threads[sessionId].orEmpty(),
                            text,
                        )
                        persistDraft(sessionId, text)
                    }
                    val stillActive = _state.value.activeId == sessionId && !_state.value.adding
                    if (stillActive) {
                        publish {
                            it.copy(
                                messages = threads[sessionId] ?: it.messages,
                                draft = if (!ok) text else it.draft,
                                status = if (ok) {
                                    null
                                } else {
                                    "Could not send. Is the Mac companion still running on Wi-Fi?"
                                },
                            )
                        }
                    }
                }
            } finally {
                if (started) {
                    sessionLock.withLock {
                        inFlight.remove(sessionId)
                        sendJobs.remove(sessionId)
                        publish { it }
                    }
                }
            }
        }
    }

    fun toggleStatusExpanded() {
        val next = !_state.value.statusExpanded
        _state.value = _state.value.copy(statusExpanded = next)
        store.write(STATUS_EXPANDED_KEY, (if (next) "1" else "0").encodeToByteArray())
    }

    fun toggleBackgroundRefresh() {
        val next = !_state.value.backgroundRefresh
        _state.value = _state.value.copy(backgroundRefresh = next)
        store.write(BACKGROUND_REFRESH_KEY, (if (next) "1" else "0").encodeToByteArray())
    }

    fun startRename(id: String) {
        if (_state.value.sessions.none { it.id == id }) return
        _state.value = _state.value.copy(renamingId = id)
    }

    fun cancelRename() {
        _state.value = _state.value.copy(renamingId = null)
    }

    fun renameSession(id: String, name: String) {
        val clipped = name.trim().take(40)
        if (clipped.isEmpty()) {
            _state.value = _state.value.copy(renamingId = null)
            return
        }
        _state.value = _state.value.copy(
            sessions = _state.value.sessions.map { if (it.id == id) it.copy(name = clipped) else it },
            renamingId = null,
        )
        persistCatalog()
    }

    fun requestUnpair() {
        val id = _state.value.activeId ?: return
        if (_state.value.sessions.none { it.id == id }) return
        _state.value = _state.value.copy(pendingUnpairId = id)
    }

    fun cancelUnpair() {
        _state.value = _state.value.copy(pendingUnpairId = null)
    }

    fun confirmUnpair(saveHistory: Boolean) {
        val id = _state.value.pendingUnpairId ?: return
        val rec = _state.value.sessions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionLock.withLock {
                sendJobs.remove(id)?.cancel()
                inFlight.remove(id)
            }
        }
        persistActiveDraft()
        if (saveHistory) {
            val messages = _state.value.messages.ifEmpty { threads[id].orEmpty() }
            val payload = encodeArchive(messages).encodeToByteArray()
            if (!files.write(archiveFileName(id), payload)) {
                _state.value = _state.value.copy(
                    pendingUnpairId = null,
                    status = "Could not save the conversation on this phone. Still paired.",
                )
                return
            }
            persistDraft(id, "")
            val archived = rec.copy(pairing = null, archived = true, unread = false)
            val sessions = _state.value.sessions.map { if (it.id == id) archived else it }
            _state.value = _state.value.copy(
                sessions = sessions,
                pendingUnpairId = null,
                pairing = null,
                archived = true,
                sending = false,
                status = "Saved on this phone. The Mac is unpaired.",
                sessionStatus = null,
            )
        } else {
            persistDraft(id, "")
            files.delete(archiveFileName(id))
            threads.remove(id)
            counts.remove(id)
            drafts.remove(id)
            val sessions = _state.value.sessions.filter { it.id != id }
            val next = sessions.firstOrNull { !it.archived } ?: sessions.firstOrNull()
            _state.value = _state.value.copy(
                sessions = sessions,
                activeId = next?.id,
                adding = next == null,
                pendingUnpairId = null,
                pairing = next?.pairing,
                archived = next?.archived == true,
                messages = emptyList(),
                draft = "",
                sending = false,
                sessionStatus = null,
                status = if (next == null) {
                    "Unpaired. Tap + to scan a QR or paste a pairing URL."
                } else {
                    "Session removed."
                },
            )
            showActive()
        }
        persistCatalog()
        val live = _state.value.sessions.firstOrNull { it.pairing != null }?.pairing
        if (live == null) store.delete(PAIRING_KEY)
        else store.write(PAIRING_KEY, encodePairing(live).encodeToByteArray())
    }

    private suspend fun poll() {
        if (_state.value.locked) return
        val live = _state.value.sessions.filter { it.pairing != null && !it.archived }
        if (live.isEmpty()) return
        if (_state.value.backgroundRefresh) {
            val activeId = _state.value.activeId
            val adding = _state.value.adding
            val visible = live.firstOrNull { it.id == activeId && !adding }
            val rest = live.filter { it.id != visible?.id }
            coroutineScope {
                val visibleJob = visible?.let { rec ->
                    async { refreshSession(rec, active = true) }
                }
                val restJobs = rest.map { rec ->
                    async { refreshSession(rec, active = false) }
                }
                visibleJob?.await()
                restJobs.awaitAll()
            }
        } else {
            val rec = live.firstOrNull { it.id == _state.value.activeId && !_state.value.adding } ?: return
            refreshSession(rec, active = true)
        }
    }

    private suspend fun refreshSession(rec: SessionRecord, active: Boolean) {
        val pairing = rec.pairing ?: return
        val api = apiFactory(pairing)
        val pulled = runCatching {
            val first = api.pull()
            if (first.isFailure && pairing.requireApple && isAppleRequired(first.exceptionOrNull())) {
                if (!proveApple(pairing, rec.id)) return
                api.pull()
            } else {
                first
            }
        }.getOrElse { Result.failure(it) }

        val snap = pulled.getOrNull()
        if (snap != null) {
            var nameChanged = false
            sessionLock.withLock {
                val row = liveRow(rec.id) ?: return@withLock
                if (row.pairing?.token != pairing.token) return@withLock
                val messages = collapseDuplicateUserTurns(snap.messages)
                val previous = counts[rec.id]
                val sendingHere = rec.id in inFlight
                if (!sendingHere) {
                    threads[rec.id] = messages
                    counts[rec.id] = messages.size
                }
                val unread = previous != null && !active && !sendingHere && messages.size > previous
                val hostname = snap.status?.hostname?.trim().orEmpty()
                val named = if (hostname.isNotEmpty() && rec.name == defaultSessionName(pairing)) {
                    defaultSessionName(pairing, hostname)
                } else {
                    rec.name
                }
                nameChanged = named != rec.name
                val stillActive = active &&
                    _state.value.activeId == rec.id &&
                    !_state.value.adding &&
                    liveRow(rec.id) != null
                publish { cur ->
                    cur.copy(
                        sessions = cur.sessions.map { item ->
                            when (item.id) {
                                rec.id -> item.copy(
                                    name = named,
                                    unread = if (active) false else item.unread || unread,
                                )
                                else -> item
                            }
                        },
                        messages = if (stillActive && !sendingHere) messages else cur.messages,
                        sessionStatus = if (stillActive) snap.status else cur.sessionStatus,
                        status = if (stillActive && snap.status != null) null else cur.status,
                        pairing = if (stillActive) pairing else cur.pairing,
                        archived = if (stillActive) false else cur.archived,
                    )
                }
            }
            if (nameChanged) persistCatalog()
            return
        }

        sessionLock.withLock {
            if (liveRow(rec.id) == null) return@withLock
            if (active && _state.value.activeId == rec.id && !_state.value.adding) {
                publish {
                    it.copy(
                        status = "Could not reach the Mac. Keep Tailscale on, and leave the companion running.",
                    )
                }
            } else if (counts[rec.id] != null) {
                publish { cur ->
                    cur.copy(
                        sessions = cur.sessions.map { item ->
                            if (item.id == rec.id) item.copy(unread = true) else item
                        },
                    )
                }
            }
        }
    }

    private suspend fun proveApple(pairing: Pairing, sessionId: String): Boolean {
        val token = requestAppleIdentityToken()
        if (token == null) {
            dropLive(sessionId, "Apple ID is required for this pairing.")
            return false
        }
        val bound = apiFactory(pairing).bindApple(token)
        if (bound.isFailure) {
            dropLive(sessionId, "That Apple ID is not allowed for this pairing.")
            return false
        }
        if (_state.value.activeId == sessionId) {
            _state.value = _state.value.copy(status = "Paired. Fetching this session…")
        }
        return true
    }

    private fun liveRow(id: String): SessionRecord? {
        val row = _state.value.sessions.firstOrNull { it.id == id } ?: return null
        return if (!row.archived && row.pairing != null) row else null
    }

    private fun dropLive(sessionId: String, message: String) {
        persistDraft(sessionId, "")
        val hasArchive = files.read(archiveFileName(sessionId)) != null
        val sessions = if (hasArchive) {
            _state.value.sessions.map { rec ->
                if (rec.id == sessionId) rec.copy(pairing = null, archived = true, unread = false) else rec
            }
        } else {
            _state.value.sessions.filter { it.id != sessionId }
        }
        val rec = sessions.firstOrNull { it.id == sessionId } ?: sessions.firstOrNull()
        _state.value = _state.value.copy(
            sessions = sessions,
            activeId = if (_state.value.activeId == sessionId) rec?.id else _state.value.activeId,
            pairing = if (_state.value.activeId == sessionId) rec?.pairing else _state.value.pairing,
            archived = if (_state.value.activeId == sessionId) rec?.archived == true else _state.value.archived,
            adding = sessions.isEmpty(),
            status = message,
            sessionStatus = if (_state.value.activeId == sessionId) null else _state.value.sessionStatus,
        )
        persistCatalog()
        val live = sessions.firstOrNull { it.pairing != null }?.pairing
        if (live == null) store.delete(PAIRING_KEY)
        else store.write(PAIRING_KEY, encodePairing(live).encodeToByteArray())
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

    private fun initialState(): MirrorUiState {
        val loaded = loadCatalog()
        return loaded.copy(
            canAuthenticate = authenticator.canAuthenticate,
            statusExpanded = store.read(STATUS_EXPANDED_KEY)?.decodeToString() != "0",
            backgroundRefresh = store.read(BACKGROUND_REFRESH_KEY)?.decodeToString() != "0",
        )
    }

    private fun loadCatalog(): MirrorUiState {
        val raw = store.read(SESSIONS_KEY)?.decodeToString()
        var (sessions, activeId) = if (raw.isNullOrEmpty()) {
            emptyList<SessionRecord>() to null
        } else {
            parseSessionCatalog(raw)
        }
        if (sessions.isEmpty()) {
            store.read(PAIRING_KEY)?.decodeToString()?.let(::migrateLegacyPairing)?.let { rec ->
                sessions = listOf(rec)
                activeId = rec.id
            }
        }
        val adding = sessions.isEmpty()
        val rec = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
        return MirrorUiState(
            sessions = sessions,
            activeId = rec?.id,
            adding = adding,
            pairing = rec?.pairing,
            archived = rec?.archived == true,
            draft = rec?.id?.let(::storedDraft).orEmpty(),
        )
    }

    private fun showActive() {
        showActiveLocked()
    }

    private fun showActiveLocked() {
        val id = _state.value.activeId
        val rec = _state.value.sessions.firstOrNull { it.id == id }
        if (rec == null) {
            publish {
                it.copy(
                    adding = true,
                    pairing = null,
                    archived = false,
                    messages = emptyList(),
                    draft = "",
                )
            }
            return
        }
        val messages = when {
            rec.archived -> storedArchive(rec.id)
            else -> threads[rec.id].orEmpty()
        }
        publish {
            it.copy(
                pairing = rec.pairing,
                archived = rec.archived,
                adding = false,
                messages = messages,
                draft = storedDraft(rec.id),
                sessionStatus = if (rec.archived || rec.pairing == null) null else it.sessionStatus,
                status = when {
                    rec.archived -> "Saved conversation. This tab is not connected."
                    rec.pairing == null -> "Scan a QR or paste a pairing URL."
                    else -> it.status
                },
            )
        }
    }

    private fun persistActiveDraft() {
        persistDraft(_state.value.activeId, _state.value.draft)
    }

    private fun persistCatalog() {
        viewModelScope.launch {
            persistLock.withLock {
                val raw = encodeSessionCatalog(_state.value.sessions, _state.value.activeId)
                val bytes = raw.encodeToByteArray()
                if (bytes.size <= InputLimits.MAX_STORE_BYTES) {
                    withContext(Dispatchers.Default) {
                        store.write(SESSIONS_KEY, bytes)
                    }
                }
            }
        }
    }

    private fun storedDraft(sessionId: String): String {
        drafts[sessionId]?.let { return it }
        val raw = store.read(draftStoreKey(sessionId))?.decodeToString() ?: return ""
        val value = raw.take(minOf(InputLimits.DRAFT_MAX, InputLimits.MAX_STORE_BYTES))
        drafts[sessionId] = value
        return value
    }

    private fun persistDraft(sessionId: String?, text: String) {
        if (sessionId.isNullOrEmpty()) return
        val clipped = text.take(minOf(InputLimits.DRAFT_MAX, InputLimits.MAX_STORE_BYTES))
        drafts[sessionId] = clipped
        val key = draftStoreKey(sessionId)
        viewModelScope.launch {
            persistLock.withLock {
                withContext(Dispatchers.Default) {
                    if (clipped.isEmpty()) store.delete(key)
                    else store.write(key, clipped.encodeToByteArray())
                }
            }
        }
    }

    private fun storedArchive(sessionId: String): List<ChatMessage> {
        val raw = files.read(archiveFileName(sessionId))?.decodeToString() ?: return emptyList()
        return parseArchive(raw)
    }
}

fun encodePairing(pairing: Pairing): String =
    "grok-mirror://v1?host=${pairing.host}&port=${pairing.port}" +
        "&sid=${pairing.sessionId}&tok=${pairing.token}&fp=${pairing.fingerprint}" +
        if (pairing.requireApple) "&apple=1" else ""

/** Drop a failed optimistic send if it is still the last user bubble. */
fun rollbackOptimisticUser(messages: List<ChatMessage>, text: String): List<ChatMessage> {
    val last = messages.lastOrNull() ?: return messages
    if (last.role != "user" || last.text != text) return messages
    return messages.dropLast(1)
}

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

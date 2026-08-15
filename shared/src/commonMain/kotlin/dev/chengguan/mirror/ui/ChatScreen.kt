package dev.chengguan.mirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chengguan.mirror.APP_NAME
import dev.chengguan.mirror.APP_VERSION_LABEL
import dev.chengguan.mirror.ChatMessage
import dev.chengguan.mirror.ChatStyle
import dev.chengguan.mirror.MirrorUiState
import dev.chengguan.mirror.QrScanner
import dev.chengguan.mirror.SessionStatus
import dev.chengguan.mirror.parseInlineMarkdown

@Composable
fun ChatScreen(
    state: MirrorUiState,
    onUnlock: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onApplyLink: (String) -> Unit,
    onUnpair: () -> Unit,
    onLock: () -> Unit,
    onToggleStatus: () -> Unit = {},
) {
    if (state.locked) {
        LockScreen(state = state, onUnlock = onUnlock)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(APP_NAME, style = MaterialTheme.typography.headlineMedium)
                Text(
                    state.pairing?.let { "Session ${it.sessionId.take(8)}… · ${it.host}:${it.port}" }
                        ?: APP_VERSION_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLock) { Text("Lock") }
        }

        if (state.pairing == null) {
            PairPane(status = state.status, onApplyLink = onApplyLink)
        } else {
            UsageBar(state.sessionStatus)
            ThreadPane(
                modifier = Modifier.weight(1f),
                messages = state.messages,
                draft = state.draft,
                sending = state.sending,
                status = state.status,
                sessionStatus = state.sessionStatus,
                statusExpanded = state.statusExpanded,
                onDraft = onDraft,
                onSend = onSend,
                onUnpair = onUnpair,
                onToggleStatus = onToggleStatus,
            )
        }
    }
}

@Composable
private fun LockScreen(state: MirrorUiState, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(APP_NAME, style = MaterialTheme.typography.headlineLarge)
        Text(
            APP_VERSION_LABEL,
            style = MaterialTheme.typography.titleSmall,
            color = AccentCyan,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Unlock to open the pairing secret and this Grok thread. Same Wi-Fi only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(onClick = onUnlock, enabled = !state.authenticating) {
            Text(if (state.authenticating) "Waiting…" else "Unlock")
        }
        state.status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun PairPane(status: String?, onApplyLink: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Scan the pairing QR with the camera, or paste the pairing URL from the Mac companion.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                val host = QrScanner.host
                if (host == null) {
                    scanStatus = "Camera scanner is not available on this build."
                    return@Button
                }
                if (scanning) return@Button
                scanning = true
                scanStatus = null
                host.scan { payload, error ->
                    scanning = false
                    if (payload != null) {
                        val clipped = payload.trim().take(2_000)
                        draft = clipped
                        onApplyLink(clipped)
                    } else if (!error.isNullOrBlank()) {
                        scanStatus = error
                    }
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "Opening camera…" else "Scan QR") }
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 2_000) draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing URL") },
            singleLine = true,
            enabled = !scanning,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onApplyLink(draft) }),
        )
        OutlinedButton(
            onClick = { onApplyLink(draft) },
            enabled = !scanning && draft.isNotBlank(),
        ) { Text("Pair") }
        (scanStatus ?: status)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ThreadPane(
    modifier: Modifier,
    messages: List<ChatMessage>,
    draft: String,
    sending: Boolean,
    status: String?,
    sessionStatus: SessionStatus?,
    statusExpanded: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onUnpair: () -> Unit,
    onToggleStatus: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard(sessionStatus, sending, statusExpanded, onToggleStatus)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Waiting for this session’s messages. Keep the Mac companion running.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(
                messages,
                key = { index, message -> "$index:${message.role}:${message.text.hashCode()}" },
            ) { _, message ->
                MessageBubble(message)
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            minLines = 1,
            maxLines = 6,
            enabled = !sending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !sending && draft.isNotBlank()) {
                Text(if (sending) "Sending…" else "Send")
            }
            OutlinedButton(onClick = onUnpair, enabled = !sending) { Text("Unpair") }
        }
    }
}

@Composable
private fun UsageBar(sessionStatus: SessionStatus?) {
    val usage = sessionStatus ?: return
    val hasBilling = usage.billing || usage.billingKind.isNotEmpty()
    if (!hasBilling && usage.tokensWindow <= 0 && usage.usagePercent <= 0) return
    var open by remember { mutableStateOf(false) }
    val percent = if (hasBilling) usage.usagePercent.coerceIn(0, 100) else 0
    val barColor = when {
        !hasBilling -> MaterialTheme.colorScheme.onSurfaceVariant
        percent >= 85 -> AccentError
        percent >= 70 -> AccentWarn
        else -> AccentCyan
    }
    Column(
        modifier = Modifier.fillMaxWidth().clickable { open = true },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Usage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (hasBilling) "$percent%" else "—",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(
            progress = { if (hasBilling) percent / 100f else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = BgHighlight,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Usage") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Account credit (/usage)", fontWeight = FontWeight.SemiBold, color = AccentCyan)
                    if (hasBilling) {
                        val kind = when (usage.billingKind) {
                            "weekly" -> "weekly"
                            "monthly" -> "monthly"
                            else -> "billing"
                        }
                        Text("${usage.usagePercent.coerceIn(0, 100)}% of this $kind period")
                        if (usage.subscriptionTier.isNotBlank()) {
                            Text("Plan: ${usage.subscriptionTier.take(40)}")
                        }
                        if (usage.billingResets.isNotBlank()) {
                            Text("Resets: ${usage.billingResets}")
                        }
                    } else {
                        Text("No /usage snapshot yet. Keep the Mac TUI open so it can refresh billing.")
                    }
                    Text("Session context", fontWeight = FontWeight.SemiBold, color = AccentCyan)
                    if (usage.tokensWindow > 0) {
                        Text("${formatCount(usage.tokensUsed)} / ${formatCount(usage.tokensWindow)} tokens")
                        val ctx = if (usage.contextPercent > 0) usage.contextPercent else usage.usagePercent
                        if (!hasBilling && ctx > 0) {
                            Text("$ctx% of this session’s window")
                        } else if (usage.contextPercent > 0) {
                            Text("${usage.contextPercent}% of this session’s window")
                        }
                    } else {
                        Text("Context window is not in the last snapshot.")
                    }
                    Text("This session", fontWeight = FontWeight.SemiBold, color = AccentCyan)
                    Text("Model: ${usage.model.ifBlank { "—" }}")
                    Text("Turns: ${formatCount(usage.turns)}")
                    Text("Tool calls: ${formatCount(usage.toolCalls)}")
                    Text("Compactions: ${formatCount(usage.compactions)}")
                    if (usage.tokensBeforeCompaction > 0) {
                        Text("Tokens before last compaction: ${formatCount(usage.tokensBeforeCompaction)}")
                    }
                    if (usage.durationSeconds > 0) {
                        Text("Duration: ${formatDuration(usage.durationSeconds)}")
                    }
                    Text(
                        "The bar is account credit from /usage — the same figure as the TUI Usage tab. Session tokens are listed here only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
        )
    }
}

private fun formatCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun StatusCard(
    sessionStatus: SessionStatus?,
    sending: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val phase = when {
        sending -> "sending"
        else -> sessionStatus?.phase ?: "unknown"
    }
    val detail = when {
        sending -> "Sending your message…"
        sessionStatus != null -> sessionStatus.detail.ifBlank { phaseLabel(phase) }
        else -> "Waiting for companion status…"
    }
    val accent = when (phase) {
        "working", "sending" -> AccentWarn
        "thinking" -> AccentUser
        "queued" -> AccentAssistant
        "idle" -> AccentCyan
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val meta = buildString {
        if (sessionStatus != null) {
            if (sessionStatus.model.isNotEmpty()) append(sessionStatus.model)
            append(if (isNotEmpty()) " · " else "")
            append(if (sessionStatus.tui) "TUI live" else "TUI off")
            if (sessionStatus.inbox > 0) append(" · inbox ${sessionStatus.inbox}")
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BgRaised,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "STATUS" else "STATUS · ${phaseLabel(phase)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                    Text(
                        if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Text(phaseLabel(phase), color = accent, fontWeight = FontWeight.SemiBold)
                    Text(detail, color = Ink, style = MaterialTheme.typography.bodySmall)
                    if (meta.isNotEmpty()) {
                        Text(
                            meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun phaseLabel(phase: String): String = when (phase) {
    "idle" -> "Idle"
    "thinking" -> "Thinking"
    "working" -> "Working"
    "queued" -> "Queued"
    "sending" -> "Sending"
    else -> "Unknown"
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == "user"
    val align = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    val accent = if (mine) AccentUser else AccentAssistant
    val color = if (mine) UserBubble else AssistantBubble
    val textColor = Ink
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
                )
                Column(
                    Modifier.weight(1f).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (mine) "You" else "Grok",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                    Text(
                        text = annotatedChat(message.text),
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun annotatedChat(raw: String) = buildAnnotatedString {
    for (run in parseInlineMarkdown(raw)) {
        val span = when (run.style) {
            ChatStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            ChatStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            ChatStyle.Code -> SpanStyle(fontFamily = FontFamily.Monospace, color = AccentCyan)
            ChatStyle.Path -> SpanStyle(color = AccentCyan)
            ChatStyle.Color -> SpanStyle(
                color = chatColor(run.colorName),
                fontWeight = FontWeight.SemiBold,
            )
            ChatStyle.Normal -> SpanStyle()
        }
        withStyle(span) { append(run.text) }
    }
}

private fun chatColor(name: String?): Color = when (name) {
    "red" -> AccentError
    "amber" -> AccentWarn
    "green" -> AccentSuccess
    "blue" -> AccentAssistant
    else -> Ink
}

package com.sidescreen.app

enum class DiagnosticRejectedUiState { IDLE, CONNECTING, CONNECTED }

/** Diagnostic-only owner: one complete connection coroutine at a time, including cleanup. */
class DiagnosticAttempts(private val stats: DiagnosticStats) {
    private var active: Long? = null
    private var current = 0L
    private var rejected = 0L
    private var connectedSession = 0L

    @Synchronized fun begin(startNs: Long, bindDecoder: (Long) -> Unit): Long? {
        if (active != null) {
            rejected++
            return null
        }
        val id = stats.beginSession(startNs)
        active = id
        current = id
        connectedSession = 0L
        try {
            // Keep creation and decoder rebind indivisible; always return this local ID.
            bindDecoder(id)
        } catch (error: Exception) {
            stats.endSession(id)
            active = null
            throw error
        }
        return id
    }

    @Synchronized fun finish(session: Long) {
        if (active != session) return
        stats.endSession(session)
        active = null
    }

    @Synchronized fun connectionStatus(session: Long, connected: Boolean) {
        if (active != session || session != current) return
        if (connected) {
            if (!stats.snapshot().ended) connectedSession = session
        } else {
            connectedSession = 0L
            stats.endSession(session)
        }
    }

    @Synchronized fun restoreRejectedUi(restore: (DiagnosticRejectedUiState) -> Unit) {
        // Decide when the queued UI action executes, not when an obsolete attempt was rejected.
        // Keep this short UI restoration atomic with begin/finish/status updates.
        val ended = stats.snapshot().ended
        val state = when {
            active == null || ended -> DiagnosticRejectedUiState.IDLE
            connectedSession == current -> DiagnosticRejectedUiState.CONNECTED
            else -> DiagnosticRejectedUiState.CONNECTING
        }
        restore(state)
    }

    @Synchronized fun isCurrent(session: Long): Boolean = session > 0 && session == current
    @Synchronized fun rejectedCount(): Long = rejected
}

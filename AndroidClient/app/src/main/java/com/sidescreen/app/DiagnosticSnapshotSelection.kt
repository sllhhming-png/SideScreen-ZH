package com.sidescreen.app

enum class DiagnosticSnapshotOrigin(val label: String) { PRE_SETTINGS("打开设置前快照"), CURRENT("当前/最后会话统计"), IN_DIALOG_REFRESH("弹窗内刷新，含界面干扰") }
data class DiagnosticSnapshotChoice(val snapshot: DiagnosticSnapshot, val origin: DiagnosticSnapshotOrigin)
object DiagnosticSnapshotSelection {
    fun choose(current: DiagnosticSnapshot, cached: DiagnosticSnapshot?, fromSettings: Boolean, refreshing: Boolean = false): DiagnosticSnapshotChoice {
        if(refreshing) return DiagnosticSnapshotChoice(current,DiagnosticSnapshotOrigin.IN_DIALOG_REFRESH)
        if(fromSettings && cached != null && cached.sessionId == current.sessionId && cached.ended == current.ended && cached.observationEpoch == current.observationEpoch) {
            return DiagnosticSnapshotChoice(cached,DiagnosticSnapshotOrigin.PRE_SETTINGS)
        }
        return DiagnosticSnapshotChoice(current,DiagnosticSnapshotOrigin.CURRENT)
    }
}

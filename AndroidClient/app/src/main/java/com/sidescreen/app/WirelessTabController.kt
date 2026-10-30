package com.sidescreen.app

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Five-state UI machine for the Wireless tab on Android.
 *
 *   ① first-time → ② scanning (QRScannerActivity) → ③ connected
 *                                         ↘ ④ token mismatch / re-pair
 *   ⓹ permission denied permanently
 */
class WirelessTabController(
    private val activity: Activity,
    private val views: Views,
    private val storage: PairedHostStorage,
    private val cameraPerm: CameraPermissionManager,
    private val onConnectRequested: (
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) -> Unit,
) {
    data class Views(
        val connecting: View,
        val firstTime: View,
        val connected: View,
        val pairedIdle: View,
        val repair: View,
        val permDenied: View,
        val scanButton: Button,
        val rescanButton: Button,
        val disconnectButton: Button,
        val forgetButton: Button,
        val reconnectButton: Button,
        val idleForgetButton: Button,
        val openSettingsButton: Button,
        val connectedMacName: TextView,
        val connectedMacIp: TextView,
        val connectingLabel: TextView,
        val connectingSubtitle: TextView,
        val idleMacName: TextView,
        val idleMacIp: TextView,
        val repairTitle: TextView,
        val repairMessage: TextView,
    )

    enum class State { FIRST_TIME, CONNECTING, CONNECTED, PAIRED_IDLE, REPAIR_NEEDED, PERM_DENIED }

    private var state: State = State.FIRST_TIME

    fun bind() {
        views.scanButton.setOnClickListener { triggerScan() }
        views.rescanButton.setOnClickListener { triggerScan() }
        views.openSettingsButton.setOnClickListener { cameraPerm.openAppSettings() }
        views.forgetButton.setOnClickListener {
            storage.clear()
            transition(State.FIRST_TIME)
        }
        views.idleForgetButton.setOnClickListener {
            storage.clear()
            transition(State.FIRST_TIME)
        }
        views.reconnectButton.setOnClickListener {
            val entry =
                storage.load() ?: run {
                    transition(State.FIRST_TIME)
                    return@setOnClickListener
                }
            showConnecting("正在重新连接 ${entry.macName}", "${entry.host}:${entry.port}")
            attemptAutoConnect(entry)
        }
    }

    /**
     * Called when the TCP stream goes down (user tapped Disconnect, network drop, etc).
     * Move the UI to a clean "paired but idle" state showing the Mac info + Reconnect button.
     */
    fun onStreamDisconnected() {
        android.util.Log.i(
            "WirelessTabController",
            "onStreamDisconnected called, current state=$state, storage entry exists=${storage.load() != null}",
        )
        val entry =
            storage.load() ?: run {
                transition(State.FIRST_TIME)
                return
            }
        views.idleMacName.text = entry.macName
        views.idleMacIp.text = "${entry.host}:${entry.port}"
        transition(State.PAIRED_IDLE)
    }

    /** Restore only the diagnostic request UI after its overlap gate rejected a second request. */
    fun onDiagnosticAttemptRejected(state: DiagnosticRejectedUiState) {
        if (!BuildConfig.DIAGNOSTICS) return
        when (state) {
            DiagnosticRejectedUiState.IDLE -> onStreamDisconnected()
            DiagnosticRejectedUiState.CONNECTED -> transition(State.CONNECTED)
            DiagnosticRejectedUiState.CONNECTING -> {
                views.connectingLabel.text = "正在等待当前诊断连接"
                views.connectingSubtitle.text = "重复请求已拒绝，请等待当前尝试结束"
                transition(State.CONNECTING)
            }
        }
    }

    private fun transition(next: State) {
        android.util.Log.i("WirelessTabController", "transition $state → $next")
        state = next
        views.connecting.visibility = if (next == State.CONNECTING) View.VISIBLE else View.GONE
        views.firstTime.visibility = if (next == State.FIRST_TIME) View.VISIBLE else View.GONE
        views.connected.visibility = if (next == State.CONNECTED) View.VISIBLE else View.GONE
        views.pairedIdle.visibility = if (next == State.PAIRED_IDLE) View.VISIBLE else View.GONE
        views.repair.visibility = if (next == State.REPAIR_NEEDED) View.VISIBLE else View.GONE
        views.permDenied.visibility = if (next == State.PERM_DENIED) View.VISIBLE else View.GONE
    }

    /**
     * Called when the Wireless tab becomes visible. Decides initial state based on
     * cached host + camera permission state.
     *
     * No auto-connect: even when a cached pairing exists, the user must press
     * the Reconnect button to actually start a connection. Auto-connect was
     * confusing because it could run silently while the user toggled tabs.
     */
    fun show() {
        when {
            cameraPerm.isPermanentlyDenied() -> transition(State.PERM_DENIED)
            storage.load() == null -> transition(State.FIRST_TIME)
            else -> {
                val entry = storage.load()!!
                views.idleMacName.text = entry.macName
                views.idleMacIp.text = "${entry.host}:${entry.port}"
                transition(State.PAIRED_IDLE)
            }
        }
    }

    fun onScanResult(url: String) {
        val parsed = PairingURL.parse(url) ?: return
        val deviceName = (android.os.Build.MODEL ?: "Android").take(64)
        storage.save(PairedHostStorage.Entry(parsed.host, parsed.port, parsed.token, parsed.macName))
        showConnecting("正在连接 ${parsed.macName}", "${parsed.host}:${parsed.port}")
        onConnectRequested(parsed.host, parsed.port, parsed.token, deviceName, parsed.macName)
    }

    fun onConnectError(error: StreamClient.WirelessConnectError) {
        val cached = storage.load()
        when (error) {
            is StreamClient.WirelessConnectError.NetworkUnreachable -> {
                views.repairTitle.text = "⚠ 无法连接 Mac"
                views.repairMessage.text =
                    if (cached != null) {
                        "${cached.macName}（${cached.host}:${cached.port}）没有响应。\n\n" +
                            "Mac 可能换了 Wi-Fi、端口变了，或者 Mac 端没有启动。请在 Mac 上打开 Side Screen，扫描新的二维码重新配对。"
                    } else {
                        "Mac 没有响应。请确认 Mac 和平板在同一个 Wi-Fi，且 Mac 端已启动，然后重新扫码。"
                    }
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.TokenRejected -> {
                views.repairTitle.text = "⚠ 需要重新配对"
                views.repairMessage.text =
                    if (cached != null) {
                        "${cached.macName} 的配对信息已重置。请扫描新的二维码重新配对。"
                    } else {
                        "Mac 的配对信息已重置。请扫描新的二维码重新配对。"
                    }
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.ProtocolError -> {
                views.repairTitle.text = "⚠ 连接错误"
                views.repairMessage.text = "无法和 Mac 完成安全连接。请重新扫描二维码。"
                transition(State.REPAIR_NEEDED)
            }
        }
    }

    private fun showConnecting(
        title: String,
        subtitle: String,
    ) {
        views.connectingLabel.text = title
        views.connectingSubtitle.text = subtitle
        transition(State.CONNECTING)
    }

    fun onConnectSuccess(
        macName: String,
        ip: String,
    ) {
        views.connectedMacName.text = macName
        views.connectedMacIp.text = ip
        transition(State.CONNECTED)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            // Re-evaluate; user just granted, jump straight into scanner.
            launchScanner()
        } else if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
        }
        // else: stay in current state; user can tap Scan again to re-prompt.
    }

    private fun triggerScan() {
        if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
            return
        }
        if (!cameraPerm.isGranted()) {
            cameraPerm.request(REQ_CAMERA)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        val intent = Intent(activity, QRScannerActivity::class.java)
        activity.startActivityForResult(intent, REQ_SCAN)
    }

    private fun attemptAutoConnect(entry: PairedHostStorage.Entry) {
        val deviceName = (android.os.Build.MODEL ?: "Android").take(64)
        onConnectRequested(entry.host, entry.port, entry.token, deviceName, entry.macName)
    }

    companion object {
        const val REQ_SCAN = 1001
        const val REQ_CAMERA = 1002
    }
}

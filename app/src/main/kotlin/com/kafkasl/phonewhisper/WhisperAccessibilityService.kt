package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val SAMPLE_RATE       = 16000
        private const val BTN_DP            = 44
        private const val PAD_DP            = 10
        private const val MARGIN_DP         = 8
        private const val TAP_THRESHOLD_DP  = 10
        private const val RING_DP           = 56

        private const val COLOR_IDLE        = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING   = 0xDDEF4444.toInt()
        private const val COLOR_CONNECTING  = 0xDDF57F17.toInt()  // amber
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING        = 0xFFE8EAED.toInt()

        const val LAN_WS = "ws://192.168.0.122:9877"
        const val TS_WS  = "ws://100.89.80.54:9877"

        private const val NOTIF_CHANNEL = "ptt_hidden"
        private const val NOTIF_ID      = 1
        private const val ACTION_SHOW   = "com.kafkasl.phonewhisper.SHOW_OVERLAY"
    }

    private enum class State { CONNECTING, IDLE, RECORDING }
    private var state = State.CONNECTING

    // ── HTTP clients ──────────────────────────────────────────────────────────

    private val okLan = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)   // handles WiFi power-saving wakeup
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val okTs = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ── Persistent connection state ───────────────────────────────────────────

    @Volatile private var persistentWs: WebSocket? = null
    @Volatile private var reconnecting  = false
    private var reconnectDelay          = 1000L   // ms, doubles on each failure

    // ── Audio ─────────────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    @Volatile private var streaming = false

    // ── UI ────────────────────────────────────────────────────────────────────

    private var overlayView          : FrameLayout?             = null
    private var button               : ImageView?               = null
    private var spinner              : ProgressBar?             = null
    private var feedbackView         : TextView?                = null
    private var dismissView          : TextView?                = null
    private var layoutParams         : WindowManager.LayoutParams? = null
    private var feedbackLayoutParams : WindowManager.LayoutParams? = null
    private var dismissParams        : WindowManager.LayoutParams? = null
    private val handler              = Handler(Looper.getMainLooper())

    private val dp      get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    // ── Broadcast receiver (restore from notification) ────────────────────────

    private val showReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SHOW) {
                cancelNotification()
                showOverlay()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun show() {
        if (overlayView == null) showOverlay()
    }

    override fun onServiceConnected() {
        instance = this
        createNotifChannel()
        registerReceiver(showReceiver, IntentFilter(ACTION_SHOW))
        showOverlay()
        connectPersistent()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        if (streaming) {
            streaming = false
            persistentWs?.send("CANCEL")
        }
        persistentWs?.close(1000, "service destroyed")
        persistentWs = null
        removeOverlay()
        cancelNotification()
        try { unregisterReceiver(showReceiver) } catch (_: Exception) {}
        okLan.dispatcher.executorService.shutdown()
        okTs.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    // ── Persistent WebSocket ──────────────────────────────────────────────────

    private fun connectPersistent() {
        if (reconnecting || persistentWs != null) return
        reconnecting = true
        handler.post { setColor(COLOR_CONNECTING); spinner?.visibility = View.VISIBLE }

        var tsAttempted = false
        fun tryTs() {
            if (tsAttempted) return
            tsAttempted = true
            okTs.newWebSocket(Request.Builder().url(TS_WS).build(), makePersistentListener())
        }

        okLan.newWebSocket(Request.Builder().url(LAN_WS).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                makePersistentListener().onOpen(ws, response)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                tryTs()
            }
        })
    }

    private fun makePersistentListener() = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            persistentWs  = ws
            reconnecting  = false
            reconnectDelay = 1000L
            handler.post {
                state = State.IDLE
                spinner?.visibility = View.GONE
                setColor(COLOR_IDLE)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (persistentWs == ws || persistentWs == null) {
                onConnectionLost()
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (persistentWs == ws) {
                onConnectionLost()
            }
        }
    }

    private fun onConnectionLost() {
        val wasRecording = state == State.RECORDING
        persistentWs = null
        reconnecting = false
        if (wasRecording) streaming = false
        handler.post {
            state = State.CONNECTING
            spinner?.visibility = View.VISIBLE
            setColor(COLOR_CONNECTING)
            stopPulse()
            if (wasRecording) showFeedback("Connection lost")
        }
        handler.postDelayed({
            reconnectDelay = minOf(reconnectDelay * 2, 30_000L)
            connectPersistent()
        }, reconnectDelay)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return
        val wm      = getSystemService(WINDOW_SERVICE) as WindowManager
        val btnSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad     = (PAD_DP * dp).toInt()
        val margin  = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = if (state == State.CONNECTING) View.VISIBLE else View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(when (state) {
                State.CONNECTING -> COLOR_CONNECTING
                State.RECORDING  -> COLOR_RECORDING
                State.IDLE       -> COLOR_IDLE
            })
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img,  FrameLayout.LayoutParams(btnSize,  btnSize,  Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX;  touchY = ev.rawY
                    dragging = false
                    if (state == State.IDLE) beginRecording()
                    else if (state == State.CONNECTING) showFeedback("Connecting…")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (!dragging && moved > TAP_THRESHOLD_DP * dp) {
                        dragging = true
                        cancelRecording()
                        showDismissTarget()
                    }
                    if (dragging) {
                        params.x = startX + (ev.rawX - touchX).toInt()
                        params.y = startY + (ev.rawY - touchY).toInt()
                        wm.updateViewLayout(v, params)
                        updateDismissHover(params.x + ringSize / 2, params.y + ringSize / 2)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        if (isOverDismiss(params.x + ringSize / 2, params.y + ringSize / 2)) {
                            hideDismissTarget()
                            removeOverlay()
                            showHiddenNotification()
                        } else {
                            hideDismissTarget()
                            params.x = if (params.x + ringSize / 2 > screenW / 2)
                                screenW - ringSize - margin else margin
                            wm.updateViewLayout(v, params)
                        }
                    } else {
                        if (state == State.RECORDING) stopRecording()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        hideDismissTarget()
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                    } else {
                        if (state == State.RECORDING) cancelRecording()
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView          = overlay
        button               = img
        spinner              = ring
        feedbackView         = feedback
        layoutParams         = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let  { try { wm.removeView(it) } catch (_: Exception) {} }
        feedbackView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dismissView?.let  { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayView = null; feedbackView = null; button = null; spinner = null
        dismissView = null; dismissParams = null
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun beginRecording() {
        val ws = persistentWs ?: run { showFeedback("Connecting…"); return }
        if (state != State.IDLE) return
        state = State.RECORDING
        setColor(COLOR_RECORDING)
        startPulse()
        startAudioStream(ws)
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        streaming = false
        persistentWs?.send("STOP")
        handler.post { resetIdle() }
    }

    private fun cancelRecording() {
        if (state != State.RECORDING) return
        streaming = false
        persistentWs?.send("CANCEL")
        handler.post { resetIdle() }
    }

    private fun startAudioStream(ws: WebSocket) {
        streaming = true
        thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4
                )
            } catch (_: SecurityException) {
                handler.post { toast("Audio permission denied") }
                return@thread
            }
            audioRecord = rec
            rec.startRecording()
            val buf = ByteArray(4096)
            while (streaming) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) ws.send(buf.toByteString(0, n))
            }
            rec.stop()
            rec.release()
            audioRecord = null
        }
    }

    // ── Dismiss target ────────────────────────────────────────────────────────

    private val dismissSize get() = (72 * dp).toInt()

    private fun showDismissTarget() {
        if (dismissView != null) return
        val wm   = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dismissSize
        val view = TextView(this).apply {
            text = "✕"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); background = circle(0xCC333333.toInt()); alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW / 2 - size / 2
            y = (32 * dp).toInt()
        }
        wm.addView(view, lp)
        dismissView = view; dismissParams = lp
        view.animate().alpha(1f).setDuration(180).start()
    }

    private fun hideDismissTarget() {
        val view = dismissView ?: return
        val wm   = getSystemService(WINDOW_SERVICE) as WindowManager
        view.animate().alpha(0f).setDuration(150).withEndAction {
            try { wm.removeView(view) } catch (_: Exception) {}
        }.start()
        dismissView = null; dismissParams = null
    }

    private fun isOverDismiss(btnCx: Int, btnCy: Int): Boolean {
        val lp   = dismissParams ?: return false
        val size = dismissSize
        val dx   = btnCx - (lp.x + size / 2)
        val dy   = btnCy - (lp.y + size / 2)
        return Math.hypot(dx.toDouble(), dy.toDouble()) < size * 0.85
    }

    private fun updateDismissHover(btnCx: Int, btnCy: Int) {
        val view     = dismissView ?: return
        val hovering = isOverDismiss(btnCx, btnCy)
        handler.post {
            view.background = circle(if (hovering) 0xCCEF4444.toInt() else 0xCC333333.toInt())
            view.scaleX = if (hovering) 1.2f else 1f
            view.scaleY = if (hovering) 1.2f else 1f
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun resetIdle() {
        state = State.IDLE
        setColor(COLOR_IDLE)
        stopPulse()
    }

    private fun setColor(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    private fun positionFeedback(fp: WindowManager.LayoutParams, bp: WindowManager.LayoutParams) {
        val margin = (MARGIN_DP * dp).toInt()
        fp.x = maxOf(margin, bp.x - (64 * dp).toInt())
        fp.y = maxOf(margin, bp.y - margin)
    }

    private fun showFeedback(text: String) {
        handler.post {
            val view = feedbackView ?: return@post
            val bp   = layoutParams ?: return@post
            val fp   = feedbackLayoutParams ?: return@post
            val wm   = getSystemService(WINDOW_SERVICE) as WindowManager
            view.text = text
            positionFeedback(fp, bp)
            wm.updateViewLayout(view, fp)
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed({
                view.animate().alpha(0f).setDuration(180).withEndAction {
                    view.visibility = View.GONE
                }.start()
            }, 2000)
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 16 * dp; setColor(color)
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ── Notification (hidden state) ───────────────────────────────────────────

    private fun createNotifChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(NOTIF_CHANNEL, "PTT button", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun showHiddenNotification() {
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("PTT mic button hidden")
            .setContentText("Tap to bring it back")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif)
    }

    private fun cancelNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }
}

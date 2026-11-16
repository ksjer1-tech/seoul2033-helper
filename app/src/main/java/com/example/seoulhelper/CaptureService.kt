package com.example.seoulhelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.seoulhelper.STOP"
        private const val CHANNEL = "helper"
        private const val NOTIF_ID = 1
        private const val AUTO_INTERVAL_MS = 1500L
        private const val SIG_W = 54
        private const val SIG_H = 96
    }

    private val main = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private lateinit var worker: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var heldImage: Image? = null

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: TextView? = null
    private var panelFull = ""
    private var panelExpanded = true

    @Volatile private var overlayRects: List<Rect> = emptyList()
    @Volatile private var auto = false
    @Volatile private var busy = false
    @Volatile private var lastSentSig: IntArray? = null
    private var prevCheckSig: IntArray? = null
    private val history = ArrayDeque<String>()

    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { dataExtra(it) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val t = HandlerThread("capture").also { it.start() }
        workerThread = t
        worker = Handler(t.looper)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        startCapture(proj)
        setupOverlay()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun dataExtra(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra<Intent>(EXTRA_DATA)

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "서울 2033 도우미", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("서울 2033 도우미 실행 중")
            .setContentText("🎯 탭: 분석 · 길게: 자동 모드")
            .addAction(Notification.Action.Builder(null as Icon?, "중지", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- 화면 캡처 ----------

    @Suppress("DEPRECATION")
    private fun startCapture(proj: MediaProjection) {
        val metrics = DisplayMetrics()
        wm!!.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            }
            if (img != null) {
                synchronized(frameLock) {
                    heldImage?.close()
                    heldImage = img
                }
            }
        }, worker)
        reader = r
        virtualDisplay = proj.createVirtualDisplay(
            "seoul-helper", screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun grabBitmap(): Bitmap? = synchronized(frameLock) {
        val img = heldImage ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            val buf = plane.buffer
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 화면 위 버튼 / 말풍선 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setupOverlay() {
        val w = wm ?: return
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val p = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(220, 20, 20, 20))
            }
        }
        val pp = WindowManager.LayoutParams(
            screenW - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(48)
        }

        val b = TextView(this).apply {
            text = "🎯"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val bp = WindowManager.LayoutParams(
            dp(56), dp(56), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - dp(72)
            y = screenH / 2
        }

        w.addView(p, pp)
        w.addView(b, bp)
        panel = p
        bubble = b
        refreshBubble()

        makeDraggable(b, bp, onTap = { analyze(autoTriggered = false) }, onLong = { toggleAuto() })
        makeDraggable(p, pp, onTap = {
            panelExpanded = !panelExpanded
            applyPanel()
        }, onLong = null)

        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayRects() }
        p.addOnLayoutChangeListener(relayout)
        b.addOnLayoutChangeListener(relayout)

        showPanel("🎯 탭: 지금 화면 분석\n🎯 길게: 자동 모드 켜기/끄기\n이 창 탭: 접기 · 드래그: 옮기기")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(
        v: View,
        lp: WindowManager.LayoutParams,
        onTap: () -> Unit,
        onLong: (() -> Unit)?
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downTime = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    downTime = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(10) || abs(dy) > dp(10))) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm?.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        main.postDelayed({ updateOverlayRects() }, 100)
                    } else if (onLong != null && SystemClock.uptimeMillis() - downTime > 600) {
                        onLong()
                    } else {
                        onTap()
                    }
                }
            }
            true
        }
    }

    private fun updateOverlayRects() {
        val list = mutableListOf<Rect>()
        for (v in listOfNotNull(bubble, panel)) {
            if (v.width == 0) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val m = dp(8)
            list.add(Rect(loc[0] - m, loc[1] - m, loc[0] + v.width + m, loc[1] + v.height + m))
        }
        overlayRects = list
    }

    private fun refreshBubble() {
        val b = bubble ?: return
        b.text = if (busy) "⏳" else "🎯"
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (auto) Color.argb(235, 30, 140, 70) else Color.argb(220, 60, 60, 60))
        }
    }

    private fun showPanel(text: String) {
        panelFull = text
        applyPanel()
    }

    private fun applyPanel() {
        val p = panel ?: return
        p.text = if (panelExpanded) panelFull
        else (panelFull.lineSequence().firstOrNull().orEmpty() + "  ▾")
    }

    // ---------- 자동 모드 ----------

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            try {
                checkForChange()
            } catch (_: Exception) {
            }
            worker.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    private fun toggleAuto() {
        auto = !auto
        refreshBubble()
        worker.removeCallbacks(autoTick)
        if (auto) {
            prevCheckSig = null
            showPanel("🔄 자동 모드 켜짐\n화면이 바뀌면 알아서 분석해요")
            worker.post(autoTick)
        } else {
            showPanel("⏸ 자동 모드 꺼짐\n🎯를 탭하면 분석해요")
        }
    }

    /** 워커 스레드에서 실행. 화면이 바뀐 뒤 멈추면 분석을 요청한다. */
    private fun checkForChange() {
        if (busy) return
        val bmp = grabBitmap() ?: return
        val sig = signature(bmp, overlayRects)
        val prev = prevCheckSig
        prevCheckSig = sig
        if (prev == null || !similar(prev, sig)) return // 아직 바뀌는 중
        val last = lastSentSig
        if (last != null && similar(last, sig)) return // 이미 분석한 화면
        main.post { if (auto) analyze(autoTriggered = true) }
    }

    private fun signature(bmp: Bitmap, masks: List<Rect>): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, SIG_W, SIG_H, true)
        val px = IntArray(SIG_W * SIG_H)
        small.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        val sx = bmp.width.toFloat() / SIG_W
        val sy = bmp.height.toFloat() / SIG_H
        return IntArray(px.size) { i ->
            val cx = (((i % SIG_W) + 0.5f) * sx).toInt()
            val cy = (((i / SIG_W) + 0.5f) * sy).toInt()
            if (masks.any { it.contains(cx, cy) }) {
                -1
            } else {
                val c = px[i]
                (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            }
        }
    }

    private fun similar(a: IntArray, b: IntArray): Boolean {
        var changed = 0
        var counted = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            counted++
            if (abs(a[i] - b[i]) > 12) changed++
        }
        return counted == 0 || changed <= counted * 0.015
    }

    // ---------- 분석 ----------

    private fun analyze(autoTriggered: Boolean) {
        if (busy) return
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val model = prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL) ?: Prefs.DEFAULT_MODEL
        val useGemini = model == Prefs.GEMINI
        val key = prefs.getString(if (useGemini) Prefs.GEMINI_KEY else Prefs.KEY, "").orEmpty()
        val geminiModel = prefs.getString(Prefs.GEMINI_MODEL, Prefs.DEFAULT_GEMINI_MODEL)
            ?: Prefs.DEFAULT_GEMINI_MODEL
        if (key.isBlank()) {
            showPanel("API 키가 없어요. 앱에서 입력해 주세요.")
            return
        }
        val userText = buildUserText(
            prefs.getString(Prefs.GOAL, "").orEmpty(),
            prefs.getString(Prefs.MEMO, "").orEmpty()
        )

        busy = true
        refreshBubble()
        // 캡처에 버튼/말풍선이 찍히지 않도록 잠깐 숨긴다
        val views = listOfNotNull(bubble, panel)
        views.forEach { it.visibility = View.INVISIBLE }

        main.postDelayed({
            worker.post {
                val bmp = grabBitmap()
                main.post { views.forEach { it.visibility = View.VISIBLE } }
                if (bmp == null) {
                    finishWith("화면을 아직 못 받았어요. 잠시 후 다시 탭해 주세요.")
                    return@post
                }
                lastSentSig = signature(bmp, emptyList())
                main.post { showPanel("⏳ 분석 중…") }
                try {
                    val img = toJpegBase64(bmp)
                    val reply = (
                        if (useGemini) Gemini.ask(key, geminiModel, Prompt.SYSTEM, userText, img)
                        else Claude.ask(key, model, Prompt.SYSTEM, userText, img)
                        ).trim()
                    if (reply.startsWith("NONE")) {
                        finishWith(
                            if (autoTriggered) "🔄 자동 모드: 선택지가 나오면 알려줄게요"
                            else "이 화면엔 선택지가 안 보여요"
                        )
                    } else {
                        synchronized(history) {
                            history.addLast(reply.lineSequence().firstOrNull().orEmpty())
                            while (history.size > 3) history.removeFirst()
                        }
                        finishWith(reply)
                    }
                } catch (e: Exception) {
                    finishWith("⚠️ 오류: ${e.message}")
                }
            }
        }, 350)
    }

    private fun finishWith(text: String) {
        main.post {
            busy = false
            refreshBubble()
            panelExpanded = true
            showPanel(text)
        }
    }

    private fun buildUserText(goal: String, memo: String): String {
        val recent = synchronized(history) { history.joinToString(" / ") }
        return buildString {
            if (goal.isNotBlank()) appendLine("플레이 목표: $goal")
            if (memo.isNotBlank()) appendLine("내 상태 메모: $memo")
            if (recent.isNotBlank()) appendLine("직전 추천들: $recent")
            append("이 화면을 보고 골라줘.")
        }
    }

    private fun toJpegBase64(bmp: Bitmap): String {
        val targetW = 768
        val scaled = if (bmp.width > targetW) {
            Bitmap.createScaledBitmap(bmp, targetW, bmp.height * targetW / bmp.width, true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun onDestroy() {
        auto = false
        if (::worker.isInitialized) worker.removeCallbacks(autoTick)
        listOfNotNull(bubble, panel).forEach { v -> runCatching { wm?.removeView(v) } }
        bubble = null
        panel = null
        virtualDisplay?.release()
        virtualDisplay = null
        synchronized(frameLock) {
            heldImage?.close()
            heldImage = null
        }
        reader?.close()
        reader = null
        val p = projection
        projection = null
        runCatching { p?.stop() }
        workerThread?.quitSafely()
        super.onDestroy()
    }
}

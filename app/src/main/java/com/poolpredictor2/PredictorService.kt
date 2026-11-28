package com.poolpredictor2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat as GfxPixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import com.poolpredictor2.vision.CueBallDetector
import com.poolpredictor2.vision.CueBallState
import com.poolpredictor2.vision.StickDetector
import com.poolpredictor2.vision.StickState
import com.poolpredictor2.vision.TableDetector
import com.poolpredictor2.vision.TableRegion
import android.util.DisplayMetrics
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.poolpredictor2.geometry.ShotGeometry
import kotlin.math.PI

/**
 * Android Overlay Service untuk 8ball-android-overlay-v2.
 *
 * LOCKED SPECIFICATIONS:
 * - Khusus Android (WindowManager overlay), terpisah dari Project 1 (PC).
 * - Hanya menggambar 2 garis:
 *   1. Cue Ball -> Ghost Ball (Putih)
 *   2. Target Ball -> Pocket (Warna sesuai bola 1-15, termasuk efek strip untuk 9-15)
 * - No Auto Play (tidak ada injeksi sentuhan otomatis).
 * - Panjang garis dinamis mengikuti Cue Power.
 * - Garis ter-clip ketat di dalam area meja.
 * - Menggunakan MOCK DATA sementara (offset game di Config.kt masih kosong).
 */
class PredictorService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var themeManager: ThemeManager
    private lateinit var overlayCanvasView: TrajectoryOverlayView
    private lateinit var controlPanelView: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // ---- Screen capture (MediaProjection) ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureStatusText: TextView? = null
    private var tableStatusText: TextView? = null
    private var frameCount = 0
    private var lastFpsTime = 0L
    @Volatile private var lastFps = 0f

    // Ukuran frame capture saat ini (px) dan ukuran layar nyata (px), keduanya sesuai rotasi aktif
    @Volatile private var capW = 0
    @Volatile private var capH = 0
    @Volatile private var realW = 0
    @Volatile private var realH = 0
    private var lastRotation = -1

    // ---- Deteksi meja ----
    @Volatile private var tableRegion: TableRegion? = null
    @Volatile private var resetDetectionRequested = false
    private var pendingRegion: TableRegion? = null   // hanya diakses dari thread capture
    private var missCount = 0                         // hanya diakses dari thread capture
    private var lastDetectTime = 0L                   // hanya diakses dari thread capture

    // ---- Deteksi bola putih ----
    @Volatile private var detectedCue: CueBallState? = null
    private var cueMissCount = 0          // hanya diakses dari thread capture
    private var lastCueTime = 0L          // hanya diakses dari thread capture
    private var cueStatusText: TextView? = null

    // ---- Stik / sudut bidik asli ----
    @Volatile private var detectedAim: StickState? = null
    private var stickMissCount = 0        // hanya diakses dari thread capture
    private var stickStatusText: TextView? = null

    // ---- Render on-demand ----
    @Volatile private var overlayDirty = true
    private var lastDrawTime = 0L

    private var mockAimAngleDeg = -18.5f
    private var mockCuePower = 0.72f

    private val renderLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val now = SystemClock.uptimeMillis()
            if (overlayDirty || now - lastDrawTime >= Config.OVERLAY_HEARTBEAT_MS) {
                overlayDirty = false
                lastDrawTime = now
                overlayCanvasView.invalidate()
            }
            mainHandler.postDelayed(this, Config.FRAME_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        themeManager = ThemeManager(this)

        setupTrajectoryOverlay()
        setupFloatingControlPanel()

        isRunning = true
        mainHandler.post(renderLoop)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || code == 0) {
            setCaptureStatus("Capture: tidak ada izin")
            return START_NOT_STICKY
        }
        // Wajib dipanggil sebelum getMediaProjection()
        startAsForeground()
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "pp2_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "PoolPredictor2", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("PoolPredictor2")
            .setContentText("Overlay & tangkap layar aktif")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun readDisplayInfo(): DisplayMetrics {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay.rotation

    private fun scaledEven(v: Int): Int {
        val s = (v * Config.CAPTURE_SCALE).toInt()
        return ((s / 2) * 2).coerceAtLeast(2)
    }

    private fun createReader(w: Int, h: Int, handler: Handler): ImageReader {
        val reader = ImageReader.newInstance(w, h, GfxPixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)
        return reader
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        stopCapture()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            projection = mp

            // Android 14+ wajib mendaftarkan callback sebelum membuat VirtualDisplay
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { setCaptureStatus("Capture: dihentikan sistem") }
                }
            }, mainHandler)

            // Ukuran diambil dari layar NYATA saat ini (bukan dari orientasi Launcher)
            val dm = readDisplayInfo()
            realW = dm.widthPixels
            realH = dm.heightPixels
            lastRotation = currentRotation()
            capW = scaledEven(realW)
            capH = scaledEven(realH)

            val thread = HandlerThread("pp2-capture").also { it.start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            lastFpsTime = System.currentTimeMillis()
            frameCount = 0

            val reader = createReader(capW, capH, handler)
            imageReader = reader

            virtualDisplay = mp.createVirtualDisplay(
                "pp2-capture", capW, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )

            // Pantau perubahan rotasi/ukuran layar supaya frame selalu mengikuti orientasi aktif
            val dispMgr = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager = dispMgr
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) onDisplayMaybeChanged()
                }
            }
            displayListener = listener
            dispMgr.registerDisplayListener(listener, mainHandler)

            refreshStatus()
        } catch (e: Exception) {
            stopCapture()
            setCaptureStatus("Capture gagal: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Dipanggil di main thread saat layar berubah. Memeriksa ukuran DAN rotasi nyata:
     * rotasi 180 derajat tidak mengubah lebar/tinggi tetapi membalik isi frame,
     * jadi deteksi tetap harus diulang.
     */
    private fun onDisplayMaybeChanged() {
        val vd = virtualDisplay ?: return
        val handler = captureHandler ?: return
        val dm = readDisplayInfo()
        val rot = currentRotation()
        val newW = scaledEven(dm.widthPixels)
        val newH = scaledEven(dm.heightPixels)

        realW = dm.widthPixels
        realH = dm.heightPixels

        val rotationChanged = rot != lastRotation
        val sizeChanged = newW != capW || newH != capH
        lastRotation = rot
        if (!rotationChanged && !sizeChanged) {
            overlayDirty = true
            return
        }

        if (sizeChanged) {
            val newReader = createReader(newW, newH, handler)
            val oldReader = imageReader
            try {
                vd.resize(newW, newH, dm.densityDpi)
                vd.surface = newReader.surface
            } catch (e: Exception) {
                newReader.close()
                setCaptureStatus("Capture gagal resize: ${e.javaClass.simpleName}")
                return
            }
            imageReader = newReader
            capW = newW
            capH = newH
            oldReader?.close()
        }

        // Posisi meja lama tidak berlaku lagi untuk orientasi/rotasi baru
        tableRegion = null
        detectedCue = null
        detectedAim = null
        resetDetectionRequested = true
        overlayDirty = true
        refreshStatus()
    }

    /** Dipanggil di thread capture untuk setiap frame. */
    private fun onFrame(reader: ImageReader) {
        try {
            val img = reader.acquireLatestImage() ?: return
            try {
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    lastFps = frameCount * 1000f / (now - lastFpsTime)
                    frameCount = 0
                    lastFpsTime = now
                    mainHandler.post { refreshStatus() }
                }

                if (resetDetectionRequested) {
                    resetDetectionRequested = false
                    pendingRegion = null
                    missCount = 0
                    lastDetectTime = 0L
                    cueMissCount = 0
                    lastCueTime = 0L
                    stickMissCount = 0
                }

                // Frame sisa dari ukuran lama (sesaat setelah resize) dilewati.
                // Ukuran diambil dari frame itu sendiri, bukan diasumsikan.
                if (img.width != capW || img.height != capH) return

                val interval = if (tableRegion == null) {
                    Config.TABLE_DETECT_SEARCH_INTERVAL_MS
                } else {
                    Config.TABLE_DETECT_LOCKED_INTERVAL_MS
                }
                val uptime = SystemClock.uptimeMillis()
                val tableDue = uptime - lastDetectTime >= interval
                val cueDue = tableRegion != null &&
                    uptime - lastCueTime >= Config.CUE_DETECT_INTERVAL_MS
                if (!tableDue && !cueDue) return

                val plane = img.planes[0]
                if (tableDue) {
                    lastDetectTime = uptime
                    val region = TableDetector.detect(
                        plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride
                    )
                    handleDetection(region)
                }

                // Bola putih dicari hanya di dalam meja yang sudah terkunci
                val table = tableRegion
                if (cueDue && table != null) {
                    lastCueTime = uptime
                    val cue = CueBallDetector.detect(
                        plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride,
                        (table.left * img.width).toInt(),
                        (table.top * img.height).toInt(),
                        (table.right * img.width).toInt(),
                        (table.bottom * img.height).toInt()
                    )
                    handleCue(cue)

                    // Stik dicari hanya jika bola putih ditemukan (garis stik melewati bola putih)
                    if (cue != null) {
                        val stick = StickDetector.detect(
                            plane.buffer, img.width, img.height,
                            plane.rowStride, plane.pixelStride,
                            cue.x * img.width,
                            cue.y * img.height,
                            (table.left * img.width).toInt(),
                            (table.top * img.height).toInt(),
                            (table.right * img.width).toInt(),
                            (table.bottom * img.height).toInt()
                        )
                        handleStick(stick)
                    } else {
                        handleStick(null)
                    }
                }
            } finally {
                img.close()
            }
        } catch (e: Exception) {
            // Reader bisa sudah ditutup saat resize; abaikan
        }
    }

    private fun closeTo(a: TableRegion, b: TableRegion): Boolean {
        val tol = Config.TABLE_LOCK_TOLERANCE
        return kotlin.math.abs(a.left - b.left) <= tol &&
            kotlin.math.abs(a.top - b.top) <= tol &&
            kotlin.math.abs(a.right - b.right) <= tol &&
            kotlin.math.abs(a.bottom - b.bottom) <= tol
    }

    /** Thread capture. Meja baru dikunci setelah dua deteksi berturut-turut yang sama. */
    private fun handleDetection(region: TableRegion?) {
        if (region == null) {
            pendingRegion = null
            missCount++
            if (missCount >= Config.TABLE_MISS_LIMIT && tableRegion != null) {
                tableRegion = null
                overlayDirty = true
                mainHandler.post { refreshStatus() }
            }
            return
        }
        missCount = 0

        val current = tableRegion
        if (current != null && closeTo(current, region)) {
            pendingRegion = null
            return
        }

        val pending = pendingRegion
        if (pending != null && closeTo(pending, region)) {
            tableRegion = region
            pendingRegion = null
            overlayDirty = true
            mainHandler.post { refreshStatus() }
        } else {
            pendingRegion = region
        }
    }

    /** Thread capture. Bola putih mengikuti deteksi terbaru; dilepas setelah beberapa kali tidak ditemukan. */
    private fun handleCue(cue: CueBallState?) {
        if (cue != null) {
            cueMissCount = 0
            detectedCue = cue
            overlayDirty = true
            mainHandler.post { refreshStatus() }
        } else {
            cueMissCount++
            if (cueMissCount >= Config.CUE_MISS_LIMIT && detectedCue != null) {
                detectedCue = null
                overlayDirty = true
                mainHandler.post { refreshStatus() }
            }
        }
    }

    /** Thread capture. Sudut dihaluskan sedikit; kembali ke slider manual jika stik tidak terlihat. */
    private fun handleStick(stick: StickState?) {
        if (stick != null) {
            stickMissCount = 0
            val prev = detectedAim
            val angle = if (prev != null) {
                var delta = stick.aimAngleDeg - prev.aimAngleDeg
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                if (kotlin.math.abs(delta) < 15f) {
                    var a = prev.aimAngleDeg + 0.6f * delta
                    while (a > 180f) a -= 360f
                    while (a <= -180f) a += 360f
                    a
                } else {
                    stick.aimAngleDeg
                }
            } else {
                stick.aimAngleDeg
            }
            detectedAim = StickState(angle, stick.votes)
            overlayDirty = true
            mainHandler.post { refreshStatus() }
        } else {
            stickMissCount++
            if (stickMissCount >= Config.STICK_MISS_LIMIT && detectedAim != null) {
                detectedAim = null
                overlayDirty = true
                mainHandler.post { refreshStatus() }
            }
        }
    }

    private fun stopCapture() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        displayManager = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        captureThread?.quitSafely(); captureThread = null
        captureHandler = null
        tableRegion = null
        detectedCue = null
        detectedAim = null
    }

    private fun setCaptureStatus(text: String) {
        captureStatusText?.text = text
    }

    /** Main thread. Menampilkan ukuran frame, rotasi nyata, fps, dan status meja. */
    private fun refreshStatus() {
        if (virtualDisplay != null) {
            captureStatusText?.text =
                "Capture: ${capW}x${capH} | rot ${lastRotation * 90}° | %.0f fps".format(lastFps)
        }
        val cue = detectedCue
        cueStatusText?.text = if (virtualDisplay == null || tableRegion == null) {
            "Bola putih: -"
        } else if (cue == null) {
            "Bola putih: mencari..."
        } else {
            "Bola putih: (${(cue.x * realW).toInt()}, ${(cue.y * realH).toInt()})"
        }
        val aim = detectedAim
        stickStatusText?.text = if (virtualDisplay == null || cue == null) {
            "Sudut: manual"
        } else if (aim == null) {
            "Sudut: manual (stik tak terlihat)"
        } else {
            "Sudut: %.1f° (stik)".format(aim.aimAngleDeg)
        }
        val t = tableRegion
        tableStatusText?.text = if (virtualDisplay == null) {
            "Meja: -"
        } else if (t == null) {
            "Meja: mencari..."
        } else {
            val w = ((t.right - t.left) * realW).toInt()
            val h = ((t.bottom - t.top) * realH).toInt()
            "Meja: ${w}x${h} px"
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(renderLoop)
        stopCapture()
        if (::overlayCanvasView.isInitialized) {
            try { windowManager.removeView(overlayCanvasView) } catch (_: Exception) {}
        }
        if (::controlPanelView.isInitialized) {
            try { windowManager.removeView(controlPanelView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun getOverlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun setupTrajectoryOverlay() {
        overlayCanvasView = TrajectoryOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android 12+ memblokir sentuhan ke aplikasi di bawah overlay yang
            // alpha jendelanya > 0.8. Harus <= 0.8 supaya sentuhan tembus.
            alpha = 0.8f
        }
        windowManager.addView(overlayCanvasView, params)
    }

    private fun setupFloatingControlPanel() {
        val theme = themeManager.currentTheme

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.panel)
            setPadding(28, 20, 28, 20)
        }

        val titleText = TextView(this).apply {
            text = "POOL PREDICTOR V2 [MOCK]"
            setTextColor(theme.highlight)
            textSize = 12f
        }

        val infoText = TextView(this).apply {
            text = "Angle: ${mockAimAngleDeg}° | Power: ${(mockCuePower * 100).toInt()}%"
            setTextColor(theme.textPrimary)
            textSize = 11f
        }

        val angleSlider = SeekBar(this).apply {
            max = 360
            progress = (mockAimAngleDeg + 180f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    mockAimAngleDeg = progress - 180f
                    overlayDirty = true
                    infoText.text = "Angle: ${"%.1f".format(mockAimAngleDeg)}° | Power: ${(mockCuePower * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val powerSlider = SeekBar(this).apply {
            max = 100
            progress = (mockCuePower * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    mockCuePower = (progress.coerceAtLeast(5)) / 100f
                    overlayDirty = true
                    infoText.text = "Angle: ${"%.1f".format(mockAimAngleDeg)}° | Power: ${(mockCuePower * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val captureText = TextView(this).apply {
            text = "Capture: belum aktif"
            setTextColor(theme.textPrimary)
            textSize = 11f
        }
        captureStatusText = captureText

        val tableText = TextView(this).apply {
            text = "Meja: -"
            setTextColor(theme.textPrimary)
            textSize = 11f
        }
        tableStatusText = tableText

        val cueText = TextView(this).apply {
            text = "Bola putih: -"
            setTextColor(theme.textPrimary)
            textSize = 11f
        }
        cueStatusText = cueText

        val stickText = TextView(this).apply {
            text = "Sudut: manual"
            setTextColor(theme.textPrimary)
            textSize = 11f
        }
        stickStatusText = stickText

        container.addView(titleText)
        container.addView(infoText)
        container.addView(captureText)
        container.addView(tableText)
        container.addView(cueText)
        container.addView(stickText)
        container.addView(angleSlider)
        container.addView(powerSlider)

        val panelParams = WindowManager.LayoutParams(
            480,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 40
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x
                    initialY = panelParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    panelParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, panelParams)
                    true
                }
                else -> false
            }
        }

        controlPanelView = container
        windowManager.addView(controlPanelView, panelParams)
    }

    private inner class TrajectoryOverlayView(context: Context) : FrameLayout(context) {

        private val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val locOnScreen = IntArray(2)

        private val cueMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(0, 230, 118)
        }

        private val pocketMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.YELLOW
        }

        private val cueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }

        private val targetSolidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }

        private val targetStripeOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.BUTT
            color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
        }

        private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }

        private val mockBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val theme = themeManager.currentTheme

            // Batas meja: dari hasil deteksi kain (koordinat relatif frame -> layar -> view).
            // Hanya dipakai jika orientasi deteksi sama dengan orientasi layar saat ini.
            val region = tableRegion
            val rw = realW
            val rh = realH
            val detected = region != null && rw > 0 && rh > 0 &&
                region.isLandscape == (rw >= rh)

            val tableBounds = if (detected && region != null) {
                getLocationOnScreen(locOnScreen)
                ShotGeometry.TableBounds(
                    left = region.left * rw - locOnScreen[0],
                    top = region.top * rh - locOnScreen[1],
                    right = region.right * rw - locOnScreen[0],
                    bottom = region.bottom * rh - locOnScreen[1]
                )
            } else {
                // Cadangan (mock) sampai meja terdeteksi; kotak tepi tidak digambar
                val marginX = width * 0.12f
                val marginY = height * 0.16f
                ShotGeometry.TableBounds(
                    left = marginX,
                    top = marginY,
                    right = width - marginX,
                    bottom = height - marginY
                )
            }
            val ballRadius = (tableBounds.height * 0.032f).coerceAtLeast(14f)

            val midX = (tableBounds.left + tableBounds.right) * 0.5f
            val pockets = listOf(
                ShotGeometry.Vec2(tableBounds.left, tableBounds.top),
                ShotGeometry.Vec2(midX, tableBounds.top),
                ShotGeometry.Vec2(tableBounds.right, tableBounds.top),
                ShotGeometry.Vec2(tableBounds.left, tableBounds.bottom),
                ShotGeometry.Vec2(midX, tableBounds.bottom),
                ShotGeometry.Vec2(tableBounds.right, tableBounds.bottom)
            )

            if (detected) {
                // Gambar sedikit DI LUAR kain (di bantalan/rel) supaya garis overlay
                // tidak tertangkap sebagai bagian kain pada deteksi berikutnya.
                tableBorderPaint.color = theme.accent
                val pad = tableBorderPaint.strokeWidth
                canvas.drawRect(
                    tableBounds.left - pad,
                    tableBounds.top - pad,
                    tableBounds.right + pad,
                    tableBounds.bottom + pad,
                    tableBorderPaint
                )
                // Penanda enam pocket (empat sudut + tengah sisi panjang)
                pockets.forEach { pocket ->
                    canvas.drawCircle(pocket.x, pocket.y, 12f, pocketMarkerPaint)
                }
            }

            // Bola putih: posisi asli dari deteksi jika meja & bola putih terdeteksi,
            // selain itu posisi mock lama (hanya saat meja belum terdeteksi).
            val cueState = detectedCue
            val useRealCue = detected && cueState != null
            val cueBall = if (cueState != null && useRealCue) {
                getLocationOnScreen(locOnScreen)
                ShotGeometry.BallState(
                    number = 0,
                    position = ShotGeometry.Vec2(
                        cueState.x * rw - locOnScreen[0],
                        cueState.y * rh - locOnScreen[1]
                    )
                )
            } else {
                ShotGeometry.BallState(
                    number = 0,
                    position = ShotGeometry.Vec2(
                        tableBounds.left + tableBounds.width * 0.28f,
                        tableBounds.top + tableBounds.height * 0.58f
                    )
                )
            }

            val mockObjectBalls = listOf(
                ShotGeometry.BallState(
                    number = 1,
                    position = ShotGeometry.Vec2(
                        tableBounds.left + tableBounds.width * 0.62f,
                        tableBounds.top + tableBounds.height * 0.36f
                    )
                ),
                ShotGeometry.BallState(
                    number = 11,
                    position = ShotGeometry.Vec2(
                        tableBounds.left + tableBounds.width * 0.72f,
                        tableBounds.top + tableBounds.height * 0.64f
                    )
                ),
                ShotGeometry.BallState(
                    number = 8,
                    position = ShotGeometry.Vec2(
                        tableBounds.left + tableBounds.width * 0.52f,
                        tableBounds.top + tableBounds.height * 0.74f
                    )
                )
            )

            if (Config.USE_MOCK_DATA) {
                // Bola putih mock hanya di mode cadangan; bola putih palsu akan
                // tertangkap capture dan mengganggu deteksi bola putih asli.
                if (!detected) drawMockBall(canvas, cueBall, ballRadius)
                mockObjectBalls.forEach { drawMockBall(canvas, it, ballRadius) }
            }

            if (useRealCue) {
                // Cincin hijau di luar bola putih asli (bukan warna putih, tidak ikut terdeteksi)
                canvas.drawCircle(
                    cueBall.position.x,
                    cueBall.position.y,
                    ballRadius + 4f,
                    cueMarkerPaint
                )
            }

            // Meja sudah terdeteksi tetapi bola putih belum: jangan gambar garis dari posisi palsu
            if (detected && cueState == null) return

            // Sudut dari stik asli jika terdeteksi; selain itu dari slider manual
            val effectiveAngleDeg = detectedAim?.aimAngleDeg ?: mockAimAngleDeg
            val angleRad = (effectiveAngleDeg * PI / 180.0).toFloat()
            val prediction = ShotGeometry.calculateShot(
                cueBall = cueBall,
                objectBalls = mockObjectBalls,
                pockets = pockets,
                tableBounds = tableBounds,
                ballRadius = ballRadius,
                aimAngleRad = angleRad,
                cuePower = mockCuePower
            )

            canvas.save()
            canvas.clipRect(tableBounds.left, tableBounds.top, tableBounds.right, tableBounds.bottom)

            prediction.cueLine?.let { line ->
                canvas.drawLine(line.start.x, line.start.y, line.end.x, line.end.y, cueLinePaint)
            }

            prediction.ghostBallCenter?.let { ghost ->
                canvas.drawCircle(ghost.x, ghost.y, ballRadius, ghostBallPaint)
            }

            val targetNum = prediction.targetBallNumber
            val targetLine = prediction.targetLine
            if (targetNum != null && targetLine != null) {
                val ballStyle = Config.getBallVisualStyle(targetNum)
                targetSolidPaint.color = Color.parseColor(ballStyle.colorHex)

                canvas.drawLine(
                    targetLine.start.x,
                    targetLine.start.y,
                    targetLine.end.x,
                    targetLine.end.y,
                    targetSolidPaint
                )

                if (ballStyle.isStripe) {
                    canvas.drawLine(
                        targetLine.start.x,
                        targetLine.start.y,
                        targetLine.end.x,
                        targetLine.end.y,
                        targetStripeOverlayPaint
                    )
                }
            }

            canvas.restore()
        }

        private fun drawMockBall(canvas: Canvas, ball: ShotGeometry.BallState, radius: Float) {
            val style = Config.getBallVisualStyle(ball.number)
            mockBallPaint.color = Color.parseColor(style.colorHex)
            canvas.drawCircle(ball.position.x, ball.position.y, radius, mockBallPaint)

            if (style.isStripe) {
                mockBallPaint.color = Color.WHITE
                canvas.drawCircle(ball.position.x, ball.position.y, radius * 0.55f, mockBallPaint)
            }
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
    }
}

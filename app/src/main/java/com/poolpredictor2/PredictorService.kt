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
    private var captureStatusText: TextView? = null
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var capW = 0
    private var capH = 0

    private var mockAimAngleDeg = -18.5f
    private var mockCuePower = 0.72f

    private val renderLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            overlayCanvasView.invalidate()
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

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            capW = dm.widthPixels
            capH = dm.heightPixels

            val thread = HandlerThread("pp2-capture").also { it.start() }
            captureThread = thread
            val handler = Handler(thread.looper)

            val reader = ImageReader.newInstance(capW, capH, GfxPixelFormat.RGBA_8888, 2)
            imageReader = reader
            lastFpsTime = System.currentTimeMillis()
            frameCount = 0

            reader.setOnImageAvailableListener({ r ->
                // Frame dibuang dulu; pemrosesan vision menyusul di tahap berikutnya
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                img.close()
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    val fps = frameCount * 1000f / (now - lastFpsTime)
                    frameCount = 0
                    lastFpsTime = now
                    mainHandler.post {
                        setCaptureStatus("Capture: ${capW}x${capH} | %.0f fps".format(fps))
                    }
                }
            }, handler)

            virtualDisplay = mp.createVirtualDisplay(
                "pp2-capture", capW, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            setCaptureStatus("Capture: ${capW}x${capH} | menunggu frame...")
        } catch (e: Exception) {
            stopCapture()
            setCaptureStatus("Capture gagal: ${e.javaClass.simpleName}")
        }
    }

    private fun stopCapture() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        captureThread?.quitSafely(); captureThread = null
    }

    private fun setCaptureStatus(text: String) {
        captureStatusText?.text = text
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

        container.addView(titleText)
        container.addView(infoText)
        container.addView(captureText)
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

            val marginX = width * 0.12f
            val marginY = height * 0.16f
            val tableBounds = ShotGeometry.TableBounds(
                left = marginX,
                top = marginY,
                right = width - marginX,
                bottom = height - marginY
            )
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

            tableBorderPaint.color = theme.accent
            canvas.drawRect(
                tableBounds.left,
                tableBounds.top,
                tableBounds.right,
                tableBounds.bottom,
                tableBorderPaint
            )

            val cueBall = ShotGeometry.BallState(
                number = 0,
                position = ShotGeometry.Vec2(
                    tableBounds.left + tableBounds.width * 0.28f,
                    tableBounds.top + tableBounds.height * 0.58f
                )
            )

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
                drawMockBall(canvas, cueBall, ballRadius)
                mockObjectBalls.forEach { drawMockBall(canvas, it, ballRadius) }
            }

            val angleRad = (mockAimAngleDeg * PI / 180.0).toFloat()
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

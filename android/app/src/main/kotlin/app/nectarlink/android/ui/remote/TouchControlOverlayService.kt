// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.R
import app.nectarlink.android.core.Core
import app.nectarlink.android.ui.MainActivity
import app.nectarlink.core.MirrorInputEvent
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Interactive, resizable floating overlay service (PiP mode) for TouchControl.
 * Allows the user to view the live PC screen, move mouse, tap, scroll, use shortcut
 * buttons, and type in a small adjustable window while other apps are active on the phone.
 */
class TouchControlOverlayService : Service() {

    companion object {
        private const val TAG = "TouchOverlayService"
        const val ACTION_START = "app.nectarlink.android.ui.remote.ACTION_START_OVERLAY"
        const val ACTION_STOP = "app.nectarlink.android.ui.remote.ACTION_STOP_OVERLAY"
        const val EXTRA_PC_ID = "extra_pc_id"
        const val EXTRA_PC_NAME = "extra_pc_name"
        private const val NOTIFICATION_ID = 4099
        private const val CHANNEL_ID = "touch_control_pip_channel"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, pcId: String, pcName: String) {
            val intent = Intent(context, TouchControlOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PC_ID, pcId)
                putExtra(EXTRA_PC_NAME, pcName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TouchControlOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null

    // Window views
    private var windowView: FrameLayout? = null
    private var bubbleView: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // PC connection info
    private var pcId: String = ""
    private var pcName: String = "PC"
    private var core: Core? = null

    // Decoding & display
    private var textureView: TextureView? = null
    private var targetSurface: Surface? = null
    private var mediaCodec: MediaCodec? = null
    private var decodeJob: Job? = null
    private val codecLock = Any()

    private var isBubble: Boolean = false
    private var isKeyboardOpen: Boolean = false
    private var keyboardBar: LinearLayout? = null
    private var keyboardInput: EditText? = null

    private fun dp(value: Int): Int {
        val dm = resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), dm).roundToInt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        core = (applicationContext as? NectarlinkApplication)?.core
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val id = intent?.getStringExtra(EXTRA_PC_ID)
        val name = intent?.getStringExtra(EXTRA_PC_NAME)
        if (!id.isNullOrBlank()) pcId = id
        if (!name.isNullOrBlank()) pcName = name

        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlay: permission missing")
            stopSelf()
            return START_NOT_STICKY
        }

        if (windowView == null) {
            initWindowLayout()
            initBubbleLayout()
            showWindow()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_pip_mode),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.touch_control_overlay_notification)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, TouchControlOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val closePending = PendingIntent.getService(
            this, 1, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pip)
            .setContentTitle(getString(R.string.remote_pip_mode))
            .setContentText(getString(R.string.touch_control_overlay_controlling, pcName))
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.touch_control_overlay_close), closePending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initWindowLayout() {
        val dm = resources.displayMetrics
        val initialWidth = min(dp(340), (dm.widthPixels * 0.9f).roundToInt())
        val initialHeight = min(dp(270), (dm.heightPixels * 0.5f).roundToInt())

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - initialWidth) / 2
            y = dp(80)
        }

        // Root container with rounded corners and border
        val root = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B1F"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#49454F"))
            }
            background = bg
            clipToOutline = true
        }

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(contentColumn)

        // 1. Header drag bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
            setBackgroundColor(Color.parseColor("#262529"))
            setPadding(dp(10), 0, dp(6), 0)
        }

        // Status dot
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(6)
            }
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
            }
            background = dotBg
        }
        header.addView(dot)

        // PC Name title
        val title = TextView(this).apply {
            text = pcName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.widget.TextView.BufferType.NORMAL.let { android.text.TextUtils.TruncateAt.END }
        }
        header.addView(title)

        // Header Action: Minimize to Bubble
        val btnMinimize = createHeaderButton("—") {
            minimizeToBubble()
        }
        header.addView(btnMinimize)

        // Header Action: Fullscreen / Return to App
        val btnExpand = createHeaderButton("⤢") {
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(appIntent)
            stopSelf()
        }
        header.addView(btnExpand)

        // Header Action: Close
        val btnClose = createHeaderButton("✕") {
            stopSelf()
        }
        header.addView(btnClose)

        // Dragging the header moves the window
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = windowParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + (event.rawX - touchX)).toInt()
                        params.y = (startY + (event.rawY - touchY)).toInt()
                        windowManager?.updateViewLayout(root, params)
                        return true
                    }
                }
                return false
            }
        })
        contentColumn.addView(header)

        // 2. Video Preview & Touch Surface (TextureView)
        val videoContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
        }

        val tv = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        setupTextureViewEvents(tv)
        videoContainer.addView(tv)
        textureView = tv
        contentColumn.addView(videoContainer)

        // 3. Inline Keyboard input bar (hidden by default)
        val kbBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
            setBackgroundColor(Color.parseColor("#201F23"))
            setPadding(dp(6), dp(3), dp(6), dp(3))
            visibility = View.GONE
        }
        val edit = EditText(this).apply {
            hint = "Type to PC…"
            setHintTextColor(Color.parseColor("#8E8D92"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.parseColor("#2D2C31"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    val text = text.toString()
                    if (text.isNotEmpty()) {
                        core?.remoteText(pcId, text)
                        setText("")
                    }
                    core?.remoteKey(pcId, "enter", emptyList())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (count > 0 && s != null) {
                        val added = s.subSequence(start, start + count).toString()
                        if (added != "\n") {
                            core?.remoteText(pcId, added)
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        kbBar.addView(edit)
        keyboardInput = edit

        val btnBs = createToolbarButton("⌫") {
            core?.remoteKey(pcId, "backspace", emptyList())
        }
        kbBar.addView(btnBs)

        val btnHideKb = createToolbarButton("✓") {
            toggleKeyboard()
        }
        kbBar.addView(btnHideKb)

        keyboardBar = kbBar
        contentColumn.addView(kbBar)

        // 4. Quick Actions Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
            setBackgroundColor(Color.parseColor("#222125"))
            setPadding(dp(4), 0, dp(28), 0) // extra padding on right for resize handle
        }

        toolbar.addView(createToolbarButton("Left") { core?.remoteButton(pcId, "left", "click") })
        toolbar.addView(createToolbarButton("Mid") { core?.remoteButton(pcId, "middle", "click") })
        toolbar.addView(createToolbarButton("Right") { core?.remoteButton(pcId, "right", "click") })
        toolbar.addView(createToolbarButton("Esc") { core?.remoteKey(pcId, "escape", emptyList()) })
        toolbar.addView(createToolbarButton("Win") { core?.remoteKey(pcId, "meta", emptyList()) })
        toolbar.addView(createToolbarButton("Kb") { toggleKeyboard() })

        contentColumn.addView(toolbar)

        // 5. Corner Resize Handle (bottom-right)
        val resizeHandle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#938F99"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(4), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                dp(28),
                dp(28),
                Gravity.BOTTOM or Gravity.END
            )
        }

        val minW = dp(200)
        val minH = dp(160)
        val maxW = dm.widthPixels
        val maxH = (dm.heightPixels * 0.9f).roundToInt()

        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initW = 0
            private var initH = 0
            private var initTouchX = 0f
            private var initTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = windowParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initW = params.width
                        initH = params.height
                        initTouchX = event.rawX
                        initTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initTouchX).toInt()
                        val deltaY = (event.rawY - initTouchY).toInt()
                        params.width = (initW + deltaX).coerceIn(minW, maxW)
                        params.height = (initH + deltaY).coerceIn(minH, maxH)
                        windowManager?.updateViewLayout(root, params)
                        return true
                    }
                }
                return false
            }
        })
        root.addView(resizeHandle)

        windowView = root
    }

    private fun createHeaderButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#CAC4D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(4), 0, dp(4), 0)
            minWidth = dp(28)
            minHeight = dp(28)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createToolbarButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#36343B"))
                cornerRadius = dp(6).toFloat()
            }
            background = btnBg
            setPadding(dp(4), 0, dp(4), 0)
            minWidth = dp(32)
            minHeight = dp(26)
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTextureViewEvents(tv: TextureView) {
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                targetSurface = Surface(surface)
                startVideoDecoder()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopVideoDecoder()
                targetSurface?.release()
                targetSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // Gesture handling on TextureView:
        // 1 finger move -> mouse move
        // 1 finger tap -> left click
        // 2 finger tap -> right click
        // 2 finger scroll -> mouse scroll
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L
            private var maxPointerCount = 1
            private var isScroll = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val c = core ?: return false
                val id = pcId
                if (id.isEmpty()) return false

                val pointerCount = event.pointerCount
                if (pointerCount > maxPointerCount) maxPointerCount = pointerCount

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        lastX = event.x
                        lastY = event.y
                        downTime = System.currentTimeMillis()
                        maxPointerCount = 1
                        isScroll = false
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (pointerCount == 2) {
                            isScroll = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isScroll && pointerCount >= 2) {
                            val dy = event.getY(0) - lastY
                            c.remoteScroll(id, 0f, -(dy / 18f), fast = true)
                            lastY = event.getY(0)
                        } else if (pointerCount == 1) {
                            val dx = event.x - lastX
                            val dy = event.y - lastY
                            c.remoteMove(id, dx * 1.5f, dy * 1.5f)
                            lastX = event.x
                            lastY = event.y
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - downTime
                        val moved = abs(event.x - downX) + abs(event.y - downY)
                        if (duration < 300 && moved < dp(12)) {
                            if (maxPointerCount >= 2) {
                                c.remoteButton(id, "right", "click")
                            } else {
                                c.remoteButton(id, "left", "click")
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleKeyboard() {
        isKeyboardOpen = !isKeyboardOpen
        val bar = keyboardBar ?: return
        val edit = keyboardInput ?: return
        val params = windowParams ?: return
        val root = windowView ?: return

        if (isKeyboardOpen) {
            bar.visibility = View.VISIBLE
            // Remove NOT_FOCUSABLE flag so the EditText can receive focus & soft keyboard
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(root, params)
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        } else {
            bar.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(root, params)
        }
    }

    private fun initBubbleLayout() {
        val dm = resources.displayMetrics
        val size = dp(52)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(10)
            y = dp(120)
        }

        val bubble = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6750A4"))
                setStroke(dp(2), Color.WHITE)
            }
            background = bg
            elevation = dp(8).toFloat()
        }

        val icon = TextView(this).apply {
            text = "🖱️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubble.addView(icon)

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var downTime = 0L

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = bubbleParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        downTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + (event.rawX - touchX)).toInt()
                        params.y = (startY + (event.rawY - touchY)).toInt()
                        windowManager?.updateViewLayout(bubble, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - downTime
                        val moved = abs(event.rawX - touchX) + abs(event.rawY - touchY)
                        if (duration < 250 && moved < dp(8)) {
                            expandFromBubble()
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = bubble
    }

    private fun showWindow() {
        val root = windowView ?: return
        val params = windowParams ?: return
        try {
            if (root.parent == null) {
                windowManager?.addView(root, params)
            }
            isBubble = false
        } catch (e: Exception) {
            Log.e(TAG, "Error adding window view", e)
        }
    }

    private fun minimizeToBubble() {
        val root = windowView ?: return
        val bubble = bubbleView ?: return
        val bParams = bubbleParams ?: return
        try {
            if (root.parent != null) {
                windowManager?.removeView(root)
            }
            if (bubble.parent == null) {
                windowManager?.addView(bubble, bParams)
            }
            isBubble = true
        } catch (e: Exception) {
            Log.e(TAG, "Error minimizing to bubble", e)
        }
    }

    private fun expandFromBubble() {
        val root = windowView ?: return
        val bubble = bubbleView ?: return
        val wParams = windowParams ?: return
        try {
            if (bubble.parent != null) {
                windowManager?.removeView(bubble)
            }
            if (root.parent == null) {
                windowManager?.addView(root, wParams)
            }
            isBubble = false
        } catch (e: Exception) {
            Log.e(TAG, "Error expanding from bubble", e)
        }
    }

    private fun extractH264Config(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var i = 0
        var firstPlayableNalOffset = -1
        var sawConfig = false
        while (i + 4 < data.size) {
            val scLen = if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) 3
            else if (i + 4 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) 4
            else { i++; continue }
            val nalHeaderIdx = i + scLen
            if (nalHeaderIdx >= data.size) break
            val nalType = data[nalHeaderIdx].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) sawConfig = true
            else if (nalType == 5 || nalType == 1) { firstPlayableNalOffset = i; break }
            i = nalHeaderIdx + 1
        }
        return if (sawConfig && firstPlayableNalOffset in 1 until data.size) {
            Pair(data.copyOfRange(0, firstPlayableNalOffset), data.copyOfRange(firstPlayableNalOffset, data.size))
        } else null
    }

    private fun extractHevcConfig(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var i = 0
        var firstPlayableNalOffset = -1
        var sawConfig = false
        while (i + 4 < data.size) {
            val scLen = if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) 3
            else if (i + 4 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) 4
            else { i++; continue }
            val nalHeaderIdx = i + scLen
            if (nalHeaderIdx >= data.size) break
            val nalType = (data[nalHeaderIdx].toInt() and 0x7E) ushr 1
            if (nalType in 32..34) sawConfig = true
            else if (nalType in 0..31) { firstPlayableNalOffset = i; break }
            i = nalHeaderIdx + 1
        }
        return if (sawConfig && firstPlayableNalOffset in 1 until data.size) {
            Pair(data.copyOfRange(0, firstPlayableNalOffset), data.copyOfRange(firstPlayableNalOffset, data.size))
        } else null
    }

    private fun startVideoDecoder() {
        val c = core ?: return
        val surf = targetSurface ?: return
        if (pcId.isEmpty() || !surf.isValid) return

        c.startPcMirror(pcId, maxSize = 720, fps = 30, bitrate = 1_500_000, watch = false)
        c.requestPcMirrorKeyframe(pcId)

        decodeJob?.cancel()
        decodeJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val effectiveCodec = c.pcMirrorConfig.value.codec ?: "h264"
                val mime = if (effectiveCodec == "h265" || effectiveCodec == "hevc") {
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                } else {
                    MediaFormat.MIMETYPE_VIDEO_AVC
                }
                val format = MediaFormat.createVideoFormat(mime, 1280, 720)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, surf, null, 0)
                codec.start()
                synchronized(codecLock) {
                    mediaCodec = codec
                }

                fun queueRaw(bytes: ByteArray, timeUs: Long, flags: Int) {
                    synchronized(codecLock) {
                        val activeCodec = mediaCodec ?: return
                        val inIdx = activeCodec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = activeCodec.getInputBuffer(inIdx)
                            inBuf?.clear()
                            inBuf?.put(bytes)
                            activeCodec.queueInputBuffer(inIdx, 0, bytes.size, timeUs, flags)
                        }
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var hasRenderedFirst = false

                val watchdogJob = launch {
                    delay(2500L)
                    while (isActive && !hasRenderedFirst) {
                        c.requestPcMirrorKeyframe(pcId)
                        delay(2500L)
                    }
                }

                try {
                    c.latestPcMirrorKeyframe(pcId)?.let { cached ->
                        if (cached.keyframe) {
                            val split = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) extractHevcConfig(cached.data) else extractH264Config(cached.data)
                            if (split != null) {
                                queueRaw(split.first, cached.timeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                queueRaw(split.second, cached.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            } else {
                                queueRaw(cached.data, cached.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            }
                        }
                    }

                    c.pcMirrorPackets.collect { packet ->
                        if (packet.pcId != pcId || !surf.isValid || !isActive) return@collect
                        synchronized(codecLock) {
                            val activeCodec = mediaCodec ?: return@collect
                            try {
                                if (packet.keyframe) {
                                    val split = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) extractHevcConfig(packet.data) else extractH264Config(packet.data)
                                    if (split != null) {
                                        queueRaw(split.first, packet.timeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                        queueRaw(split.second, packet.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                    } else {
                                        queueRaw(packet.data, packet.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                    }
                                } else {
                                    queueRaw(packet.data, packet.timeUs, 0)
                                }

                                var outIdx = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
                                while (outIdx >= 0) {
                                    activeCodec.releaseOutputBuffer(outIdx, true)
                                    hasRenderedFirst = true
                                    outIdx = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Decode frame error", e)
                            }
                        }
                    }
                } finally {
                    watchdogJob.cancel()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Video decoder failed", e)
            } finally {
                stopVideoDecoder()
            }
        }
    }

    private fun stopVideoDecoder() {
        synchronized(codecLock) {
            mediaCodec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            mediaCodec = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        decodeJob?.cancel()
        stopVideoDecoder()
        targetSurface?.release()
        targetSurface = null

        windowView?.let {
            if (it.parent != null) windowManager?.removeView(it)
        }
        bubbleView?.let {
            if (it.parent != null) windowManager?.removeView(it)
        }
        core?.stopPcMirror(pcId)
    }
}


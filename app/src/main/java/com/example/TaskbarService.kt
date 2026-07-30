package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.data.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class TaskbarService : Service(), ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private var lifecycleRegistry: androidx.lifecycle.LifecycleRegistry? = null
    private var currentDisplayId = android.view.Display.DEFAULT_DISPLAY

    // Reactive Compose States inside overlay
    private val pinnedAppsState = mutableStateListOf<PinnedApp>()
    private val allAppsState = mutableStateListOf<AppInfo>()

    // Config states
    private var positionEdge = mutableStateOf("right")
    private var orientation = mutableStateOf("vertical")
    private var opacity = mutableStateOf(0.85f)
    private var themePreset = mutableStateOf("glass")
    private var isExpanded = mutableStateOf(false)
    private var isSearchOpen = mutableStateOf(false)
    private var isMinimized = mutableStateOf(false)
    private var searchQuery = mutableStateOf("")

    private var overlapNavBar = mutableStateOf(true)

    // Persistent dragged Y offset
    private var offsetPercentY = mutableStateOf(0.4f) // position percentage from top (0.0f - 1.0f)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("omni_taskbar_prefs", MODE_PRIVATE)

        loadPreferences()
        loadPinnedApps()
        loadAllInstalledAppsAsync()
    }

    private fun loadPreferences() {
        positionEdge.value = prefs.getString("pref_taskbar_position", "right") ?: "right"
        orientation.value = prefs.getString("pref_taskbar_orientation", "vertical") ?: "vertical"
        opacity.value = prefs.getFloat("pref_taskbar_opacity", 0.85f)
        themePreset.value = prefs.getString("pref_taskbar_theme", "glass") ?: "glass"
        offsetPercentY.value = prefs.getFloat("pref_taskbar_offset_y", 0.4f)
        overlapNavBar.value = prefs.getBoolean("pref_overlap_nav_bar", true)
    }

    private fun saveOffsetY(percent: Float) {
        offsetPercentY.value = percent
        prefs.edit().putFloat("pref_taskbar_offset_y", percent).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PermissionUtils.canDrawOverlaysCompat(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val displayId = intent?.getIntExtra("display_id", android.view.Display.DEFAULT_DISPLAY)
            ?: android.view.Display.DEFAULT_DISPLAY

        if (displayId != currentDisplayId || !::composeView.isInitialized) {
            currentDisplayId = displayId
            recreateOverlayForDisplay(displayId)
        } else if (intent != null && intent.action == "ACTION_REFRESH_CONFIG") {
            loadPreferences()
            updateOverlayLayout()
        }
        return START_STICKY
    }

    private fun loadPinnedApps() {
        serviceScope.launch {
            val database = AppDatabase.getDatabase(this@TaskbarService)
            val repository = PinnedAppRepository(database.pinnedAppDao())
            repository.allPinnedApps.collectLatest { apps ->
                pinnedAppsState.clear()
                pinnedAppsState.addAll(apps)
            }
        }
    }

    private fun loadAllInstalledAppsAsync() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val apps = resolveInfos.map { resolveInfo ->
                    AppInfo(
                        packageName = resolveInfo.activityInfo.packageName,
                        appName = resolveInfo.loadLabel(pm).toString(),
                        launcherActivity = resolveInfo.activityInfo.name
                    )
                }.sortedBy { it.appName.lowercase() }

                launch(Dispatchers.Main) {
                    allAppsState.clear()
                    allAppsState.addAll(apps)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupOverlayView() {
        if (!PermissionUtils.canDrawOverlaysCompat(this)) {
            stopSelf()
            return
        }
        val customLifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle = registry
        }
        lifecycleRegistry = customLifecycleOwner.registry

        val customSavedStateRegistryOwner = object : androidx.savedstate.SavedStateRegistryOwner {
            private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
            override val lifecycle: androidx.lifecycle.Lifecycle = customLifecycleOwner.lifecycle
            override val savedStateRegistry: androidx.savedstate.SavedStateRegistry = controller.savedStateRegistry

            fun performRestore() {
                controller.performRestore(null)
            }
        }

        // 1. Perform restore while state of lifecycle is INITIALIZED (mandatory for Jetpack SavedState components!)
        customSavedStateRegistryOwner.performRestore()

        // 2. Advance lifecycle registry state to RESUMED to activate compose framework elements
        customLifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(customLifecycleOwner)
            setViewTreeViewModelStoreOwner(this@TaskbarService)
            setViewTreeSavedStateRegistryOwner(customSavedStateRegistryOwner)
        }

        // Layout Parameters
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            val edge = positionEdge.value
            if (edge == "bottom") {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            } else {
                gravity = Gravity.TOP or if (edge == "left") Gravity.START else Gravity.END
            }
            if (overlapNavBar.value) {
                // Do NOT set FLAG_LAYOUT_NO_LIMITS here as it completely breaks touch inputs in overlay windows
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    fitInsetsTypes = android.view.WindowInsets.Type.systemBars()
                }
            }
            x = 0
            val screenHeight = getScreenHeight()
            if (edge == "bottom") {
                val navBarHeight = if (overlapNavBar.value) getNavigationBarHeight(this@TaskbarService) else 0
                y = ((screenHeight - navBarHeight) * offsetPercentY.value).toInt() + navBarHeight
            } else {
                val statusBarHeight = getStatusBarHeight(this@TaskbarService)
                y = ((screenHeight - statusBarHeight) * offsetPercentY.value).toInt() + statusBarHeight
            }
        }

        // Setup Content
        composeView.setContent {
            MyApplicationTheme {
                TaskbarOverlayContent()
            }
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recreateOverlayForDisplay(displayId: Int) {
        if (!PermissionUtils.canDrawOverlaysCompat(this)) {
            stopSelf()
            return
        }
        if (::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val targetDisplay = displayManager.getDisplay(displayId)
        val displayContext = if (targetDisplay != null) {
            createDisplayContext(targetDisplay)
        } else {
            this
        }

        windowManager = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayView()
    }

    private fun updateOverlayLayout() {
        updateWindowConstraints()
    }

    private fun updateWindowConstraints() {
        if (!::composeView.isInitialized) return
        try {
            val expanded = isExpanded.value
            val searchOpen = isSearchOpen.value
            val edge = positionEdge.value

            if (searchOpen) {
                // Search mode: expand overlay window fully to capture keyboard focus and touches nicely
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.CENTER
                params.x = 0
                params.y = 0
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            } else {
                // Dock or collapsed mode: Wrap content, make non-focusable to let ambient clicks leak through
                if (orientation.value == "horizontal") {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                } else {
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                }
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (edge == "bottom") {
                    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                } else {
                    params.gravity = Gravity.TOP or if (edge == "left") Gravity.START else Gravity.END
                }
                params.x = 0
                val screenHeight = getScreenHeight()
                if (edge == "bottom") {
                    val navBarHeight = if (overlapNavBar.value) getNavigationBarHeight(this@TaskbarService) else 0
                    params.y = ((screenHeight - navBarHeight) * offsetPercentY.value).toInt() + navBarHeight
                } else {
                    val statusBarHeight = getStatusBarHeight(this@TaskbarService)
                    params.y = ((screenHeight - statusBarHeight) * offsetPercentY.value).toInt() + statusBarHeight
                }
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                if (overlapNavBar.value) {
                    // Do NOT set FLAG_LAYOUT_NO_LIMITS here as it completely breaks touch inputs in overlay windows
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        params.fitInsetsTypes = 0
                    }
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        params.fitInsetsTypes = android.view.WindowInsets.Type.systemBars()
                    }
                }
            }
            windowManager.updateViewLayout(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleFocusable(focusable: Boolean) {
        updateWindowConstraints()
    }

    private fun getScreenHeight(): Int {
        return try {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        } catch (e: Exception) {
            1280
        }
    }

    private fun getNavigationBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlayLayout()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            lifecycleRegistry?.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::windowManager.isInitialized && ::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Compose Overlay Composable Layers ---

    @Composable
    fun TaskbarOverlayContent() {
        val edge by positionEdge
        val taskbarOrientation by orientation
        val preset by themePreset
        val activeOpacity by opacity
        val expanded by isExpanded
        val searchOpen by isSearchOpen
        val minimized by isMinimized

        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        // Dynamic Window Sizer hook
        LaunchedEffect(expanded, searchOpen, edge, minimized) {
            updateWindowConstraints()
        }

        // Theme specifications returning Triple(BackgroundBrush, AccentColor, ContentColor)
        val themeBrushes = remember(preset, activeOpacity, taskbarOrientation) {
            val isHorizontal = taskbarOrientation == "horizontal"
            val makeBrush: (List<Color>) -> Brush = { colors ->
                if (isHorizontal) Brush.horizontalGradient(colors) else Brush.verticalGradient(colors)
            }
            when (preset) {
                "neon" -> Triple(
                    makeBrush(listOf(Color(0xFF0F2027).copy(activeOpacity), Color(0xFF203A43).copy(activeOpacity))),
                    Color(0xFF00FFCC),
                    Color.White
                )
                "carbon" -> Triple(
                    makeBrush(listOf(Color(0xFF1E1E24).copy(activeOpacity), Color(0xFF111115).copy(activeOpacity))),
                    Color(0xFFE94560),
                    Color.White
                )
                "warm" -> Triple(
                    makeBrush(listOf(Color(0xFF2D142C).copy(activeOpacity), Color(0xFF801336).copy(activeOpacity))),
                    Color(0xFFFFB037),
                    Color.White
                )
                "black" -> Triple(
                    makeBrush(listOf(Color(0xFF000000).copy(activeOpacity), Color(0xFF000000).copy(activeOpacity))),
                    Color(0xFFFFFFFF),
                    Color.White
                )
                "white" -> Triple(
                    makeBrush(listOf(Color(0xFFFFFFFF).copy(activeOpacity), Color(0xFFFFFFFF).copy(activeOpacity))),
                    Color(0xFF000000),
                    Color.Black
                )
                else -> Triple( // glass
                    makeBrush(listOf(Color(0xE01A1C1E).copy(activeOpacity), Color(0xE0121315).copy(activeOpacity))),
                    Color(0xFF8AB4F8),
                    Color.White
                )
            }
        }

        val backgroundBrush = themeBrushes.first
        val accentColor = themeBrushes.second
        val contentColor = themeBrushes.third

        // Handle alignment layout direction
        val contentAlignment = when (edge) {
            "left" -> Alignment.CenterStart
            "bottom" -> Alignment.BottomCenter
            else -> Alignment.CenterEnd
        }

        Box(
            modifier = if (searchOpen) Modifier.fillMaxSize() else if (taskbarOrientation == "horizontal") Modifier.fillMaxWidth() else Modifier.wrapContentSize(),
            contentAlignment = contentAlignment
        ) {
            if (!searchOpen) {
                if (minimized) {
                    UnhideFloatingButton(accentColor, edge, preset)
                } else {
                    if (taskbarOrientation == "horizontal") {
                        HorizontalTaskbar(backgroundBrush, accentColor, contentColor, edge)
                    } else {
                        VerticalTaskbar(backgroundBrush, accentColor, contentColor, edge)
                    }
                }
            }

            // All installed applications search modal
            if (searchOpen) {
                // Dim screen scrim click cover
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            closeLauncherSearch()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Inner dialog with click interception to avoid dismissing on dialog actions
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(400.dp)
                            .padding(horizontal = 8.dp)
                            .shadow(24.dp, RoundedCornerShape(24.dp))
                            .background(backgroundBrush, RoundedCornerShape(24.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* Prevent tap propagation outside */ }
                            .drawBehind {
                                drawRoundRect(
                                    color = accentColor.copy(alpha = 0.4f),
                                    style = Stroke(1.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                                )
                            }
                            .padding(16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        SearchAppsDialog(accentColor, contentColor)
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderPinnedItem(
        pinnedApp: PinnedApp,
        accentColor: Color,
        contentColor: Color,
        isHorizontal: Boolean
    ) {
        val context = LocalContext.current
        val pkg = pinnedApp.packageName
        if (pkg.startsWith("sys:")) {
            when (pkg) {
                "sys:hide" -> {
                    IconButton(
                        onClick = { isMinimized.value = true },
                        modifier = Modifier.requiredSize(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "Hide Taskbar",
                            tint = accentColor.copy(alpha = 0.7f),
                            modifier = Modifier.requiredSize(20.dp)
                        )
                    }
                }
                "sys:back" -> {
                    DockIconButton(
                        icon = Icons.Default.ArrowBack,
                        description = "Go Back",
                        tint = accentColor
                    ) {
                        if (TaskbarAccessibilityService.isServiceRunning()) {
                            TaskbarAccessibilityService.performBack()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Please enable 'Quick access Helper' in Settings -> Accessibility to use Back.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
                "sys:drag" -> {
                    if (isHorizontal) {
                        HorizontalDragHandle(accentColor)
                    } else {
                        VerticalDragHandle(accentColor)
                    }
                }

                "sys:search" -> {
                    DockIconButton(
                        icon = Icons.Default.Search,
                        description = "Search App Library",
                        tint = accentColor
                    ) {
                        openLauncherSearch()
                    }
                }
                "sys:home" -> {
                    DockIconButton(
                        icon = Icons.Default.Home,
                        description = "Device Home",
                        tint = accentColor
                    ) {
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(homeIntent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
            AppIconPill(pkg, contentColor) {
                launchApp(pkg)
            }
        }
    }

    @Composable
    fun VerticalTaskbar(
        backgroundBrush: Brush,
        accentColor: Color,
        contentColor: Color,
        edge: String
    ) {
        val activeOpacity = opacity.value

        val activeItems = remember(pinnedAppsState.size) {
            pinnedAppsState.toList()
        }

        Column(
            modifier = Modifier
                .systemGestureExclusion()
                .padding(horizontal = 6.dp)
                .shadow(if (activeOpacity <= 0.25f) 0.dp else 12.dp, RoundedCornerShape(20.dp))
                .background(backgroundBrush, RoundedCornerShape(20.dp))
                .drawBehind {
                    drawRoundRect(
                        color = accentColor.copy(alpha = (0.2f * (activeOpacity / 0.5f)).coerceIn(0f, 0.2f)),
                        style = Stroke(1.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                    )
                }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            activeItems.forEachIndexed { index, pinnedApp ->
                RenderPinnedItem(pinnedApp, accentColor, contentColor, isHorizontal = false)
                if (index < activeItems.lastIndex) {
                    DockDivider(accentColor, isHorizontal = false)
                }
            }
        }
    }

    @Composable
    fun HorizontalTaskbar(
        backgroundBrush: Brush,
        accentColor: Color,
        contentColor: Color,
        edge: String
    ) {
        val activeOpacity = opacity.value

        val activeItems = remember(pinnedAppsState.size) {
            pinnedAppsState.toList()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .systemGestureExclusion()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .shadow(if (activeOpacity <= 0.25f) 0.dp else 12.dp, RoundedCornerShape(20.dp))
                .background(backgroundBrush, RoundedCornerShape(20.dp))
                .drawBehind {
                    drawRoundRect(
                        color = accentColor.copy(alpha = (0.2f * (activeOpacity / 0.5f)).coerceIn(0f, 0.2f)),
                        style = Stroke(1.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                    )
                }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            activeItems.forEachIndexed { index, pinnedApp ->
                RenderPinnedItem(pinnedApp, accentColor, contentColor, isHorizontal = true)
                if (index < activeItems.lastIndex) {
                    DockDivider(accentColor, isHorizontal = true)
                }
            }
        }
    }

    @Composable
    fun HorizontalDragHandle(accentColor: Color) {
        Box(
            modifier = Modifier
                .requiredWidth(24.dp)
                .requiredHeight(32.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val screenH = getScreenHeight()
                            // Calculate new coordinates
                            val isBottom = positionEdge.value == "bottom"
                            if (isBottom) {
                                params.y = (params.y - dragAmount.y).toInt()
                                val navBarHeight = if (overlapNavBar.value) getNavigationBarHeight(this@TaskbarService) else 0
                                val boundedY = params.y.coerceIn(navBarHeight, screenH - 150)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - navBarHeight > 0) {
                                    (boundedY - navBarHeight).toFloat() / (screenH - navBarHeight)
                                } else {
                                    0.0f
                                }
                            } else {
                                params.y = (params.y + dragAmount.y).toInt()
                                val statusBarHeight = getStatusBarHeight(this@TaskbarService)
                                val boundedY = params.y.coerceIn(statusBarHeight, screenH - 120)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - statusBarHeight > 0) {
                                    (boundedY - statusBarHeight).toFloat() / (screenH - statusBarHeight)
                                } else {
                                    0.0f
                                }
                            }
                            saveOffsetY(offsetPercentY.value)

                            try {
                                windowManager.updateViewLayout(composeView, params)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.8f))
            )
        }
    }

    @Composable
    fun VerticalDragHandle(accentColor: Color) {
        Box(
            modifier = Modifier
                .requiredWidth(32.dp)
                .requiredHeight(24.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val screenH = getScreenHeight()
                            // Calculate new coordinates
                            val isBottom = positionEdge.value == "bottom"
                            if (isBottom) {
                                params.y = (params.y - dragAmount.y).toInt()
                                val navBarHeight = if (overlapNavBar.value) getNavigationBarHeight(this@TaskbarService) else 0
                                val boundedY = params.y.coerceIn(navBarHeight, screenH - 150)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - navBarHeight > 0) {
                                    (boundedY - navBarHeight).toFloat() / (screenH - navBarHeight)
                                } else {
                                    0.0f
                                }
                            } else {
                                params.y = (params.y + dragAmount.y).toInt()
                                val statusBarHeight = getStatusBarHeight(this@TaskbarService)
                                val boundedY = params.y.coerceIn(statusBarHeight, screenH - 120)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - statusBarHeight > 0) {
                                    (boundedY - statusBarHeight).toFloat() / (screenH - statusBarHeight)
                                } else {
                                    0.0f
                                }
                            }
                            saveOffsetY(offsetPercentY.value)

                            try {
                                windowManager.updateViewLayout(composeView, params)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Elegant twin pill indicator matching Android drag indicators
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.8f))
            )
        }
    }

    private fun openLauncherSearch() {
        searchQuery.value = ""
        isSearchOpen.value = true
        toggleFocusable(true) // Switch overlay to FOCUSABLE mode so keyboard opens!
    }

    private fun closeLauncherSearch() {
        isSearchOpen.value = false
        toggleFocusable(false) // Restore non-focusable overlay
    }

    @Composable
    fun TimeBadge(accentColor: Color) {
        var currentTime by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val formatter = SimpleDateFormat("HH\nmm", Locale.getDefault())
                currentTime = formatter.format(Date())
                kotlinx.coroutines.delay(15000)
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(vertical = 4.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = currentTime,
                color = accentColor,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    fun DockIconButton(
        icon: ImageVector,
        description: String,
        tint: Color,
        onClick: () -> Unit
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.requiredSize(48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.requiredSize(26.dp)
            )
        }
    }

    @Composable
    fun DockDivider(accentColor: Color, isHorizontal: Boolean = false) {
        Box(
            modifier = Modifier
                .requiredWidth(if (isHorizontal) 1.dp else 32.dp)
                .requiredHeight(if (isHorizontal) 32.dp else 1.dp)
                .background(accentColor.copy(alpha = 0.25f))
        )
    }

    @Composable
    fun AppIconPill(
        packName: String,
        contentColor: Color,
        onTap: () -> Unit
    ) {
        val context = LocalContext.current
        val iconBitmap = rememberAppIcon(context, packName)

        Box(
            modifier = Modifier
                .requiredSize(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onTap)
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = packName,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(contentColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "Fail icon",
                        tint = contentColor.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    @Composable
    fun UnhideFloatingButton(accentColor: Color, edge: String, preset: String) {
        val isOledWhite = preset == "white"
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .size(36.dp) // Smaller floating unhide button (was 48.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    color = if (isOledWhite) Color.White else Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .border(
                    width = 1.5.dp,
                    color = accentColor.copy(alpha = 0.8f),
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isMinimized.value = false
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val screenH = getScreenHeight()
                            // Calculate new coordinates
                            if (edge == "bottom") {
                                params.y = (params.y - dragAmount.y).toInt()
                                val navBarHeight = if (overlapNavBar.value) getNavigationBarHeight(this@TaskbarService) else 0
                                val boundedY = params.y.coerceIn(navBarHeight, screenH - 150)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - navBarHeight > 0) {
                                    (boundedY - navBarHeight).toFloat() / (screenH - navBarHeight)
                                } else {
                                    0.0f
                                }
                            } else {
                                params.y = (params.y + dragAmount.y).toInt()
                                val statusBarHeight = getStatusBarHeight(this@TaskbarService)
                                val boundedY = params.y.coerceIn(statusBarHeight, screenH - 120)
                                params.y = boundedY
                                offsetPercentY.value = if (screenH - statusBarHeight > 0) {
                                    (boundedY - statusBarHeight).toFloat() / (screenH - statusBarHeight)
                                } else {
                                    0.0f
                                }
                            }
                            saveOffsetY(offsetPercentY.value)

                            try {
                                windowManager.updateViewLayout(composeView, params)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (edge) {
                    "left" -> Icons.Default.ChevronRight
                    "bottom" -> Icons.Default.ArrowUpward
                    else -> Icons.Default.ChevronLeft
                },
                contentDescription = "Unhide Taskbar",
                tint = accentColor,
                modifier = Modifier.size(20.dp) // Smaller icon (was 28.dp)
            )
        }
    }

    @Composable
    fun SearchAppsDialog(accentColor: Color, contentColor: Color) {
        val query by searchQuery
        val focusManager = LocalFocusManager.current
        var filteredApps = remember(query, allAppsState.size) {
            if (query.isBlank()) {
                allAppsState.toList()
            } else {
                allAppsState.filter {
                    it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Search field Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))

                BasicTextField(
                    value = query,
                    onValueChange = { searchQuery.value = it },
                    textStyle = TextStyle(color = contentColor, fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )

                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = contentColor.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { searchQuery.value = "" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Close overlay dialog button
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Exit Search",
                    tint = Color.Red.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            closeLauncherSearch()
                        }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(contentColor.copy(alpha = 0.15f))
                    .padding(bottom = 8.dp)
            )

            // Results List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (filteredApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No installed apps found",
                                color = contentColor.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(filteredApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    launchApp(app.packageName)
                                    closeLauncherSearch()
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val context = LocalContext.current
                            val iconBitmap = rememberAppIcon(context, app.packageName)

                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = app.appName,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = "Fallback App Icon",
                                    tint = accentColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = app.appName,
                                    color = contentColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = app.packageName,
                                    color = contentColor.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchApp(packName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                // Auto collapse to keep screen tidy!
                isExpanded.value = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isForegroundAppDark(): Boolean {
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val appProcesses = am.runningAppProcesses
            if (appProcesses != null) {
                for (processInfo in appProcesses) {
                    if (processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        val packageName = processInfo.processName
                        val darkApps = listOf("spotify", "youtube", "netflix", "camera", "gallery", "photos", "video", "player", "kodi", "vlc")
                        val lightApps = listOf("chrome", "gmail", "contacts", "settings", "dialer", "calculator", "docs", "sheets", "keep")
                        for (app in darkApps) {
                            if (packageName.lowercase().contains(app)) return true
                        }
                        for (app in lightApps) {
                            if (packageName.lowercase().contains(app)) return false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return isSystemDark
    }
}

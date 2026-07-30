package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.*
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppInfo
import com.example.data.PinnedApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TaskbarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service was enabled, and start if permissions are active now
        val hasOverlayPermission = PermissionUtils.canDrawOverlaysCompat(this)
        if (hasOverlayPermission && viewModel.isServiceEnabled.value) {
            val serviceIntent = Intent(this, TaskbarService::class.java).apply {
                val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    this@MainActivity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
                } else {
                    android.view.Display.DEFAULT_DISPLAY
                }
                putExtra("display_id", displayId)
            }
            try {
                startService(serviceIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: TaskbarViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(PermissionUtils.canDrawOverlaysCompat(context)) }
    var isAccessibilityActive by remember { mutableStateOf(TaskbarAccessibilityService.isServiceRunning()) }

    // Collect variables
    val pinnedApps by viewModel.pinnedApps.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    
    // Dynamic columns: 4 apps in a row, or more if screen is bigger (e.g., tablet)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val columns = remember(configuration.screenWidthDp) {
        maxOf(4, configuration.screenWidthDp / 95)
    }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val systemButtons = remember {
        listOf(
            AppInfo("sys:hide", "Hide Button", ""),
            AppInfo("sys:back", "Back Button", ""),
            AppInfo("sys:drag", "Drag Handle", ""),
            AppInfo("sys:search", "Search Button", ""),
            AppInfo("sys:home", "Home Button", "")
        )
    }
    val matchedSystemButtons = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            systemButtons
        } else {
            systemButtons.filter { it.appName.contains(searchQuery, ignoreCase = true) }
        }
    }
    val libraryItems = remember(matchedSystemButtons, filteredApps) {
        matchedSystemButtons + filteredApps
    }
    val chunkedItems = remember(libraryItems, columns) {
        libraryItems.chunked(columns)
    }
    val positionEdge by viewModel.positionEdge.collectAsStateWithLifecycle()
    val orientation by viewModel.orientation.collectAsStateWithLifecycle()
    val opacity by viewModel.opacity.collectAsStateWithLifecycle()
    val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
    val isServiceEnabled by viewModel.isServiceEnabled.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val overlapNavBar by viewModel.overlapNavBar.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current

    // Observe permission states dynamically when we return to app using lifecycle events
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = PermissionUtils.canDrawOverlaysCompat(context)
                isAccessibilityActive = TaskbarAccessibilityService.isServiceRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // App Title Banner
        item {
            HeaderSection(
                isServiceRunning = isServiceEnabled && hasOverlayPermission,
                onServiceToggleClicked = { enabled ->
                    val currentPermission = PermissionUtils.canDrawOverlaysCompat(context)
                    if (enabled) {
                        viewModel.toggleServiceState(true)
                        if (!currentPermission) {
                            // Prompt overlay permission
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    } else {
                        viewModel.toggleServiceState(false)
                    }
                }
            )
        }

        // Unified Permissions & Services Banner Card
        item {
            CombinedPermissionsCard(
                hasOverlayPermission = hasOverlayPermission,
                isAccessibilityActive = isAccessibilityActive,
                onRequestOverlayPermission = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                onRequestAccessibilityPermission = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )
        }

        // Pinned Apps Tray Builder (Horizontal Carousel)
        item {
            PinnedAppsSection(
                pinnedApps = pinnedApps,
                onMoveUp = { viewModel.movePinnedAppUp(it) },
                onMoveDown = { viewModel.movePinnedAppDown(it) },
                onUnpin = { appInfo ->
                    viewModel.togglePinApp(
                        AppInfo(packageName = appInfo.packageName, appName = appInfo.appName, launcherActivity = ""),
                        true
                    )
                }
            )
        }

        // Configuration Options Screen
        item {
            CustomizationSection(
                positionEdge = positionEdge,
                orientation = orientation,
                opacity = opacity,
                themePreset = themePreset,
                pinnedApps = pinnedApps,
                overlapNavBar = overlapNavBar,
                onPositionChange = { viewModel.updatePositionEdge(it) },
                onOrientationChange = { viewModel.updateOrientation(it) },
                onOpacityChange = { viewModel.updateOpacity(it) },
                onThemePresetChange = { viewModel.updateThemePreset(it) },
                onOverlapNavBarChange = { viewModel.updateOverlapNavBar(it) }
            )
        }

        // Installed Applications search section
        item {
            Text(
                text = "Application Library",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose apps and system buttons to pin to your always-visible floating bar",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        // Search text-field
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_input"),
                placeholder = { Text("Search apps & system buttons...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            )
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else if (libraryItems.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No compatible applications or buttons found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            items(chunkedItems) { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (i in 0 until columns) {
                        val item = rowItems.getOrNull(i)
                        if (item != null) {
                            val isPinned = remember(pinnedApps, item.packageName) {
                                pinnedApps.any { it.packageName == item.packageName }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                            ) {
                                AppGridItem(
                                    app = item,
                                    isPinned = isPinned,
                                    onToggle = {
                                        val maxAllowed = 20
                                        if (!isPinned && pinnedApps.size >= maxAllowed) {
                                            android.widget.Toast.makeText(context, "Maximum $maxAllowed items allowed", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.togglePinApp(item, isPinned)
                                        }
                                    }
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderSection(
    isServiceRunning: Boolean,
    onServiceToggleClicked: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Quick access Service",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Keep overlay running on top",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isServiceRunning,
                onCheckedChange = onServiceToggleClicked,
                modifier = Modifier.testTag("service_toggle")
            )
        }
    }
}

@Composable
fun CombinedPermissionsCard(
    hasOverlayPermission: Boolean,
    isAccessibilityActive: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit
) {
    val allGranted = hasOverlayPermission && isAccessibilityActive
    val isWarning = !hasOverlayPermission || !isAccessibilityActive

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.VerifiedUser else Icons.Default.Shield,
                    contentDescription = "System Permissions",
                    tint = if (allGranted) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                )
                Column {
                    Text(
                        text = "App Permissions & Services",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (allGranted) "All required permissions are active" else "Grant permissions below for taskbar features",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

            // 1. Overlay Permission Item
            PermissionStatusRow(
                title = "Display Over Other Apps",
                description = "Required to keep floating taskbar visible across all apps.",
                isGranted = hasOverlayPermission,
                buttonText = "Grant Overlay Permission",
                testTag = "grant_overlay_button",
                onRequestPermission = onRequestOverlayPermission
            )

            // 2. Accessibility Service Item
            PermissionStatusRow(
                title = "Quick Access Helper (Accessibility)",
                description = "Required for Back, Home, and gesture navigation controls.",
                isGranted = isAccessibilityActive,
                buttonText = "Enable Accessibility",
                testTag = "grant_accessibility_button",
                onRequestPermission = onRequestAccessibilityPermission
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    testTag: String,
    onRequestPermission: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = if (isGranted) Color(0xFFE2F6EA) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isGranted) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isGranted) Color(0xFF065F46) else MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Text(
                    text = if (isGranted) "Active" else "Action Needed",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isGranted) Color(0xFF047857) else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isGranted) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Text(
                text = description,
                fontSize = 11.sp,
                color = if (isGranted) Color(0xFF065F46).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )

            if (!isGranted) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag(testTag),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun PinnedAppsSection(
    pinnedApps: List<PinnedApp>,
    onMoveUp: (PinnedApp) -> Unit,
    onMoveDown: (PinnedApp) -> Unit,
    onUnpin: (PinnedApp) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Current Pinned Layout (${pinnedApps.size}/20)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.LinearScale,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pinnedApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No apps pinned yet. Find apps below!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(pinnedApps) { app ->
                        PinnedAppCard(
                            app = app,
                            onMoveLeft = { onMoveUp(app) },
                            onMoveRight = { onMoveDown(app) },
                            onRemove = { onUnpin(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PinnedAppCard(
    app: PinnedApp,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val isSys = app.packageName.startsWith("sys:")
    val bitmap = rememberAppIcon(context, app.packageName)
    val sysIcon = remember(app.packageName) { if (isSys) getSystemButtonIcon(app.packageName) else null }

    Card(
        modifier = Modifier
            .width(104.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Delete badge at top-right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Move Left",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onMoveLeft)
                )

                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Remove item",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onRemove)
                )

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Move Right",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onMoveRight)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (sysIcon != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = sysIcon,
                            contentDescription = app.appName,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = "Default app",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Text(
                text = app.appName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun CustomizationSection(
    positionEdge: String,
    orientation: String,
    opacity: Float,
    themePreset: String,
    pinnedApps: List<PinnedApp>,
    overlapNavBar: Boolean,
    onPositionChange: (String) -> Unit,
    onOrientationChange: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onThemePresetChange: (String) -> Unit,
    onOverlapNavBarChange: (Boolean) -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Quick access Stylist",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // positioning edge selector (Left vs Right Edge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Placement Screen Edge",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("left" to "Left Edge", "right" to "Right Edge").forEach { (valStr, displayStr) ->
                        val isSelected = positionEdge == valStr
                        Button(
                            onClick = {
                                onPositionChange(valStr)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(displayStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }

            // Orientation Selector (Vertical vs Horizontal)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Layout Style",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("vertical" to "Vertical", "horizontal" to "Horizontal").forEach { (valStr, displayStr) ->
                        val isSelected = orientation == valStr
                        Button(
                            onClick = { onOrientationChange(valStr) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(displayStr, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Opacity slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bar Opacity (Transparency)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(opacity * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0.0f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Theme Preset selections
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Aesthetic Theme Profile",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                        Triple("glass", "Glassmorphic", Color(0xFF8AB4F8)),
                        Triple("neon", "Cyber Neon", Color(0xFF00FFCC)),
                        Triple("carbon", "Carbon Red", Color(0xFFE94560)),
                        Triple("warm", "Amber Glow", Color(0xFFFFB037)),
                        Triple("black", "OLED Black", Color(0xFFFFFFFF)),
                        Triple("white", "OLED White", Color(0xFF000000))
                    )

                    // First row: glass, neon, carbon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.take(3).forEach { (key, display, color) ->
                            val isSelected = themePreset == key
                            val selectColor = if (key == "white") MaterialTheme.colorScheme.primary else color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) selectColor.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) selectColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onThemePresetChange(key) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = display,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        color = if (isSelected) selectColor else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Second row: warm, black, white
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.drop(3).forEach { (key, display, color) ->
                            val isSelected = themePreset == key
                            val selectColor = if (key == "white") MaterialTheme.colorScheme.primary else color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) selectColor.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) selectColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onThemePresetChange(key) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = display,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        color = if (isSelected) selectColor else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getSystemButtonIcon(packageName: String): ImageVector {
    return when (packageName) {
        "sys:hide" -> Icons.Default.VisibilityOff
        "sys:back" -> Icons.Default.ArrowBack
        "sys:drag" -> Icons.Default.DragHandle
        "sys:search" -> Icons.Default.Search
        "sys:home" -> Icons.Default.Home
        else -> Icons.Default.Settings
    }
}

@Composable
fun AppGridItem(
    app: AppInfo,
    isPinned: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val isSys = app.packageName.startsWith("sys:")
    val bitmap = rememberAppIcon(context, app.packageName)
    val sysIcon = remember(app.packageName) { if (isSys) getSystemButtonIcon(app.packageName) else null }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onToggle() }
            .testTag("app_grid_item_${app.packageName}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            }
        ),
        border = if (isPinned) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            // Checkmark Badge or Add Indicator at top-right corner
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Not Pinned",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.TopEnd)
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (sysIcon != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = sysIcon,
                                contentDescription = app.appName,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = app.appName,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = "Standard icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = app.appName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}


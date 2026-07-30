package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TaskbarViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    val repository = PinnedAppRepository(database.pinnedAppDao())

    private val prefs: SharedPreferences = context.getSharedPreferences("omni_taskbar_prefs", Context.MODE_PRIVATE)

    // Flow of pinned apps from Room
    val pinnedApps: StateFlow<List<PinnedApp>> = repository.allPinnedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All launcher apps installed on phone
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // Query for filtering installed apps in UI
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Loading status
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Config preferences
    private val _positionEdge = MutableStateFlow(
        (prefs.getString("pref_taskbar_position", "right") ?: "right").let {
            if (it == "bottom") "right" else it
        }
    )
    val positionEdge: StateFlow<String> = _positionEdge.asStateFlow()

    private val _orientation = MutableStateFlow(prefs.getString("pref_taskbar_orientation", "vertical") ?: "vertical")
    val orientation: StateFlow<String> = _orientation.asStateFlow()

    private val _opacity = MutableStateFlow(prefs.getFloat("pref_taskbar_opacity", 0.85f))
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

    private val _themePreset = MutableStateFlow(prefs.getString("pref_taskbar_theme", "glass") ?: "glass")
    val themePreset: StateFlow<String> = _themePreset.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(prefs.getBoolean("pref_taskbar_enabled", false))
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _overlapNavBar = MutableStateFlow(prefs.getBoolean("pref_overlap_nav_bar", false))
    val overlapNavBar: StateFlow<Boolean> = _overlapNavBar.asStateFlow()

    // Filtered installed apps list
    val filteredApps: StateFlow<List<AppInfo>> = combine(_installedApps, _searchQuery) { apps, query ->
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadInstalledApps()
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getPinnedAppsDirect()
            if (existing.isEmpty()) {
                val defaults = listOf(
                    PinnedApp("sys:hide", "Hide Button", 0),
                    PinnedApp("sys:back", "Back Button", 1),
                    PinnedApp("sys:drag", "Drag Handle", 2),
                    PinnedApp("sys:search", "Search Button", 3),
                    PinnedApp("sys:home", "Home Button", 4)
                )
                defaults.forEach { repository.insert(it) }
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val pm = context.packageManager
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
                _installedApps.value = apps
                // Preload icons asynchronously in background to ensure zero scrolling lag
                viewModelScope.launch(Dispatchers.IO) {
                    IconCacheManager.preloadIcons(context, apps.map { it.packageName })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Toggle Pin/Unpin
    fun togglePinApp(app: AppInfo, isCurrentlyPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isCurrentlyPinned) {
                repository.deleteByPackageName(app.packageName)
            } else {
                val currentSize = repository.getPinnedAppsDirect().size
                repository.insert(
                    PinnedApp(
                        packageName = app.packageName,
                        appName = app.appName,
                        orderIndex = currentSize
                    )
                )
            }
            // Trigger refresh in service if running
            notifyServiceOfChange()
        }
    }

    fun movePinnedAppUp(app: PinnedApp) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = repository.getPinnedAppsDirect().toMutableList()
            val index = apps.indexOfFirst { it.packageName == app.packageName }
            if (index > 0) {
                val temp = apps[index]
                apps[index] = apps[index - 1].copy(orderIndex = index)
                apps[index - 1] = temp.copy(orderIndex = index - 1)

                repository.clear()
                apps.forEach { repository.insert(it) }
                notifyServiceOfChange()
            }
        }
    }

    fun movePinnedAppDown(app: PinnedApp) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = repository.getPinnedAppsDirect().toMutableList()
            val index = apps.indexOfFirst { it.packageName == app.packageName }
            if (index >= 0 && index < apps.size - 1) {
                val temp = apps[index]
                apps[index] = apps[index + 1].copy(orderIndex = index)
                apps[index + 1] = temp.copy(orderIndex = index + 1)

                repository.clear()
                apps.forEach { repository.insert(it) }
                notifyServiceOfChange()
            }
        }
    }

    // Preference customization
    fun updatePositionEdge(edge: String) {
        _positionEdge.value = edge
        prefs.edit().putString("pref_taskbar_position", edge).apply()
        notifyServiceOfChange()
    }

    fun updateOrientation(value: String) {
        _orientation.value = value
        prefs.edit().putString("pref_taskbar_orientation", value).apply()
        notifyServiceOfChange()
    }

    fun updateOpacity(value: Float) {
        _opacity.value = value
        prefs.edit().putFloat("pref_taskbar_opacity", value).apply()
        notifyServiceOfChange()
    }

    fun updateThemePreset(preset: String) {
        _themePreset.value = preset
        prefs.edit().putString("pref_taskbar_theme", preset).apply()
        notifyServiceOfChange()
    }

    fun updateOverlapNavBar(enabled: Boolean) {
        _overlapNavBar.value = enabled
        prefs.edit().putBoolean("pref_overlap_nav_bar", enabled).apply()
        notifyServiceOfChange()
    }

    fun toggleServiceState(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.edit().putBoolean("pref_taskbar_enabled", enabled).apply()
        if (enabled) {
            startTaskbarService()
        } else {
            stopTaskbarService()
        }
    }

    fun restorePosition() {
        // Clear saved offset Y if desired, to recenter
        prefs.edit().remove("pref_taskbar_offset_y").apply()
        notifyServiceOfChange()
    }

    private fun startTaskbarService() {
        if (!PermissionUtils.canDrawOverlaysCompat(context)) return
        try {
            val serviceIntent = Intent(context, TaskbarService::class.java).apply {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val displays = displayManager.displays
                val secondaryDisplay = displays.firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY }
                if (secondaryDisplay != null) {
                    putExtra("display_id", secondaryDisplay.displayId)
                }
            }
            context.startService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopTaskbarService() {
        try {
            val serviceIntent = Intent(context, TaskbarService::class.java)
            context.stopService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyServiceOfChange() {
        if (!_isServiceEnabled.value || !PermissionUtils.canDrawOverlaysCompat(context)) return
        try {
            val intent = Intent(context, TaskbarService::class.java).apply {
                action = "ACTION_REFRESH_CONFIG"
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val displays = displayManager.displays
                val secondaryDisplay = displays.firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY }
                if (secondaryDisplay != null) {
                    putExtra("display_id", secondaryDisplay.displayId)
                }
            }
            context.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

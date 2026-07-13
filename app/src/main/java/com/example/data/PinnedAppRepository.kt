package com.example.data

import kotlinx.coroutines.flow.Flow

class PinnedAppRepository(private val pinnedAppDao: PinnedAppDao) {
    val allPinnedApps: Flow<List<PinnedApp>> = pinnedAppDao.getAllPinnedAppsFlow()

    suspend fun getPinnedAppsDirect(): List<PinnedApp> = pinnedAppDao.getAllPinnedAppsDirect()

    suspend fun insert(app: PinnedApp) = pinnedAppDao.insertPinnedApp(app)

    suspend fun delete(app: PinnedApp) = pinnedAppDao.deletePinnedApp(app)

    suspend fun deleteByPackageName(packageName: String) = pinnedAppDao.deleteByPackageName(packageName)

    suspend fun clear() = pinnedAppDao.clearAll()
}

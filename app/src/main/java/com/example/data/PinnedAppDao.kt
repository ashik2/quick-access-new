package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedAppDao {
    @Query("SELECT * FROM pinned_apps ORDER BY orderIndex ASC")
    fun getAllPinnedAppsFlow(): Flow<List<PinnedApp>>

    @Query("SELECT * FROM pinned_apps ORDER BY orderIndex ASC")
    suspend fun getAllPinnedAppsDirect(): List<PinnedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPinnedApp(app: PinnedApp)

    @Delete
    suspend fun deletePinnedApp(app: PinnedApp)

    @Query("DELETE FROM pinned_apps WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("DELETE FROM pinned_apps")
    suspend fun clearAll()
}

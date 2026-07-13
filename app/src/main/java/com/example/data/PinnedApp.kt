package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pinned_apps")
data class PinnedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val orderIndex: Int
)

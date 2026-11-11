package io.github.kylin33.aifitgym.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 简单的键值表，用来记录内置数据包的版本，避免每次启动都重导。 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

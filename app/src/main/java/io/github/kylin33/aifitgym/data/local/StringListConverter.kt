package io.github.kylin33.aifitgym.data.local

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * 字符串列表用 JSON 文本存一列：动作的步骤、次要肌群都是短列表，
 * 独立建表只会让查询变复杂，收益不大。
 */
class StringListConverter {

    @TypeConverter
    fun fromList(value: List<String>): String {
        if (value.isEmpty()) return "[]"
        val array = JSONArray()
        value.forEach { array.put(it) }
        return array.toString()
    }

    @TypeConverter
    fun toList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList(array.length()) {
                for (i in 0 until array.length()) add(array.optString(i))
            }
        }.getOrDefault(emptyList())
    }
}

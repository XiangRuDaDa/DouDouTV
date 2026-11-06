package com.chen.doudoutv.data.db

import androidx.room.TypeConverter
import com.chen.doudoutv.data.model.SourceKind

class Converters {

    @TypeConverter
    fun fromKind(kind: SourceKind): String = kind.name

    @TypeConverter
    fun toKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.TVBOX)
}

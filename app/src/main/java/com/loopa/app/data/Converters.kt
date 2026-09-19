package com.loopa.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun sourceToString(v: Source): String = v.name
    @TypeConverter fun stringToSource(v: String): Source = Source.valueOf(v)

    @TypeConverter fun repeatToString(v: RepeatMode): String = v.name
    @TypeConverter fun stringToRepeat(v: String): RepeatMode = RepeatMode.valueOf(v)
}

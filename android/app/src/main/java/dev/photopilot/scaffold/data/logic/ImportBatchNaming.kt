package dev.photopilot.scaffold.data.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ImportBatchNaming {

    fun nameOf(createdAtMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .apply { this.timeZone = timeZone }
            .format(Date(createdAtMillis))

    fun sortKeyOf(createdAtMillis: Long): String =
        createdAtMillis.toString().padStart(20, '0')
}

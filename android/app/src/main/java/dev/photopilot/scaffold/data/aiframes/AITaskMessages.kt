package dev.photopilot.scaffold.data.aiframes

fun aiPauseExplanation(reason: String?, chinese: Boolean): String = when (reason) {
    "user" -> if (chinese) "已手动暂停，明确继续后才会恢复。" else "Paused by you. Choose Continue to resume."
    "low_battery" -> if (chinese) "电量不足 15%，连接电源或提高电量后可继续。" else "Battery is at or below 15%. Connect power or recharge to continue."
    "temperature" -> if (chinese) "设备温度过高，降温后可继续。" else "The device is too hot. Continue after it cools."
    "timeout" -> if (chinese) "Android 后台运行时间已到，回到应用后可继续。" else "Android's background time limit was reached. Reopen the app to continue."
    "memory" -> if (chinese) "设备内存紧张，释放内存后可继续。" else "The device is low on memory. Free memory before continuing."
    "background" -> if (chinese) "系统已停止后台运行，回到应用后可继续。" else "Background execution stopped. Reopen the app to continue."
    "network" -> if (chinese) "等待网络连接，联网后可继续。" else "Waiting for a network connection. Continue when connected."
    "wifi_required" -> if (chinese) "等待非计费网络，也可在模型管理中允许移动网络。" else "Waiting for an unmetered network. You can allow metered use in model management."
    "description_too_long" -> if (chinese) "描述过长，请缩短描述或选择标准模型。" else "The description is too long. Shorten it or choose the standard model."
    "content_changed" -> if (chinese) "照片或配置已更新，请继续或重新预览。" else "Photos or configuration changed. Continue or preview again."
    "device_unsupported" -> if (chinese) "设备无法运行此模型，请选择兼容模型。" else "This device cannot run this model. Choose a compatible model."
    "low_storage" -> if (chinese) "可用存储不足，释放空间后可继续。" else "Not enough free storage. Free space and continue."
    else -> if (chinese) "任务已中断，可从已保存的进度继续。" else "The task was interrupted. Continue from saved progress."
}

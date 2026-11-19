package dev.photopilot.scaffold.data.aiframes

import android.app.ActivityManager
import android.content.Context

object AIMemoryPolicy {
    const val GENERATION_PHYSICAL_FLOOR: Long = 4_294_967_296
    const val CLASSIFICATION_PHYSICAL_FLOOR: Long = 1_610_612_736
    const val GENERATION_BUDGET: Long = 1_500_000_000
    const val CLASSIFICATION_BUDGET: Long = 550_000_000
    const val MINIMUM_WHILE_RUNNING: Long = 134_217_728

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info
    }

    fun physicalBytes(context: Context): Long = memoryInfo(context).totalMem

    fun availableBytes(context: Context): Long = memoryInfo(context).availMem

    fun supportsClassification(context: Context): Boolean = physicalBytes(context) >= CLASSIFICATION_PHYSICAL_FLOOR

    fun supportsGeneration(context: Context): Boolean = physicalBytes(context) >= GENERATION_PHYSICAL_FLOOR

    fun supportsAny(context: Context): Boolean = supportsClassification(context) || supportsGeneration(context)

    fun budget(runtime: String): Long = if (runtime == "litertlm") GENERATION_BUDGET else CLASSIFICATION_BUDGET

    fun supportsRuntime(context: Context, runtime: String): Boolean = when (runtime) {
        "litertlm" -> supportsGeneration(context)
        "onnx" -> supportsClassification(context)
        else -> true
    }

    fun canLoad(context: Context, runtime: String): Boolean {
        if (!supportsRuntime(context, runtime)) return false
        return availableBytes(context) >= budget(runtime)
    }
}

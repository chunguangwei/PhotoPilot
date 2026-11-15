package dev.photopilot.scaffold

import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.ui.nas.NasFormView
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class NasStorageLayoutTest {
    @Test fun storageLabelsWrapAndStayTouchableInBothThemesAndLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) for (font in listOf(1f, 2f)) for (night in listOf(false, true)) for (widthDp in listOf(320, 432)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(language)))
                    fontScale = font
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_PhotoPilot).apply { applyOverrideConfiguration(config) }
                val form = NasFormView(context)
                val width = (widthDp * context.resources.displayMetrics.density).toInt()
                form.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                form.layout(0, 0, width, 2400)
                assertTrue(form.currentForm()?.catalogOnly == true)
                val options = descendants(form).filterIsInstance<RadioButton>()
                assertEquals(2, options.size)
                for (option in options) {
                    assertTrue(option.height >= context.resources.getDimensionPixelSize(R.dimen.pp_touch_target))
                    val layout = requireNotNull(option.layout)
                    assertTrue(layout.getLineBottom(layout.lineCount - 1) <= option.height - option.compoundPaddingTop - option.compoundPaddingBottom)
                    assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                    val text = android.graphics.Color.luminance(option.currentTextColor)
                    val background = android.graphics.Color.luminance(context.getColor(R.color.pp_surface_canvas))
                    assertTrue((maxOf(text, background) + 0.05) / (minOf(text, background) + 0.05) >= 4.5)
                }
                options.last().performClick()
                assertFalse(form.currentForm()?.catalogOnly == true)
                form.setCatalogOnly(true)
                assertTrue(form.currentForm()?.catalogOnly == true)
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

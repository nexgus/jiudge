package io.github.nexgus.jiudge.feature.identify

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a theme symbol SVG (`<themeDir>/moiosmhs_res/<file>`) to an [ImageBitmap] for the legend.
 * These are the very files RudyMap's render theme draws on the map, so the legend always matches the
 * live map and nothing is bundled or duplicated. AndroidSVG is pulled in transitively by
 * mapsforge-themes (see app/build.gradle.kts) - do not add it explicitly or its classes collide.
 */
object SvgSymbol {
    const val RES_DIR = "moiosmhs_res"

    fun renderToBitmap(
        themeDir: File,
        file: String,
        sizePx: Int,
    ): ImageBitmap? {
        if (sizePx <= 0) return null
        val svgFile = File(File(themeDir, RES_DIR), file)
        if (!svgFile.isFile) return null
        return try {
            val svg = svgFile.inputStream().use { SVG.getFromInputStream(it) }
            // Some symbols declare width/height but no viewBox; give them one so the render scales.
            if (svg.documentViewBox == null) {
                val w = svg.documentWidth
                val h = svg.documentHeight
                if (w > 0f && h > 0f) svg.setDocumentViewBox(0f, 0f, w, h)
            }
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val options = RenderOptions.create().viewPort(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
            svg.renderToCanvas(Canvas(bitmap), options)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            // A missing or malformed symbol must not crash the legend; the row falls back to text.
            null
        }
    }
}

/** Renders [file] off the main thread, returning null until ready (or if the symbol is unavailable). */
@Composable
fun rememberSymbol(
    themeDir: File,
    file: String,
    sizePx: Int,
): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, themeDir, file, sizePx) {
        value = withContext(Dispatchers.Default) { SvgSymbol.renderToBitmap(themeDir, file, sizePx) }
    }

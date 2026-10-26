package ai.opencode.feature.drawer.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * iOS `ProjectIconTests`' rendering tests (`rasterizesSVGAndClassifiesFullBleedAndTransparentGlyphs`,
 * `composerWrapperRendersItsLayerAndBackground`, `embeddedImagesStayLocalAndBounded`), apart from
 * `ProjectIconTests.kt` because they need real Android pixels: Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectIconRenderTests {

    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun fill(canvas: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) =
        canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun rasterizesSVGAndClassifiesFullBleedAndTransparentGlyphs() {
        // 1. Full-bleed OpenCode SVG -> fullBleed.
        val openCodeSVG = """
            data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
            <rect width="512" height="512" fill="#131010"/>
            <path d="M320 224V352H192V224H320Z" fill="#5A5858"/>
            <path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/>
            </svg>
        """.trimIndent()
        val fullBleed = ProjectIcon.fromDataURL(openCodeSVG)
        assertNotNull(fullBleed)
        assertEquals(ProjectIcon.Backing.FullBleed, fullBleed!!.backing)
        assertEquals(ProjectIcon.maximumPixels, fullBleed.bitmap.width)

        // 2. Transparent light glyph with offset viewBox (`opencode-ios` logo) -> paddedDark.
        val transparentLightSVG = """
            data:image/svg+xml;utf8,<svg width="820" height="820" viewBox="46 46 420 420" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M320 224V352H192V224H320Z" fill="#5A5858"/>
            <path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/>
            </svg>
        """.trimIndent()
        assertEquals(ProjectIcon.Backing.PaddedDark, ProjectIcon.fromDataURL(transparentLightSVG)?.backing)

        // 3. Transparent dark glyph -> paddedLight.
        val transparentDarkSVG = """
            data:image/svg+xml;utf8,<svg viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path fill-rule="evenodd" clip-rule="evenodd" d="M48 52H16V12H48V52ZM40 20H24V44H40V20Z" fill="#111111"/>
            </svg>
        """.trimIndent()
        assertEquals(ProjectIcon.Backing.PaddedLight, ProjectIcon.fromDataURL(transparentDarkSVG)?.backing)

        // 4. Flat single-color or empty image is rejected so the fallback initial renders instead.
        val flatSVG = """data:image/svg+xml;utf8,<svg viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg"><rect width="32" height="32" fill="#111111"/></svg>"""
        assertNull(ProjectIcon.fromDataURL(flatSVG))
        assertNull(ProjectIcon.decode("not an image".toByteArray()))

        // 5. Non-finite or huge SVG numbers and oversized radii never trap.
        val extremeSVG = """
            data:image/svg+xml;utf8,<svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
            <rect x="0" y="0" width="64" height="64" rx="999999" ry="1e999" fill="#0b0c0e"/>
            <circle cx="1e999" cy="NaN" r="1e999" fill="#ffffff"/>
            <path d="M 16 16 L 48 16 L 1e999 48 L 48 48 Z" stroke="#ffffff" stroke-width="6"/>
            </svg>
        """.trimIndent()
        assertNotNull(ProjectIcon.fromDataURL(extremeSVG))

        // 6. Rasters larger than maximumPixels are thumbnailed to it; > 4096 is rejected.
        val png256 = png(256, 256) { canvas ->
            fill(canvas, Color.rgb(26, 51, 77), 0f, 0f, 256f, 256f)
            fill(canvas, Color.WHITE, 64f, 64f, 192f, 192f)
        }
        val thumbnailed = ProjectIcon.decode(png256)
        assertNotNull(thumbnailed)
        assertEquals(ProjectIcon.maximumPixels, thumbnailed!!.bitmap.width)
        assertEquals(ProjectIcon.maximumPixels, thumbnailed.bitmap.height)

        // Patch PNG IHDR width/height (bytes 16..23) to 8192×8192 (> maximumSourceDimension 4096).
        val oversized = png256.copyOf()
        byteArrayOf(0, 0, 0x20, 0, 0, 0, 0x20, 0).copyInto(oversized, 16)
        assertNull(ProjectIcon.decode(oversized))
    }

    @Test
    fun composerWrapperRendersItsLayerAndBackground() {
        // Exact layer and wrapper shape emitted for opencode-ios by core PR #57671.
        val layer = """<svg width="820" height="820" viewBox="46 46 420 420" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M320 224V352H192V224H320Z" fill="#5A5858"/><path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/></svg>"""
        val source = "data:image/svg+xml;base64," + b64(layer.toByteArray())
        for (background in listOf(
            """<rect width="128" height="128" rx="28" fill="color(display-p3 0.075 0.063 0.063 / 1)"/>""",
            """<defs><linearGradient id="bg" x2="0" y2="1"><stop offset="0" stop-color="color(srgb 1 0 0 / 1)"/><stop offset="1" stop-color="color(srgb 0 0 1 / 1)"/></linearGradient></defs><rect width="128" height="128" rx="28" fill="url(#bg)"/>""",
        )) {
            val data = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">$background<image width="128" height="128" href="$source"/></svg>""".toByteArray()
            val image = ProjectSVGRasterizer.rasterize(data, 96)
            assertNotNull(image)
            val pixels = IntArray(96 * 96).also { image!!.getPixels(it, 0, 96, 0, 0, 96, 96) }
            // The white logo must survive, not just its flat or gradient backdrop.
            val white = pixels.count { Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 }
            assertTrue("white pixels $white", white > 500)
            if (background.contains("linearGradient")) {
                // Mid-edge samples outside the logo prove both gradient stops are applied.
                val top = pixels[2 * 96 + 48]
                val bottom = pixels[93 * 96 + 48]
                assertTrue(Color.red(top) > Color.blue(top))
                assertTrue(Color.blue(bottom) > Color.red(bottom))
            }
            assertNotNull(ProjectIcon.decode(data))
        }
    }

    @Test
    fun embeddedImagesStayLocalAndBounded() {
        fun wrapper(source: String) = """<svg viewBox="0 0 128 128"><image width="128" height="128" href="$source"/></svg>""".toByteArray()
        for (source in listOf("https://example.com/logo.svg", "file:///etc/passwd", "data:image/svg+xml;base64,invalid")) {
            assertNull(source, ProjectSVGRasterizer.rasterize(wrapper(source), 96))
        }
        var data = """<svg viewBox="0 0 128 128"><rect width="64" height="128" fill="white"/></svg>""".toByteArray()
        repeat(3) { data = wrapper("data:image/svg+xml;base64," + b64(data)) }
        assertNull(ProjectSVGRasterizer.rasterize(data, 96))
        assertNull(ProjectSVGPaint.color("color(srgb nan 0 0 / 1)"))
        assertNull(ProjectSVGPaint.color("color(display-p3 2 0 0 / 1)"))

        // An asymmetric embedded bitmap catches a vertically flipped <image>.
        val png = png(32, 32) { canvas ->
            fill(canvas, Color.RED, 0f, 0f, 32f, 16f)
            fill(canvas, Color.BLUE, 0f, 16f, 32f, 32f)
        }
        val image = ProjectSVGRasterizer.rasterize(wrapper("data:image/png;base64," + b64(png)), 96)
        assertNotNull(image)
        assertTrue(Color.red(image!!.getPixel(48, 10)) > 240)
        assertTrue(Color.blue(image.getPixel(48, 85)) > 240)
    }
}

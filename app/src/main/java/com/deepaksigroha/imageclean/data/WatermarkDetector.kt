package com.deepaksigroha.imageclean.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WatermarkRegion(
    val rect: RectF,          // normalised 0..1 coordinates
    val label: String,
    val confidence: Float,    // 0..1
    val aiLogo: Boolean,
)

data class WatermarkResult(val regions: List<WatermarkRegion>, val notes: List<String>)

/**
 * Heuristic visible-watermark detector (fully offline, no ML model).
 *  1. Known AI marks: DALL·E 2 colour bar; bright four-point "sparkle" marks in a corner (Gemini-style).
 *  2. Overlay text/logos: cells near edges and corners whose edge density and contrast are much higher
 *     than the image's typical texture, forming compact clusters (typical of stamped text/logos).
 * Results are suggestions for a human to inspect – they are not proof of a watermark.
 */
object WatermarkDetector {

    private const val WORK = 768
    private const val CELL = 16

    fun detect(src: Bitmap): WatermarkResult {
        val scale = min(1f, WORK.toFloat() / max(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()), true) else src
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()

        val regions = ArrayList<WatermarkRegion>()
        dalleBar(px, w, h)?.let { regions += it }
        regions += sparkleMarks(px, w, h)
        regions += textLikeOverlays(px, w, h).filter { r -> regions.none { overlap(it.rect, r.rect) > 0.3f } }

        val notes = listOf(
            "Detection is heuristic and works best on corner and edge marks. Faint, tiled or invisible watermarks (e.g. SynthID) cannot be detected.",
            "This app never removes copyright notices or attribution automatically.",
        )
        return WatermarkResult(regions.sortedByDescending { it.confidence }.take(8), notes)
    }

    // ---------------------------------------------------------------- DALL·E colour bar

    private fun dalleBar(px: IntArray, w: Int, h: Int): WatermarkRegion? {
        // Five adjacent squares (yellow, cyan, green, red, blue) in the bottom-right corner.
        val targets = intArrayOf(Color.rgb(255, 255, 102), Color.rgb(66, 255, 255), Color.rgb(81, 218, 76), Color.rgb(255, 110, 60), Color.rgb(60, 70, 255))
        for (sq in listOf(w / 64, w / 80, w / 100, w / 48)) {
            if (sq < 2) continue
            val y = h - sq / 2 - 1
            var ok = 0
            for (k in 0 until 5) {
                val x = w - (5 - k) * sq + sq / 2
                if (x < 0 || y < 0) break
                if (colorDist(px[y * w + x], targets[k]) < 90) ok++
            }
            if (ok >= 4) {
                return WatermarkRegion(RectF((w - 5f * sq) / w, (h - sq.toFloat()) / h, 1f, 1f),
                    "Possible DALL·E signature colour bar", 0.6f + 0.08f * ok, aiLogo = true)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- sparkle marks

    private fun sparkleMarks(px: IntArray, w: Int, h: Int): List<WatermarkRegion> {
        val out = ArrayList<WatermarkRegion>()
        val box = max(12, min(w, h) / 10)
        val corners = listOf(w - box to h - box, 0 to h - box, w - box to 0, 0 to 0)
        for ((cx, cy) in corners) {
            // brightness statistics of the corner patch
            var sum = 0.0; var n = 0
            for (y in cy until cy + box) for (x in cx until cx + box) { sum += luma(px[y * w + x]); n++ }
            val mean = sum / n
            var bright = 0; var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            for (y in cy until cy + box) for (x in cx until cx + box) {
                val l = luma(px[y * w + x]); val c = px[y * w + x]
                val sat = max(Color.red(c), max(Color.green(c), Color.blue(c))) - min(Color.red(c), min(Color.green(c), Color.blue(c)))
                if (l > mean + 60 && l > 200 && sat < 60) { bright++; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y) }
            }
            if (bright < 8 || maxX < 0) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val fill = bright.toFloat() / (bw * bh)
            val square = min(bw, bh).toFloat() / max(bw, bh)
            // A 4-point star fills ~25-55% of its bounding box and is roughly square; small relative to the corner.
            if (square > 0.7f && fill in 0.2f..0.6f && bw < box * 0.6f && bw >= 4) {
                val cxm = (minX + maxX) / 2; val cym = (minY + maxY) / 2
                val crossArm = luma(px[cym * w + minX]) + luma(px[cym * w + maxX]) + luma(px[minY * w + cxm]) + luma(px[maxY * w + cxm])
                val diag = luma(px[minY * w + minX]) + luma(px[minY * w + maxX]) + luma(px[maxY * w + minX]) + luma(px[maxY * w + maxX])
                if (crossArm > diag + 80) {
                    out += WatermarkRegion(RectF(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1f) / w, (maxY + 1f) / h),
                        "Possible AI sparkle logo (e.g. Gemini-style mark)", 0.55f, aiLogo = true)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- text / logo overlays

    private fun textLikeOverlays(px: IntArray, w: Int, h: Int): List<WatermarkRegion> {
        val gx = w / CELL; val gy = h / CELL
        if (gx < 4 || gy < 4) return emptyList()
        val lum = FloatArray(w * h) { luma(px[it]) }
        val edge = FloatArray(gx * gy)
        val contrast = FloatArray(gx * gy)
        for (cy in 0 until gy) for (cx in 0 until gx) {
            var e = 0f; var lo = 255f; var hi = 0f
            for (y in cy * CELL + 1 until (cy + 1) * CELL - 1) for (x in cx * CELL + 1 until (cx + 1) * CELL - 1) {
                val i = y * w + x
                val sx = (lum[i + 1] - lum[i - 1]); val sy = (lum[i + w] - lum[i - w])
                val m = sqrt(sx * sx + sy * sy)
                if (m > 60) e++
                lo = min(lo, lum[i]); hi = max(hi, lum[i])
            }
            edge[cy * gx + cx] = e / ((CELL - 2) * (CELL - 2))
            contrast[cy * gx + cx] = hi - lo
        }
        val sorted = edge.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9) / 10]
        val thr = max(0.08f, max(median * 3f, p90))
        val flag = BooleanArray(gx * gy) { edge[it] >= thr && contrast[it] > 90 }

        // only keep cells in the outer 22% band or the central 30% (common watermark placements)
        for (cy in 0 until gy) for (cx in 0 until gx) {
            val fx = (cx + 0.5f) / gx; val fy = (cy + 0.5f) / gy
            val border = fx < 0.22f || fx > 0.78f || fy < 0.22f || fy > 0.78f
            val center = abs(fx - 0.5f) < 0.15f && abs(fy - 0.5f) < 0.15f
            if (!border && !center) flag[cy * gx + cx] = false
        }
        // connected components
        val seen = BooleanArray(gx * gy)
        val out = ArrayList<WatermarkRegion>()
        for (start in flag.indices) {
            if (!flag[start] || seen[start]) continue
            val stack = ArrayDeque<Int>(); stack.add(start); seen[start] = true
            var minX = gx; var minY = gy; var maxX = 0; var maxY = 0; var count = 0
            while (stack.isNotEmpty()) {
                val c = stack.removeLast(); count++
                val x = c % gx; val y = c / gx
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to -1, 1 to -1, -1 to 1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until gx && ny in 0 until gy) {
                        val ni = ny * gx + nx
                        if (flag[ni] && !seen[ni]) { seen[ni] = true; stack.add(ni) }
                    }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val areaFrac = (bw * bh).toFloat() / (gx * gy)
            val elongated = bw >= 3 && bw.toFloat() / bh >= 2.5f // text lines are wide and short
            if (count < 3 || areaFrac > 0.12f) continue
            val inCorner = (minX < gx * 0.25f || maxX > gx * 0.75f) && (minY < gy * 0.25f || maxY > gy * 0.75f)
            var conf = 0.3f + (if (elongated) 0.25f else 0f) + (if (inCorner) 0.15f else 0f) + min(0.2f, count / 40f)
            conf = min(conf, 0.9f)
            if (conf < 0.45f) continue
            out += WatermarkRegion(
                RectF(minX.toFloat() / gx, minY.toFloat() / gy, (maxX + 1f) / gx, (maxY + 1f) / gy),
                if (elongated) "Possible text watermark / signature" else "Possible logo or stamp", conf, aiLogo = false,
            )
        }
        return out
    }

    private fun luma(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private fun colorDist(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val small = min(a.width() * a.height(), b.width() * b.height())
        return if (small <= 0f) 0f else inter / small
    }
}

package com.deepaksigroha.imageclean.data

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deepaksigroha.imageclean.ImageCleanApp
import com.deepaksigroha.imageclean.core.ai.AiVerdict
import com.deepaksigroha.imageclean.core.c2pa.C2paState
import com.deepaksigroha.imageclean.data.db.RecordEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Background batch analysis (and optional privacy cleaning) with WorkManager.
 * Progress is published through WorkInfo; results are written to the local database.
 */
class BatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val UNIQUE = "batch"
        const val KEY_URIS = "uris"
        const val KEY_BATCH = "batchId"
        const val KEY_CLEAN = "clean"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
        const val KEY_FAILED = "failed"
        const val MAX_IMAGES = 50

        fun enqueue(ctx: Context, uris: List<Uri>, batchId: String, clean: Boolean) {
            val req = OneTimeWorkRequestBuilder<BatchWorker>()
                .setInputData(workDataOf(KEY_URIS to uris.take(MAX_IMAGES).map { it.toString() }.toTypedArray(), KEY_BATCH to batchId, KEY_CLEAN to clean))
                .addTag(batchId)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)
    }

    override suspend fun doWork(): Result {
        val c = (applicationContext as ImageCleanApp).container
        val uris = inputData.getStringArray(KEY_URIS).orEmpty()
        val batch = inputData.getString(KEY_BATCH) ?: return Result.failure()
        val clean = inputData.getBoolean(KEY_CLEAN, false)
        var failed = 0
        uris.forEachIndexed { i, s ->
            currentCoroutineContext().ensureActive()
            setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to uris.size, KEY_CURRENT to s, KEY_FAILED to failed))
            val uri = Uri.parse(s)
            try {
                val a = c.analyzer.analyze(uri)
                val wm = runCatching { ImageIO.decode(a.image.bytes, 1024).let { b -> WatermarkDetector.detect(b).also { b.recycle() } } }.getOrNull()
                var out: OutputInfo? = null
                var outName: String? = null
                if (clean) {
                    val res = c.cleaner.clean(a, CleanOptions(removeTags = MetadataCleaner.SENSITIVE, removeThumbnail = true,
                        removeXmp = true, removeIptc = true, removeComments = true, removePngText = true))
                    val ext = if (res.format == com.deepaksigroha.imageclean.core.model.ImageFormat.PNG) "png" else if (res.format == com.deepaksigroha.imageclean.core.model.ImageFormat.WEBP) "webp" else "jpg"
                    outName = MediaSaver.outputName(a.name, "clean", ext)
                    val saved = MediaSaver.saveImage(applicationContext, res.bytes, outName, res.format.mime)
                    val after = runCatching { c.analyzer.analyze(LoadedImage(saved.uri, outName, res.bytes, false)) }.getOrNull()
                    out = OutputInfo("Batch privacy clean", saved.displayPath, after, res.removed, res.retained, res.modified,
                        if (res.reencoded) "Re-encoded JPEG q95" else "Lossless metadata edit (no recompression)", res.warnings)
                }
                val report = ReportGenerator.build(a, out).let { r ->
                    if (wm == null || wm.regions.isEmpty()) r
                    else r.copy(sections = r.sections + ReportSection("Visible watermark scan", wm.regions.map { it.label to "confidence ${(it.confidence * 100).toInt()}%" }))
                }
                c.db.records().insert(record(a, "BATCH", batch, out != null || !clean, outName, ReportGenerator.toJson(report).toString()))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed++
                c.db.records().insert(RecordEntity(timestamp = System.currentTimeMillis(), operation = "BATCH", batchId = batch,
                    fileName = runCatching { ImageIO.displayName(applicationContext.contentResolver, uri) }.getOrDefault(s), format = "?",
                    fileSize = 0, width = 0, height = 0, hasExif = false, hasGps = false, hasXmp = false, hasIptc = false, hasC2pa = false,
                    c2paState = C2paState.NONE.name, aiVerdict = AiVerdict.UNDETERMINED.name, processedOk = false, verificationFailed = false,
                    outputName = null, reportJson = "{\"title\":\"Error\",\"sections\":[{\"section\":\"Error\",\"rows\":[{\"field\":\"Error\",\"value\":${org.json.JSONObject.quote(e.message ?: "failed")}}]}]}"))
            }
        }
        setProgress(workDataOf(KEY_DONE to uris.size, KEY_TOTAL to uris.size, KEY_FAILED to failed))
        return Result.success(Data.Builder().putInt(KEY_DONE, uris.size).putInt(KEY_FAILED, failed).build())
    }
}

fun record(a: ImageAnalysis, op: String, batch: String?, ok: Boolean, outName: String?, json: String) = RecordEntity(
    timestamp = System.currentTimeMillis(), operation = op, batchId = batch, fileName = a.name, format = a.format.label,
    fileSize = a.size, width = a.width, height = a.height, hasExif = a.hasExif, hasGps = a.hasGps, hasXmp = a.container.hasXmp,
    hasIptc = a.container.hasIptc, hasC2pa = a.container.hasC2pa, c2paState = a.c2pa.state.name, aiVerdict = a.ai.verdict.name,
    processedOk = ok, verificationFailed = a.c2pa.state == C2paState.INVALID, outputName = outName, reportJson = json,
)

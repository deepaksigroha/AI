package com.deepaksigroha.imageclean.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deepaksigroha.imageclean.ImageCleanApp
import com.deepaksigroha.imageclean.core.ai.IgInput
import com.deepaksigroha.imageclean.core.ai.IgReport
import com.deepaksigroha.imageclean.core.ai.InstagramChecker
import com.deepaksigroha.imageclean.core.clean.JpegRewriter
import com.deepaksigroha.imageclean.core.clean.XmpBuilder
import com.deepaksigroha.imageclean.core.model.ExifCategory
import com.deepaksigroha.imageclean.core.model.ImageFormat
import com.deepaksigroha.imageclean.data.*
import com.deepaksigroha.imageclean.data.db.PresetEntity
import com.deepaksigroha.imageclean.data.db.RecordEntity
import com.deepaksigroha.imageclean.data.db.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

sealed interface Ui<out T> {
    data object Idle : Ui<Nothing>
    data object Loading : Ui<Nothing>
    data class Ready<T>(val value: T) : Ui<T>
    data class Error(val message: String) : Ui<Nothing>
}

val <T> Ui<T>.value: T? get() = (this as? Ui.Ready<T>)?.value

data class SavedOutput(
    val title: String,
    val uri: Uri,
    val path: String,
    val mime: String,
    val report: Report,
    val warnings: List<String>,
    val removed: List<String>,
    val retained: List<String>,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ImageCleanApp).container
    private val ctx get() = getApplication<Application>()

    private val _analysis = MutableStateFlow<Ui<ImageAnalysis>>(Ui.Idle)
    val analysis: StateFlow<Ui<ImageAnalysis>> = _analysis
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview
    private val _watermark = MutableStateFlow<Ui<WatermarkResult>>(Ui.Idle)
    val watermark: StateFlow<Ui<WatermarkResult>> = _watermark
    private val _instagram = MutableStateFlow<IgReport?>(null)
    val instagram: StateFlow<IgReport?> = _instagram
    private val _output = MutableStateFlow<Ui<SavedOutput>>(Ui.Idle)
    val output: StateFlow<Ui<SavedOutput>> = _output
    private val _exportPreview = MutableStateFlow<ImageBitmap?>(null)
    val exportPreview: StateFlow<ImageBitmap?> = _exportPreview
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private val _lastReport = MutableStateFlow<Report?>(null)
    val lastReport: StateFlow<Report?> = _lastReport

    val records: StateFlow<List<RecordEntity>> = c.db.records().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val stats: StateFlow<Stats?> = c.db.records().stats().map<Stats, Stats?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val presets: StateFlow<List<PresetEntity>> = c.db.presets().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val batchWork: StateFlow<WorkInfo?> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(BatchWorker.UNIQUE)
        .map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _batchId = MutableStateFlow<String?>(null)
    val batchId: StateFlow<String?> = _batchId

    val prefs get() = c.prefs
    val trust get() = c.trust

    private var job: Job? = null

    // ------------------------------------------------------------------ import & analysis

    fun open(uri: Uri) {
        job?.cancel()
        _analysis.value = Ui.Loading
        _watermark.value = Ui.Idle; _instagram.value = null; _output.value = Ui.Idle; _exportPreview.value = null
        job = viewModelScope.launch {
            try {
                val a = withContext(Dispatchers.IO) { c.analyzer.analyze(uri) }
                _analysis.value = Ui.Ready(a)
                _preview.value = withContext(Dispatchers.Default) { runCatching { ImageIO.decode(a.image.bytes, 1600).asImageBitmap() }.getOrNull() }
                val report = ReportGenerator.build(a)
                _lastReport.value = report
                withContext(Dispatchers.IO) { c.db.records().insert(record(a, "INSPECT", null, true, null, ReportGenerator.toJson(report).toString())) }
                runWatermark()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                _analysis.value = Ui.Error(e.message ?: "Could not read this image")
            }
        }
    }

    fun runWatermark() {
        val a = _analysis.value.value ?: return
        _watermark.value = Ui.Loading
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { ImageIO.decode(a.image.bytes, 1024).let { b -> WatermarkDetector.detect(b).also { b.recycle() } } }
            }
            _watermark.value = r.fold({ Ui.Ready(it) }, { Ui.Error(it.message ?: "Detection failed") })
            refreshInstagram()
        }
    }

    private fun refreshInstagram() {
        val a = _analysis.value.value ?: return
        val marks = _watermark.value.value?.regions?.filter { it.aiLogo }?.map { it.label }.orEmpty()
        _instagram.value = InstagramChecker.check(IgInput(
            ai = a.ai, format = a.format, fileSize = a.size, width = a.width, height = a.height,
            hasGps = a.hasGps, hasDeviceInfo = a.exif.has(ExifCategory.DEVICE), hasDateTime = a.exif.has(ExifCategory.DATETIME),
            hasXmp = a.container.hasXmp, hasIptc = a.container.hasIptc, hasPngText = a.container.textChunks.isNotEmpty(),
            iccDescription = a.container.iccDescription, visibleAiMarks = marks,
        ))
    }

    // ------------------------------------------------------------------ cleaning

    fun clean(o: CleanOptions) {
        val a = _analysis.value.value ?: return
        _output.value = Ui.Loading
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val res = c.cleaner.clean(a, o)
                    val ext = when (res.format) { ImageFormat.PNG -> "png"; ImageFormat.WEBP -> "webp"; else -> "jpg" }
                    val name = MediaSaver.outputName(a.name, "clean", ext)
                    val s = MediaSaver.saveImage(ctx, res.bytes, name, res.format.mime)
                    val after = runCatching { c.analyzer.analyze(LoadedImage(s.uri, name, res.bytes, false)) }.getOrNull()
                    val out = OutputInfo("Privacy metadata clean", s.displayPath, after, res.removed, res.retained, res.modified,
                        if (res.reencoded) "Re-encoded to JPEG quality 95" else "None – lossless metadata edit, pixels untouched", res.warnings)
                    val report = ReportGenerator.build(a, out)
                    c.db.records().insert(record(a, "CLEAN", null, true, s.displayPath, ReportGenerator.toJson(report).toString()))
                    SavedOutput("Cleaned copy saved", s.uri, s.displayPath, res.format.mime, report, res.warnings, res.removed, res.retained)
                }
                _lastReport.value = saved.report
                _output.value = Ui.Ready(saved)
            } catch (e: Throwable) {
                _output.value = Ui.Error(e.message ?: "Cleaning failed")
            }
        }
    }

    // ------------------------------------------------------------------ social export

    fun previewExport(o: ExportOptions) {
        val a = _analysis.value.value ?: return
        viewModelScope.launch {
            _exportPreview.value = withContext(Dispatchers.Default) { runCatching { c.exporter.render(a, o, 900).asImageBitmap() }.getOrNull() }
        }
    }

    fun export(o: ExportOptions) {
        val a = _analysis.value.value ?: return
        _output.value = Ui.Loading
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val r = c.exporter.export(a, o)
                    val ext = if (r.format == ImageFormat.PNG) "png" else "jpg"
                    val suffix = o.preset.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
                    val name = MediaSaver.outputName(a.name, suffix, ext)
                    val s = MediaSaver.saveImage(ctx, r.bytes, name, r.format.mime)
                    val after = runCatching { c.analyzer.analyze(LoadedImage(s.uri, name, r.bytes, false)) }.getOrNull()
                    val out = OutputInfo("Social media export – ${o.preset.name}", s.displayPath, after, r.removed, r.retained,
                        listOf("Resized/cropped to ${r.width}×${r.height} (${o.preset.ratioLabel})"), r.compression, r.warnings)
                    val report = ReportGenerator.build(a, out)
                    c.db.records().insert(record(a, "EXPORT", null, true, s.displayPath, ReportGenerator.toJson(report).toString()))
                    SavedOutput("Exported for ${o.preset.name}", s.uri, s.displayPath, r.format.mime, report, r.warnings, r.removed, r.retained)
                }
                _lastReport.value = saved.report
                _output.value = Ui.Ready(saved)
            } catch (e: Throwable) { _output.value = Ui.Error(e.message ?: "Export failed") }
        }
    }

    fun savePreset(p: ExportPreset) = viewModelScope.launch {
        c.db.presets().insert(PresetEntity(name = p.name, aspectW = p.aspectW, aspectH = p.aspectH, maxLongEdge = p.maxLongEdge, png = p.png, quality = p.quality))
        _messages.tryEmit("Preset “${p.name}” saved")
    }

    fun deletePreset(p: PresetEntity) = viewModelScope.launch { c.db.presets().delete(p) }

    // ------------------------------------------------------------------ watermark-region editing (owned images only)

    /** Crops the image to [keep] (normalised) and saves a copy. Disclosures/credits are re-embedded. */
    fun cropCopy(keep: RectF) {
        val a = _analysis.value.value ?: return
        _output.value = Ui.Loading
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val src = ImageIO.decode(a.image.bytes, 6000)
                    val l = (keep.left * src.width).toInt().coerceIn(0, src.width - 1)
                    val t = (keep.top * src.height).toInt().coerceIn(0, src.height - 1)
                    val r = (keep.right * src.width).toInt().coerceIn(l + 1, src.width)
                    val b = (keep.bottom * src.height).toInt().coerceIn(t + 1, src.height)
                    val crop = Bitmap.createBitmap(src, l, t, r - l, b - t)
                    val baos = ByteArrayOutputStream(); crop.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    var bytes = baos.toByteArray()
                    val dst = c.cleaner.aiDisclosure(a)
                    val retained = ArrayList<String>()
                    if (dst != null) {
                        bytes = JpegRewriter.removeSegments(bytes, emptySet(), XmpBuilder.disclosurePacket(dst, note = "Edited with ImageClean Pro"))
                        retained += "AI disclosure (IPTC DigitalSourceType: $dst)"
                    }
                    val name = MediaSaver.outputName(a.name, "edited", "jpg")
                    val s = MediaSaver.saveImage(ctx, bytes, name, "image/jpeg")
                    val after = runCatching { c.analyzer.analyze(LoadedImage(s.uri, name, bytes, false)) }.getOrNull()
                    val warnings = listOf("Edited copy for an image you own or are authorised to edit. The original is unchanged.")
                    val out = OutputInfo("Region edit (crop)", s.displayPath, after, listOf("Pixels outside the kept area", "Original metadata blocks"),
                        retained, listOf("Cropped to ${r - l}×${b - t}"), "JPEG quality 95", warnings)
                    val report = ReportGenerator.build(a, out)
                    c.db.records().insert(record(a, "EDIT", null, true, s.displayPath, ReportGenerator.toJson(report).toString()))
                    SavedOutput("Edited copy saved", s.uri, s.displayPath, "image/jpeg", report, warnings, out.removed, retained)
                }
                _lastReport.value = saved.report
                _output.value = Ui.Ready(saved)
            } catch (e: Throwable) { _output.value = Ui.Error(e.message ?: "Edit failed") }
        }
    }

    fun clearOutput() { _output.value = Ui.Idle }

    // ------------------------------------------------------------------ reports

    fun showReport(r: RecordEntity) { _lastReport.value = ReportGenerator.fromJson(r.reportJson) }

    suspend fun reportFile(reports: List<Report>, pdf: Boolean, baseName: String): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val name = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_") + if (pdf) ".pdf" else ".json"
        val bytes = if (pdf) ReportGenerator.toPdf(reports, baseName)
        else (if (reports.size == 1) ReportGenerator.toJson(reports[0]).toString(2)
              else org.json.JSONArray().apply { reports.forEach { put(ReportGenerator.toJson(it)) } }.toString(2)).toByteArray()
        val mime = if (pdf) "application/pdf" else "application/json"
        val saved = runCatching { MediaSaver.saveDocument(ctx, bytes, name, mime) }.getOrNull()
        val uri = saved?.uri ?: MediaSaver.cacheFile(ctx, "reports", name, bytes)
        _messages.tryEmit(if (saved != null) "Saved to ${saved.displayPath}" else "Report ready to share")
        uri to mime
    }

    // ------------------------------------------------------------------ batch

    fun startBatch(uris: List<Uri>, clean: Boolean) {
        if (uris.isEmpty()) return
        val id = "batch-" + UUID.randomUUID().toString().take(8)
        _batchId.value = id
        BatchWorker.enqueue(ctx, uris, id, clean)
        if (uris.size > BatchWorker.MAX_IMAGES) _messages.tryEmit("Only the first ${BatchWorker.MAX_IMAGES} images will be processed")
    }

    fun cancelBatch() { BatchWorker.cancel(ctx) }

    suspend fun batchRecords(id: String) = withContext(Dispatchers.IO) { c.db.records().batch(id) }

    fun currentBatchId(info: WorkInfo?): String? = _batchId.value ?: info?.tags?.firstOrNull { it.startsWith("batch-") }

    // ------------------------------------------------------------------ settings

    fun importTrust(uri: Uri) = viewModelScope.launch {
        val n = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { c.trust.import(it.readBytes()) } ?: 0 }.getOrElse { -1 }
        }
        _messages.tryEmit(if (n > 0) "Imported $n trust anchor(s)" else "No certificates found in that file")
    }

    fun clearTrust() { c.trust.clear(); _messages.tryEmit("Imported trust anchors removed") }

    fun clearHistory() = viewModelScope.launch { withContext(Dispatchers.IO) { c.db.records().clear() }; _messages.tryEmit("History cleared") }
}

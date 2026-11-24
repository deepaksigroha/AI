package com.deepaksigroha.imageclean.data

import androidx.exifinterface.media.ExifInterface
import com.deepaksigroha.imageclean.core.ai.ExifProvenance
import com.deepaksigroha.imageclean.core.model.ExifCategory
import com.deepaksigroha.imageclean.core.model.ExifCategory.*
import com.deepaksigroha.imageclean.core.model.ExifField
import java.io.ByteArrayInputStream

data class ExifSnapshot(
    val fields: List<ExifField>,
    val latLong: DoubleArray?,
    val hasThumbnail: Boolean,
    val thumbnailBytes: Int,
    val provenance: ExifProvenance,
    val orientation: Int,
) {
    val hasExif get() = fields.isNotEmpty()
    fun has(cat: ExifCategory) = fields.any { it.category == cat }
}

object ExifReader {

    /** Tag name (as used by ExifInterface) → label → category. */
    val TAGS: List<Triple<String, String, ExifCategory>> = listOf(
        Triple("GPSLatitude", "Latitude", LOCATION), Triple("GPSLatitudeRef", "Latitude ref", LOCATION),
        Triple("GPSLongitude", "Longitude", LOCATION), Triple("GPSLongitudeRef", "Longitude ref", LOCATION),
        Triple("GPSAltitude", "Altitude", LOCATION), Triple("GPSAltitudeRef", "Altitude ref", LOCATION),
        Triple("GPSTimeStamp", "GPS time", LOCATION), Triple("GPSDateStamp", "GPS date", LOCATION),
        Triple("GPSProcessingMethod", "GPS method", LOCATION), Triple("GPSImgDirection", "Direction", LOCATION),
        Triple("GPSImgDirectionRef", "Direction ref", LOCATION), Triple("GPSSpeed", "Speed", LOCATION),
        Triple("GPSSpeedRef", "Speed ref", LOCATION), Triple("GPSAreaInformation", "Area information", LOCATION),
        Triple("GPSDestLatitude", "Destination latitude", LOCATION), Triple("GPSDestLongitude", "Destination longitude", LOCATION),
        Triple("GPSHPositioningError", "Position error", LOCATION), Triple("GPSVersionID", "GPS version", LOCATION),

        Triple("Make", "Manufacturer", DEVICE), Triple("Model", "Model", DEVICE),
        Triple("LensMake", "Lens manufacturer", DEVICE), Triple("LensModel", "Lens model", DEVICE),
        Triple("LensSpecification", "Lens specification", DEVICE), Triple("LensSerialNumber", "Lens serial number", DEVICE),
        Triple("BodySerialNumber", "Body serial number", DEVICE), Triple("CameraOwnerName", "Camera owner", PERSONAL),
        Triple("ImageUniqueID", "Unique image ID", DEVICE), Triple("MakerNote", "Maker note (vendor data)", DEVICE),

        Triple("DateTime", "Modified", DATETIME), Triple("DateTimeOriginal", "Taken", DATETIME),
        Triple("DateTimeDigitized", "Digitized", DATETIME), Triple("OffsetTime", "Time zone", DATETIME),
        Triple("OffsetTimeOriginal", "Time zone (taken)", DATETIME), Triple("OffsetTimeDigitized", "Time zone (digitized)", DATETIME),
        Triple("SubSecTime", "Sub-second", DATETIME), Triple("SubSecTimeOriginal", "Sub-second (taken)", DATETIME),
        Triple("SubSecTimeDigitized", "Sub-second (digitized)", DATETIME),

        Triple("Software", "Software", SOFTWARE), Triple("ProcessingSoftware", "Processing software", SOFTWARE),

        Triple("Artist", "Artist", PERSONAL), Triple("Copyright", "Copyright", PERSONAL),
        Triple("ImageDescription", "Description", PERSONAL), Triple("UserComment", "User comment", PERSONAL),
        Triple("XPAuthor", "Author (Windows)", PERSONAL), Triple("XPComment", "Comment (Windows)", PERSONAL),

        Triple("ExposureTime", "Exposure time", CAPTURE), Triple("FNumber", "Aperture (f-number)", CAPTURE),
        Triple("ApertureValue", "Aperture value", CAPTURE), Triple("PhotographicSensitivity", "ISO", CAPTURE),
        Triple("FocalLength", "Focal length", CAPTURE), Triple("FocalLengthIn35mmFilm", "Focal length (35 mm)", CAPTURE),
        Triple("ExposureProgram", "Exposure program", CAPTURE), Triple("ExposureBiasValue", "Exposure bias", CAPTURE),
        Triple("MeteringMode", "Metering mode", CAPTURE), Triple("Flash", "Flash", CAPTURE),
        Triple("WhiteBalance", "White balance", CAPTURE), Triple("DigitalZoomRatio", "Digital zoom", CAPTURE),
        Triple("SceneCaptureType", "Scene type", CAPTURE), Triple("SubjectDistance", "Subject distance", CAPTURE),
        Triple("BrightnessValue", "Brightness", CAPTURE), Triple("ShutterSpeedValue", "Shutter speed value", CAPTURE),

        Triple("Orientation", "Orientation", IMAGE), Triple("ImageWidth", "Image width", IMAGE),
        Triple("ImageLength", "Image height", IMAGE), Triple("PixelXDimension", "Pixel X dimension", IMAGE),
        Triple("PixelYDimension", "Pixel Y dimension", IMAGE), Triple("ColorSpace", "Colour space", IMAGE),
        Triple("XResolution", "X resolution", IMAGE), Triple("YResolution", "Y resolution", IMAGE),
        Triple("ResolutionUnit", "Resolution unit", IMAGE), Triple("ExifVersion", "EXIF version", IMAGE),
        Triple("Compression", "Compression", IMAGE), Triple("YCbCrPositioning", "YCbCr positioning", IMAGE),
    )

    /** Tags that must never be removed because the image would display wrongly. */
    val STRUCTURAL = setOf("Orientation", "ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension", "ColorSpace", "Compression")

    fun read(bytes: ByteArray): ExifSnapshot {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return ExifSnapshot(emptyList(), null, false, 0, ExifProvenance(null, null, null, null, null, null, false), 1)
        val fields = ArrayList<ExifField>()
        for ((tag, label, cat) in TAGS) {
            val v = runCatching { exif.getAttribute(tag) }.getOrNull()?.trim()?.trim('\u0000') ?: continue
            if (v.isEmpty()) continue
            fields += ExifField(tag, label, pretty(tag, v, exif), cat)
        }
        val ll = runCatching { exif.latLong }.getOrNull()
        val hasThumb = runCatching { exif.hasThumbnail() }.getOrDefault(false)
        val thumbSize = if (hasThumb) runCatching { exif.thumbnailBytes?.size ?: 0 }.getOrDefault(0) else 0
        if (hasThumb) fields += ExifField("__thumbnail", "Embedded thumbnail", "$thumbSize bytes", THUMBNAIL)
        val prov = ExifProvenance(
            software = exif.getAttribute("Software"), make = exif.getAttribute("Make"), model = exif.getAttribute("Model"),
            description = exif.getAttribute("ImageDescription"), userComment = exif.getAttribute("UserComment"),
            artist = exif.getAttribute("Artist"), hasAnyExif = fields.isNotEmpty(),
        )
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return ExifSnapshot(fields, ll, hasThumb, thumbSize, prov, orientation)
    }

    private fun pretty(tag: String, v: String, exif: ExifInterface): String = when (tag) {
        "ExposureTime" -> v.toDoubleOrNull()?.let { if (it < 1 && it > 0) "1/${Math.round(1 / it)} s" else "$it s" } ?: v
        "FNumber" -> v.toDoubleOrNull()?.let { "f/%.1f".format(it) } ?: v
        "FocalLength" -> runCatching { exif.getAttributeDouble(tag, 0.0) }.getOrNull()?.takeIf { it > 0 }?.let { "%.1f mm".format(it) } ?: v
        "GPSLatitude", "GPSLongitude" -> v
        "Orientation" -> when (v) { "1" -> "Normal"; "3" -> "Rotated 180°"; "6" -> "Rotated 90° CW"; "8" -> "Rotated 270° CW"
            "2" -> "Mirrored"; "4" -> "Mirrored vertical"; "5" -> "Transposed"; "7" -> "Transversed"; else -> v }
        "MakerNote" -> "${v.length} bytes of vendor-specific data"
        else -> if (v.length > 300) v.take(300) + "…" else v
    }
}

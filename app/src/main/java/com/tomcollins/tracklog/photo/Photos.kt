package com.tomcollins.tracklog.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class PhotoInfo(val timeMillis: Long?, val lat: Double?, val lon: Double?)

/** Photos are kept in the app's private files/photos directory (not the shared gallery). */
object Photos {
    private val exifDate = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun newFile(ctx: Context): File = File(dir(ctx), "IMG_${System.currentTimeMillis()}.jpg")

    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    fun copyIntoApp(ctx: Context, uri: Uri): File? = runCatching {
        val f = newFile(ctx)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    }.getOrNull()

    /** Writes GPS coordinates into the JPEG's EXIF so the photo carries its location anywhere. */
    fun geotag(file: File, lat: Double, lon: Double, alt: Double?) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(lat, lon)
            if (alt != null) exif.setAltitude(alt)
            exif.saveAttributes()
        }
    }

    fun read(file: File): PhotoInfo = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val latLon = exif.latLong
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        val time = raw?.let { parseExifTime(it, offset) }
        PhotoInfo(time, latLon?.get(0), latLon?.get(1))
    }.getOrDefault(PhotoInfo(null, null, null))

    private fun parseExifTime(raw: String, offset: String?): Long? = runCatching {
        val local = LocalDateTime.parse(raw.trim(), exifDate)
        val zone = offset?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
        if (zone != null) OffsetDateTime.of(local, zone).toInstant().toEpochMilli()
        else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    /** Decodes a downsampled, correctly rotated bitmap. Call off the main thread. */
    fun loadBitmap(path: String, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }.getOrNull()
}

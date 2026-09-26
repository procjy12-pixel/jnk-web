package kr.co.jnkcorp.filter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.roundToInt

/** 갤러리 저장 · 원본 해상도 처리 (편집 화면과 카메라가 같이 씀) */
object Gallery {

    const val FULL_MAX = 4096

    fun saveJpeg(ctx: Context, bmp: Bitmap, tag: String, folder: String = "Pictures/FOFilter"): Uri? {
        val safe = tag.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FOFilter_${safe}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        return uri
    }

    /** EXIF 회전까지 반영해서, 긴 변이 [maxSide] 이하가 되게 디코드합니다. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
            }
        }
    }

    /**
     * 원본 사진([uri])에 설정을 입혀 저장합니다: 자르기 → LUT·레이어·그레인·비네팅 → 워터마크.
     * [previewLong] 은 미리보기 긴 변 (그레인 굵기 맞추기용).
     */
    fun renderAndSave(
        ctx: Context, uri: Uri, grade: Grade, frame: Frame, flip: Boolean, cropX: Float, cropY: Float,
        watermark: Watermark, tag: String, previewLong: Int,
    ): Uri? {
        val full = decode(ctx, uri, FULL_MAX)
        val fw = full.width; val fh = full.height
        val b = cropBox(fw, fh, frame, flip, cropX, cropY)
        val w = b[2]; val h = b[3]
        val px = IntArray(w * h)
        full.getPixels(px, 0, w, b[0], b[1], w, h)
        full.recycle()
        val scale = max(w, h).toFloat() / max(1, previewLong)
        Pipeline.process(px, w, h, grade, scale, Region(b[0], b[1], fw, fh))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (watermark.enabled) WatermarkPainter.draw(ctx, Canvas(out), 0f, 0f, w.toFloat(), h.toFloat(), watermark)
        return saveJpeg(ctx, out, tag).also { out.recycle() }
    }
}

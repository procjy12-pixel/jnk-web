package kr.co.jnkcorp.filter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 영상 정보 (화면에 보이는 방향 기준 크기) */
data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

/**
 * 영상에 우리 설정을 입힙니다: 자르기 → LUT(강도·보정까지 구운 한 장) → 비네팅·워터마크 오버레이.
 * 그레인·마스크 레이어는 영상에는 넣지 않습니다.
 * 메인 스레드에서 부르세요 (Transformer 가 요구).
 */
@UnstableApi
object VideoExport {

    fun info(ctx: Context, uri: Uri): VideoInfo? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(ctx, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        r.release()
        if (w == 0 || h == 0) null else if (rot == 90 || rot == 270) VideoInfo(h, w, d) else VideoInfo(w, h, d)
    } catch (e: Throwable) { null }

    /** 미리보기용 한 장면 (보이는 방향으로, 긴 변 [maxSide] 이하) */
    fun frame(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(ctx, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val at = (d * 1000 / 3).coerceAtLeast(0)   // 앞쪽 1/3 지점 (첫 장면은 검은 경우가 많아서)
        val b = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        b?.let {
            val s = maxSide.toFloat() / max(it.width, it.height)
            val sw = if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).roundToInt(), (it.height * s).roundToInt(), true) else it
            sw.copy(Bitmap.Config.ARGB_8888, false)
        }
    } catch (e: Throwable) { null }

    /** 우리 LUT → Media3 LUT (cube[R][G][B] = ARGB) */
    fun toCube(lut: Lut3D): Array<Array<IntArray>> {
        val n = lut.size
        return Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
            val i = ((b * n + g) * n + r) * 3
            fun c(v: Float) = (clamp01(v) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (c(lut.data[i]) shl 16) or (c(lut.data[i + 1]) shl 8) or c(lut.data[i + 2])
        } } }
    }

    /** 자를 영역(px) → Media3 Crop 좌표(-1..1, 위가 +) */
    fun cropNdc(w: Int, h: Int, b: IntArray): FloatArray = floatArrayOf(
        -1f + 2f * b[0] / w, -1f + 2f * (b[0] + b[2]) / w,
        1f - 2f * (b[1] + b[3]) / h, 1f - 2f * b[1] / h,
    )

    /** 비네팅(검정 반투명)과 워터마크를 한 장의 투명 그림으로 */
    private fun overlay(ctx: Context, w: Int, h: Int, vignette: Float, wm: Watermark): Bitmap? {
        if (vignette <= 0f && !wm.enabled) return null
        // 너무 큰 그림은 메모리를 많이 써서 긴 변 1920 으로 만들고 늘려 씀
        val s = minOf(1f, 1920f / max(w, h))
        val ow = max(2, (w * s).roundToInt()); val oh = max(2, (h * s).roundToInt())
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (vignette > 0f) {
            // Pipeline 의 비네팅과 같은 모양: 가운데 0 → 가장자리로 갈수록 어둡게
            val vAmt = vignette * 0.6f
            val px = IntArray(ow * oh)
            val cx = ow / 2f; val cy = oh / 2f
            for (y in 0 until oh) {
                val ny = (y - cy) / cy
                for (x in 0 until ow) {
                    val nx = (x - cx) / cx
                    val a = vAmt * smooth(0.25f, 1.6f, nx * nx + ny * ny)
                    px[y * ow + x] = ((a * 255f).toInt().coerceIn(0, 255) shl 24)
                }
            }
            bmp.setPixels(px, 0, ow, 0, 0, ow, oh)
        }
        if (wm.enabled) WatermarkPainter.draw(ctx, c, 0f, 0f, ow.toFloat(), oh.toFloat(), wm)
        return bmp
    }

    class Job(val transformer: Transformer, val output: File)

    /**
     * [input] 영상을 처리해 Movies/FOFilter 에 저장. [onProgress] 0..100, [onDone] 저장된 주소(실패면 null)와 오류 글.
     */
    fun export(
        ctx: Context, input: Uri, grade: Grade, frame: Frame, flip: Boolean, cropX: Float, cropY: Float,
        watermark: Watermark, tag: String,
        onProgress: (Int) -> Unit, onDone: (Uri?, String?) -> Unit,
    ): Job? {
        val inf = info(ctx, input) ?: run { onDone(null, "영상 정보를 읽을 수 없어요"); return null }
        val effects = ArrayList<androidx.media3.common.Effect>()
        val b = cropBox(inf.width, inf.height, frame, flip, cropX, cropY)
        if (b[2] != inf.width || b[3] != inf.height) {
            val n = cropNdc(inf.width, inf.height, b)
            effects += Crop(n[0], n[1], n[2], n[3])
        }
        grade.lut?.let { effects += SingleColorLut.createFromCube(toCube(it)) }
        overlay(ctx, b[2], b[3], grade.vignette, watermark)?.let { bmp ->
            val settings = androidx.media3.effect.OverlaySettings.Builder()
                .setScale(b[2].toFloat() / bmp.width, b[3].toFloat() / bmp.height)
                .build()
            effects += OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(bmp, settings)))
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects)))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(item))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val out = File(ctx.cacheDir, "fo_export_${System.currentTimeMillis()}.mp4")
        val main = Handler(Looper.getMainLooper())
        lateinit var job: Job
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Thread {
                        val saved = try { publish(ctx, out, tag) } catch (e: Throwable) { null }
                        out.delete()
                        main.post { onDone(saved, if (saved == null) "갤러리에 저장하지 못했어요" else null) }
                    }.start()
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    out.delete()
                    onDone(null, "${e.errorCodeName}: ${e.message ?: ""}".take(200))
                }
            })
            .build()
        job = Job(transformer, out)
        transformer.start(composition, out.absolutePath)
        // 진행률
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_NOT_STARTED) return
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 300)
            }
        }
        main.postDelayed(tick, 300)
        return job
    }

    /** 처리한 파일을 Movies/FOFilter 로 */
    private fun publish(ctx: Context, file: File, tag: String): Uri? {
        val safe = tag.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FOFilter_${safe}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FOFilter")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        return uri
    }
}

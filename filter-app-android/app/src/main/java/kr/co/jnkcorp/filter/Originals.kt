package kr.co.jnkcorp.filter

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/**
 * 편집 결과를 원본 파일에 덮어쓰기.
 * 사진 선택기·문서 주소는 읽기 전용이라 실제 갤러리(MediaStore) 항목을 찾아서 씁니다.
 * 남의 앱이 만든 사진은 안드로이드가 "수정 허용" 을 한 번 물어봅니다.
 */
object Originals {

    /** 덮어쓸 대상. [checkSame] 이면 쓰기 전에 고른 파일과 같은 파일인지 확인 (사진 선택기 주소에서 추측한 경우) */
    class Target(val uri: Uri, val checkSame: Boolean)

    /** 열어 둔 주소 → 덮어쓸 수 있는 갤러리 항목 (모르면 null) */
    fun find(ctx: Context, uri: Uri): Target? = runCatching {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val seg = uri.pathSegments
        when {
            // content://media/external/images/media/123 (갤러리에서 공유, 앱 카메라 원본, 기본 카메라)
            uri.authority == MediaStore.AUTHORITY && seg.none { it.startsWith("picker") } -> Target(uri, false)
            // 사진 선택기 content://media/picker/0/…/media/123 → 기기 안 사진이면 끝의 숫자가 갤러리 ID
            uri.authority == MediaStore.AUTHORITY -> {
                val id = uri.lastPathSegment?.toLongOrNull() ?: return null
                val video = ctx.contentResolver.getType(uri)?.startsWith("video/") == true
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                Target(ContentUris.withAppendedId(base, id), true)
            }
            // 문서 주소 (content://com.android.providers.media.documents/…)
            DocumentsContract.isDocumentUri(ctx, uri) -> MediaStore.getMediaUri(ctx, uri)?.let { Target(it, false) }
            else -> null
        }
    }.getOrNull()

    /**
     * 덮어쓰기 전에 받아야 할 허락. null 이면 이미 쓸 수 있음.
     * (앱이 만든 파일은 그냥 쓰고, 남의 파일은 안드로이드의 수정 허용 창)
     */
    fun permission(ctx: Context, target: Uri): IntentSender? {
        try {
            // 덧붙이기 모드로 열었다 닫기만 하면 내용은 그대로
            ctx.contentResolver.openOutputStream(target, "wa")?.close()
            return null
        } catch (e: RecoverableSecurityException) {
            if (Build.VERSION.SDK_INT < 30) return e.userAction.actionIntent.intentSender
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT < 30) throw e
        }
        return MediaStore.createWriteRequest(ctx.contentResolver, listOf(target)).intentSender
    }

    /** 고른 파일과 덮어쓸 파일이 같은지 (크기와 앞부분) */
    fun same(ctx: Context, picked: Uri, target: Uri): Boolean = runCatching {
        if (size(ctx, picked) != size(ctx, target)) return false
        val a = head(ctx, picked) ?: return false
        val b = head(ctx, target) ?: return false
        a.contentEquals(b)
    }.getOrDefault(false)

    private fun head(ctx: Context, uri: Uri, n: Int = 256 * 1024): ByteArray? =
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        }

    private fun size(ctx: Context, uri: Uri): Long? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    /** 사진 덮어쓰기. 원본 형식(PNG·WEBP)이면 그대로, 나머지는 JPEG */
    fun writeImage(ctx: Context, target: Uri, bmp: Bitmap) {
        val type = ctx.contentResolver.getType(target) ?: "image/jpeg"
        val (format, mime) = when {
            type == "image/png" -> Bitmap.CompressFormat.PNG to type
            type == "image/webp" && Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY to type
            else -> Bitmap.CompressFormat.JPEG to "image/jpeg"
        }
        val out = ctx.contentResolver.openOutputStream(target, "wt") ?: throw IllegalStateException("원본을 열 수 없어요")
        out.use { bmp.compress(format, 95, it) }
        // HEIC 등을 JPEG 로 바꿨으면 형식 정보도 (안 되면 그냥 둠 — 대부분의 앱은 내용을 보고 엶)
        if (mime != type) runCatching {
            ctx.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.MIME_TYPE, mime) }, null, null)
        }
    }

    /** 영상 덮어쓰기 */
    fun writeFile(ctx: Context, target: Uri, file: File) {
        val out = ctx.contentResolver.openOutputStream(target, "wt") ?: throw IllegalStateException("원본을 열 수 없어요")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** 덮어쓰기 전에 원본을 앱 캐시에 남겨 두어 계속 편집할 수 있게 (같은 사진에 필터가 두 번 입혀지지 않게) */
    fun keepCopy(ctx: Context, src: Uri): Uri {
        if (src.scheme == ContentResolver.SCHEME_FILE) return src
        val dir = File(ctx.cacheDir, "originals").apply { mkdirs() }
        val f = File(dir, "src_${System.currentTimeMillis()}")
        ctx.contentResolver.openInputStream(src)?.use { i -> f.outputStream().use { i.copyTo(it) } }
            ?: throw IllegalStateException("원본을 읽을 수 없어요")
        return Uri.fromFile(f)
    }

    /** 다른 사진을 열면 남겨 둔 원본 사본은 지움 */
    fun clearCopies(ctx: Context) {
        File(ctx.cacheDir, "originals").listFiles()?.forEach { it.delete() }
    }
}

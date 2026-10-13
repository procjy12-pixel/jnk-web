package kr.co.jnkcorp.filter

import android.net.Uri
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 원본 덮어쓰기: 연 주소 → 갤러리 원본 찾기, 같은 파일 확인, 원본 사본 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OriginalsTest {
    private val ctx: android.content.Context = RuntimeEnvironment.getApplication()

    @Test fun findsGalleryItem() {
        val media = Uri.parse("content://media/external/images/media/42")
        val t = Originals.find(ctx, media)!!
        assertEquals(media, t.uri)
        assertFalse(t.checkSame)
    }

    @Test fun pickerUriMapsToGalleryIdAndNeedsCheck() {
        val picker = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000123")
        val t = Originals.find(ctx, picker)!!
        assertEquals(Uri.parse("content://media/external/images/media/1000123"), t.uri)
        assertTrue(t.checkSame)
    }

    @Test fun unknownSourcesCannotBeOverwritten() {
        assertNull(Originals.find(ctx, Uri.parse("content://com.example.fileprovider/share/a.jpg")))
        assertNull(Originals.find(ctx, Uri.fromFile(File(ctx.cacheDir, "a.jpg"))))
    }

    @Test fun sameFileCheckAndCopy() {
        val a = File(ctx.cacheDir, "a.bin").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val b = File(ctx.cacheDir, "b.bin").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val c = File(ctx.cacheDir, "c.bin").apply { writeBytes(ByteArray(5000) { (it + 1).toByte() }) }
        assertTrue(Originals.same(ctx, Uri.fromFile(a), Uri.fromFile(b)))
        assertFalse(Originals.same(ctx, Uri.fromFile(a), Uri.fromFile(c)))
        // 이미 사본(파일)이면 그대로
        assertEquals(Uri.fromFile(a), Originals.keepCopy(ctx, Uri.fromFile(a)))
        Originals.clearCopies(ctx)
    }
}

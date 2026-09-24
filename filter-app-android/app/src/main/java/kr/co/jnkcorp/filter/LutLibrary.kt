package kr.co.jnkcorp.filter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** 목록에 보이는 LUT 한 개. 기본 룩이면 [look], 사용자 LUT 면 [file] 이 있습니다. */
class LutEntry(val name: String, val sub: String, val lut: Lut3D?, val look: Look? = null, val file: File? = null)

/** 사용자가 만들거나 가져온 LUT 를 앱 저장소(files/luts 폴더에 .cube 파일로)에 둡니다. */
class LutLibrary(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "luts").apply { mkdirs() }

    fun builtIns(): List<LutEntry> = Look.values().map { l ->
        LutEntry(l.label, l.sub, if (l == Look.ORIGINAL) null else l.bake(), look = l)
    }

    fun userLuts(): List<LutEntry> = (dir.listFiles { f -> f.name.endsWith(".cube") } ?: emptyArray())
        .sortedBy { it.lastModified() }
        .mapNotNull { f ->
            try {
                val lut = f.reader().use { Lut3D.parseCube(it, f.nameWithoutExtension) }
                LutEntry(lut.title.ifBlank { f.nameWithoutExtension }, "내 LUT · ${lut.size}³", lut, file = f)
            } catch (e: Exception) {
                null
            }
        }

    fun save(name: String, lut: Lut3D): LutEntry {
        val titled = lut.withTitle(name)
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "LUT" }
        var f = File(dir, "$safe.cube")
        var n = 2
        while (f.exists()) f = File(dir, "$safe ($n).cube").also { n++ }
        f.writeText(titled.toCube())
        return LutEntry(name, "내 LUT · ${lut.size}³", titled, file = f)
    }

    fun delete(e: LutEntry) { e.file?.delete() }

    /** 외부 .cube 파일을 읽어 라이브러리에 복사합니다. */
    fun import(uri: Uri): LutEntry {
        val display = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "LUT"
        val base = display.substringBeforeLast('.')
        val lut = ctx.contentResolver.openInputStream(uri)?.reader()?.use { Lut3D.parseCube(it, base) }
            ?: throw IllegalArgumentException("파일을 열 수 없습니다")
        return save(lut.title.ifBlank { base }, lut)
    }

    /** 다운로드/JNK LUT 폴더로 .cube 내보내기 */
    fun export(e: LutEntry): String {
        val lut = (e.lut ?: Lut3D.identity()).withTitle(e.name)
        val name = e.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "LUT" } + ".cube"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/JNK LUT")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("저장 위치를 만들 수 없습니다")
        ctx.contentResolver.openOutputStream(uri)?.writer()?.use { it.write(lut.toCube()) }
        return "Download/JNK LUT/$name"
    }
}

package kr.co.jnkcorp.filter

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * - 앱이 갑자기 꺼지면 오류 내용을 (짧게 줄여서) 파일로 남겨 둡니다.
 * - 카메라 라이브러리가 기본 후면·전면 카메라 한 대씩만 보게 합니다.
 *   폴더블처럼 카메라가 여럿인 기기에서 초기화가 실패하는 경우를 피하려는 것.
 */
class FoApp : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = "thread: ${t.name}\n\n" + shorten(sw.toString())
                crashFile(this).writeText(info)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(
                CameraSelector.Builder().addCameraFilter { infos ->
                    // 뒤쪽 첫 카메라 + 앞쪽 첫 카메라만
                    val back = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                    val front = infos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    listOfNotNull(back, front).ifEmpty { infos.take(1) }
                }.build()
            )
            .setMinimumLoggingLevel(Log.WARN)
            .build()

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last-crash.txt")

        /**
         * 오류 글을 보낼 수 있는 크기로 줄입니다:
         * 줄마다 최대 400자, 똑같은 줄이 이어지면 한 번만, 전체 최대 40KB.
         */
        fun shorten(text: String, maxTotal: Int = 40_000): String {
            val out = StringBuilder()
            var last: String? = null
            var repeat = 0
            for (raw in text.lineSequence()) {
                val line = if (raw.length > 400) raw.take(400) + " …(${raw.length}자)" else raw
                if (line == last) { repeat++; continue }
                if (repeat > 0) { out.append("    …(위 줄 ${repeat}번 반복)\n"); repeat = 0 }
                out.append(line).append('\n')
                last = line
                if (out.length > maxTotal) { out.append("…(이하 생략)\n"); break }
            }
            if (repeat > 0) out.append("    …(위 줄 ${repeat}번 반복)\n")
            return out.toString()
        }

        @Suppress("unused") private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}

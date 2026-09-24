package kr.co.jnkcorp.filter

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 앱이 갑자기 꺼지면 오류 내용을 파일로 남겨 둡니다.
 * 다음에 켤 때 편집 화면이 보여 주고, 그 실행에선 카메라를 자동으로 열지 않습니다.
 */
class FoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = "FOFilter ${packageManager.getPackageInfo(packageName, 0).versionName} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n" +
                    "thread: ${t.name}\n\n$sw"
                crashFile(this).writeText(info)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last-crash.txt")
    }
}

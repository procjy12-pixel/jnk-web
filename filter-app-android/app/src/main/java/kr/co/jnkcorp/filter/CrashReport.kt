package kr.co.jnkcorp.filter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 왜 꺼졌는지 모읍니다.
 * - 코틀린 오류: FoApp 이 남긴 파일 (전체 오류 줄)
 * - 시스템 수준 충돌·응답 없음: 안드로이드가 기록한 종료 사유 (ApplicationExitInfo)
 * - 어디까지 진행됐는지: [step] 으로 남긴 발자국
 */
object CrashReport {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fofilter-crash", Context.MODE_PRIVATE)

    /** 지금 어디쯤인지 남깁니다 (충돌 직전 위치를 알기 위해 바로 기록). */
    fun step(ctx: Context, name: String) {
        prefs(ctx).edit().putString("step", name).putLong("stepAt", System.currentTimeMillis()).commit()
    }

    /** 보여 줄 보고서가 있으면 글로, 없으면 null */
    fun pending(ctx: Context): String? {
        val sb = StringBuilder()
        val file = FoApp.crashFile(ctx)
        if (file.exists()) sb.append(runCatching { file.readText() }.getOrDefault("")).append("\n")

        val p = prefs(ctx)
        val seen = p.getLong("exitSeen", 0L)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                val bad = exits.firstOrNull {
                    it.timestamp > seen && it.reason in listOf(
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    )
                }
                if (bad != null) {
                    val kind = when (bad.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "앱 오류(CRASH)"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "시스템 수준 충돌(CRASH_NATIVE)"
                        ApplicationExitInfo.REASON_ANR -> "응답 없음(ANR)"
                        else -> "초기화 실패"
                    }
                    val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(bad.timestamp))
                    sb.append("[시스템 기록] $kind · $t · ${bad.description ?: ""} · status=${bad.status}\n")
                    if (bad.reason == ApplicationExitInfo.REASON_ANR) {
                        val trace = runCatching { bad.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        if (trace != null) sb.append(trace.lines().take(80).joinToString("\n")).append("\n")
                    }
                }
            }
        }
        if (sb.isBlank()) return null
        val step = p.getString("step", null)
        if (step != null) sb.append("\n마지막 단계: $step")
        sb.insert(0, "FOFilter ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n")
        return sb.toString()
    }

    /** 보고서를 봤다고 표시 */
    fun clear(ctx: Context) {
        FoApp.crashFile(ctx).delete()
        prefs(ctx).edit().putLong("exitSeen", System.currentTimeMillis()).remove("step").commit()
    }
}

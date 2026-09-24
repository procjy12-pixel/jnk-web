package kr.co.jnkcorp.filter

import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** 앱이 켜지는지: 편집 화면과 카메라 화면을 실제로 만들어 봅니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {

    @Test fun mainActivityStarts() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        Thread.sleep(1500)
        ShadowLooper.idleMainLooper()
        ctl.pause().resume()
        val next = shadowOf(ctl.get()).nextStartedActivity
        println("next activity: ${next?.component}")
    }

    private fun waitReady(a: MainActivity) {
        val f = MainActivity::class.java.getDeclaredField("ready").apply { isAccessible = true }
        for (i in 0 until 100) {
            ShadowLooper.idleMainLooper()
            if (f.getBoolean(a)) return
            Thread.sleep(200)
        }
        error("LUT 목록을 20초 안에 다 읽지 못함")
    }

    @Test fun mainActivityFullyLoads() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitReady(ctl.get())
        ShadowLooper.idleMainLooper()
        ctl.pause().resume()
    }

    @Test fun cameraActivityFullyLoads() {
        val ctl = Robolectric.buildActivity(CameraActivity::class.java, Intent()).setup()
        val f = CameraActivity::class.java.getDeclaredField("entries").apply { isAccessible = true }
        for (i in 0 until 100) {
            ShadowLooper.idleMainLooper()
            if ((f.get(ctl.get()) as List<*>).isNotEmpty()) break
            Thread.sleep(200)
        }
        for (i in 0 until 5) { ShadowLooper.idleMainLooper(); Thread.sleep(200) }
        ctl.pause().resume()
    }

    @Test fun returnFromCameraDoesNotCrash() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        for (i in 0 until 4) { ShadowLooper.idleMainLooper(); Thread.sleep(400) }
        val a = ctl.get()
        val started = shadowOf(a).nextStartedActivityForResult
        requireNotNull(started) { "카메라가 열려야 함" }
        shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent())
        ShadowLooper.idleMainLooper()
        // 기본 카메라로 바꿔 달라는 결과도
        shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().putExtra(CameraActivity.EXTRA_SYSTEM, true))
        ShadowLooper.idleMainLooper()
    }

    @Test fun lastCrashSkipsAutoCamera() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        FoApp.crashFile(app).writeText("test crash")
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        org.junit.Assert.assertNull("꺼진 직후엔 카메라를 자동으로 열지 않음", shadowOf(ctl.get()).nextStartedActivity)
        // 오류 화면의 '앱 계속 · 폰 기본 카메라로' 를 누르면 기록을 지우고 편집 화면으로
        val root = ctl.get().window.decorView
        val btn = findByText(root, "앱 계속 · 폰 기본 카메라로")
        requireNotNull(btn).performClick()
        ShadowLooper.idleMainLooper()
        org.junit.Assert.assertFalse(FoApp.crashFile(app).exists())
        org.junit.Assert.assertFalse(SettingsStore(app).useAppCamera)
        org.junit.Assert.assertNull(shadowOf(ctl.get()).nextStartedActivity)
    }

    private fun findByText(v: android.view.View, t: String): android.view.View? {
        if (v is android.widget.TextView && v.text.toString() == t) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findByText(v.getChildAt(i), t)?.let { return it }
        return null
    }

    @Test fun cameraActivityStartsWithPermission() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val ctl = Robolectric.buildActivity(CameraActivity::class.java, Intent()).setup()
        for (i in 0 until 5) { ShadowLooper.idleMainLooper(); Thread.sleep(400) }
        ctl.pause().stop().destroy()
    }

    @Test fun cameraActivityStarts() {
        val ctl = Robolectric.buildActivity(CameraActivity::class.java, Intent()).setup()
        ShadowLooper.idleMainLooper()
        Thread.sleep(1500)
        ShadowLooper.idleMainLooper()
        ctl.pause().stop().destroy()
    }
}

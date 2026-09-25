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

    /** 화면을 실제로 측정·배치·그려 봅니다 (onDraw 까지). */
    private fun drawAll(a: android.app.Activity) {
        val root = a.window.decorView
        val w = 1080; val h = 2340
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)))
    }

    @Test fun mainActivityDraws() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitReady(ctl.get())
        drawAll(ctl.get())
        // 글자 탭(워터마크 편집)에서도
        findByText(ctl.get().window.decorView, "글자")!!.performClick()
        ShadowLooper.idleMainLooper()
        drawAll(ctl.get())
    }

    @Test fun cameraActivityDraws() {
        val ctl = Robolectric.buildActivity(CameraActivity::class.java, Intent()).setup()
        for (i in 0 until 10) { ShadowLooper.idleMainLooper(); Thread.sleep(200) }
        drawAll(ctl.get())
        // 사진이 들어온 뒤(워터마크가 실제로 그려질 때)도
        val view = CameraActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(ctl.get()) as android.widget.ImageView
        view.setImageBitmap(android.graphics.Bitmap.createBitmap(300, 400, android.graphics.Bitmap.Config.ARGB_8888))
        drawAll(ctl.get())
        // 렌즈 셋(.6 · 1 · 3)인 폰처럼 버튼 줄을 만들어 그려 봄
        val a = ctl.get()
        CameraActivity::class.java.getDeclaredField("presets").apply { isAccessible = true }.set(a, listOf(0.6f, 1f, 3f))
        CameraActivity::class.java.getDeclaredMethod("rebuildZoomRow").apply { isAccessible = true }.invoke(a)
        CameraActivity::class.java.getDeclaredMethod("updateZoomLabels", Float::class.java).apply { isAccessible = true }.invoke(a, 1.4f)
        drawAll(a)
        org.junit.Assert.assertNotNull(findByText(a.window.decorView, "1.4×"))
        org.junit.Assert.assertNotNull(findByText(a.window.decorView, ".6"))
        org.junit.Assert.assertNotNull(findByText(a.window.decorView, "3"))
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

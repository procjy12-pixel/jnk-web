package kr.co.jnkcorp.filter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import android.util.Size
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 앱 카메라. 카메라 영상 한 장 한 장에 지금 LUT·보정·프레임을 입혀서 보여 주므로
 * 찍기 전에 보이는 그대로 찍힙니다. 셔터는 지연 최소(ZSL) 모드.
 *
 * 원본은 Pictures/FOFilter/원본, 설정을 입힌 사진은 Pictures/FOFilter 에 저장합니다.
 * 끝낼 때 마지막 원본 주소를 편집 화면에 돌려줍니다.
 */
class CameraActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var library: LutLibrary
    private val entries = ArrayList<LutEntry>()
    private var cur = defaultSettings()
    private var watermark = Watermark()

    @Volatile private var previewGrade = Grade(null, 1f, 0f, 0f)
    @Volatile private var captureGrade = Grade(null, 1f, 0f, 0f)
    @Volatile private var frameLong = 960

    private val analysisExec = Executors.newSingleThreadExecutor()
    private val saveExec = Executors.newSingleThreadExecutor()
    private val gradeExec = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var front = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var lastOriginal: Uri? = null
    private var pending = 0
    private var lastRotation = 0
    private var firstFrame = false
    private val sound = MediaActionSound()

    private lateinit var view: ImageView
    private lateinit var wmView: WatermarkView
    private lateinit var focusRing: View
    private lateinit var flashCover: View
    private lateinit var thumb: ImageView
    private lateinit var status: TextView
    private lateinit var flashBtn: TextView
    private lateinit var frameBtn: TextView
    private lateinit var lutRow: LinearLayout
    private lateinit var evSeek: SeekBar
    private lateinit var evText: TextView

    private val orange = Color.parseColor("#E8743B")
    private val soft = Color.parseColor("#DDDDDD")

    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // 가로로 들고 찍으면 사진도 가로로 저장
                val r = when (deg) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != lastRotation) { lastRotation = r; imageCapture?.targetRotation = r }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.step(this, "camera:create")
        store = SettingsStore(this)
        library = LutLibrary(this)
        cur = store.current ?: defaultSettings()
        watermark = store.watermark
        setContentView(buildUi())
        sound.load(MediaActionSound.SHUTTER_CLICK)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = done()
        })

        gradeExec.execute {
            val all = library.builtIns() + library.bundled() + library.userLuts()
            runOnUiThread {
                entries.clear(); entries.addAll(all)
                rebuildLuts()
                rebuildGrade()
            }
        }

        CrashReport.step(this, "camera:ui-ready")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera()
        else { toast("카메라 권한이 있어야 앱 카메라를 쓸 수 있어요"); status.text = "카메라 권한 없음 · ‘기본 카메라’를 쓰세요" }
    }

    override fun onResume() { super.onResume(); orientation.enable() }
    override fun onPause() { super.onPause(); orientation.disable(); store.current = cur }

    override fun onDestroy() {
        super.onDestroy()
        analysisExec.shutdown(); gradeExec.shutdown()
        sound.release()
    }

    private fun done(system: Boolean = false) {
        store.current = cur
        setResult(RESULT_OK, Intent().apply {
            lastOriginal?.let { data = it }
            if (system) putExtra(EXTRA_SYSTEM, true)
        })
        finish()
    }

    // ───────────────────────── 카메라 ─────────────────────────

    private fun startCamera() {
        CrashReport.step(this, "camera:provider")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                CrashReport.step(this, "camera:provider-ready")
                bind()
            } catch (e: Throwable) {
                cameraFailed(e)
            }
        }, mainExecutor)
    }

    private var cameraError: String? = null

    private fun cameraFailed(e: Throwable) {
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
        cameraError = "FOFilter ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.SDK_INT}\n" +
            FoApp.shorten(sw.toString(), 20_000)
        CrashReport.step(this, "camera:failed ${e.javaClass.simpleName}")
        status.text = "카메라를 열 수 없어요: ${e.javaClass.simpleName}\n이 글자를 길게 누르면 오류 내용 복사 · ‘기본 카메라’로 찍을 수 있어요"
        toast("카메라를 열 수 없습니다: ${e.javaClass.simpleName}")
    }

    private fun bind() {
        val p = provider ?: return
        try { bindUnsafe(p) } catch (e: Throwable) { cameraFailed(e) }
    }

    private fun bindUnsafe(p: ProcessCameraProvider) {
        CrashReport.step(this, "camera:bind")
        p.unbindAll()
        val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)
            .build().also { a -> a.setAnalyzer(analysisExec) { proxy ->
                try {
                    val bmp = proxy.toBitmap()
                    val rot = proxy.imageInfo.rotationDegrees
                    proxy.close()
                    showFrame(bmp, rot)
                } catch (e: Throwable) {
                    // 한 장이 실패해도 다음 장면은 계속
                    try { proxy.close() } catch (_: Throwable) {}
                }
            } }
        imageCapture = ImageCapture.Builder()
            // ZSL 은 CameraX 에서 아직 실험 기능이고 일부 삼성 기기에서 문제가 있어 안정적인 지연 최소화 모드를 씀
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
            .setFlashMode(flashMode)
            .setTargetRotation(lastRotation)
            .build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        camera = try {
            p.bindToLifecycle(this, selector, analysis, imageCapture)
        } catch (e: Exception) {
            toast("카메라를 열 수 없습니다: ${e.message}"); null
        }
        setupExposure()
        CrashReport.step(this, "camera:bound")
    }

    /** 카메라 한 장면에 설정을 입혀 화면에 보여 줍니다 (분석 스레드). */
    private fun showFrame(src: Bitmap, rot: Int) {
        val m = Matrix().apply {
            postRotate(rot.toFloat())
            if (front) postScale(-1f, 1f)
        }
        val up = if (rot == 0 && !front) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val s = cur
        val b = cropBox(up.width, up.height, s.frame, s.frameFlip, s.cropX, s.cropY)
        val w = b[2]; val h = b[3]
        val px = IntArray(w * h)
        up.getPixels(px, 0, w, b[0], b[1], w, h)
        frameLong = max(up.width, up.height)
        Pipeline.process(px, w, h, previewGrade)
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        runOnUiThread {
            view.setImageBitmap(out)
            wmView.invalidate()
            if (!firstFrame) { firstFrame = true; CrashReport.step(this, "camera:first-frame") }
        }
    }

    private fun shoot() {
        try { shootUnsafe() } catch (e: Throwable) { toast("촬영 실패: ${e.message}") }
    }

    private fun shootUnsafe() {
        val ic = imageCapture ?: return
        flashCover.alpha = 0.85f
        flashCover.animate().alpha(0f).setDuration(180).start()
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        sound.play(MediaActionSound.SHUTTER_CLICK)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FO_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FOFilter/원본")
        }
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = front }  // 셀카는 보이는 그대로
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(meta).build()
        val s = cur; val g = captureGrade; val wm = watermark; val long = frameLong
        pending++; updateStatus()
        ic.takePicture(opts, saveExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val uri = r.savedUri
                val ok = try {
                    if (uri == null) false else {
                        lastOriginal = uri
                        Gallery.renderAndSave(this@CameraActivity, uri, g, s.frame, s.frameFlip, s.cropX, s.cropY,
                            emptyList(), wm, s.lutName, long)?.also { saved ->
                            val small = Gallery.decode(this@CameraActivity, saved, 256)
                            runOnUiThread { thumb.setImageBitmap(small) }
                        } != null
                    }
                } catch (e: Throwable) { false }
                runOnUiThread { pending--; updateStatus(); if (!ok) toast("저장하지 못했습니다") }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { pending--; updateStatus(); toast("촬영 실패: ${e.message}") }
            }
        })
    }

    private fun updateStatus() {
        if (cameraError != null) return
        status.text = if (pending > 0) "저장 중 $pending" else "${cur.lutName} · ${cur.frame.label}"
    }

    private fun setupExposure() {
        val c = camera ?: return
        val st = try { c.cameraInfo.exposureState } catch (e: Throwable) { return }
        if (!st.isExposureCompensationSupported) { evSeek.isEnabled = false; evText.text = "-"; return }
        val range = st.exposureCompensationRange
        evSeek.max = range.upper - range.lower
        evSeek.progress = st.exposureCompensationIndex - range.lower
        showEv(st.exposureCompensationIndex)
    }

    private fun showEv(index: Int) {
        val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        evText.text = String.format("%+.1f", index * step)
    }

    /** 화면을 톡 누른 곳에 초점·노출 맞추기 */
    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val a = analysis ?: return
        val r = photoRect() ?: return
        var u = ((x - r[0]) / r[2]).coerceIn(0f, 1f)
        val v = ((y - r[1]) / r[3]).coerceIn(0f, 1f)
        if (front) u = 1f - u
        // 화면(세운) 좌표 → 센서 방향 좌표
        val rot = a.resolutionInfo?.rotationDegrees ?: 90
        val (bx, by) = when (rot) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        try {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, a).createPoint(bx, by)
            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        } catch (e: Throwable) { return }
        focusRing.x = x - focusRing.width / 2f; focusRing.y = y - focusRing.height / 2f
        focusRing.alpha = 1f; focusRing.scaleX = 1.3f; focusRing.scaleY = 1.3f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
        }.start()
    }

    private fun photoRect(): FloatArray? {
        val d = view.drawable ?: return null
        val bw = d.intrinsicWidth.toFloat(); val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0 || view.width == 0) return null
        val s = min(view.width / bw, view.height / bh)
        return floatArrayOf((view.width - bw * s) / 2f, (view.height - bh * s) / 2f, bw * s, bh * s)
    }

    // ───────────────────────── 설정 ─────────────────────────

    private fun rebuildGrade() {
        val s = cur
        val list = entries.toList()
        if (gradeExec.isShutdown) return
        gradeExec.execute {
            val sel = list.firstOrNull { it.key == s.lutKey }?.lut
            val lut = if (sel == null && s.adjust == MakerParams()) null
            else LutMaker.build(sel, null, s.adjust.copy(), baseIntensity = s.intensity)
            val g = Grade(lut, 1f, s.grain, s.vignette)
            captureGrade = g
            previewGrade = g
        }
        runOnUiThread { updateStatus() }
    }

    private fun setSettings(s: Settings) {
        cur = s
        store.current = s
        rebuildGrade()
        rebuildLuts()
        frameBtn.text = "프레임 ${s.frame.label}"
    }

    private fun rebuildLuts() {
        lutRow.removeAllViews()
        store.recent().forEach { r ->
            lutRow.addView(chip("최근 · ${r.label()}", false) { setSettings(r.copy()) })
        }
        entries.forEach { e ->
            lutRow.addView(chip(e.name, e.key == cur.lutKey) {
                val g = e.look?.grain ?: cur.grain
                val v = e.look?.vignette ?: cur.vignette
                setSettings(cur.copy(lutKey = e.key, lutName = e.name, grain = g, vignette = v))
            })
        }
    }

    // ───────────────────────── 화면 ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val stage = FrameLayout(this)
        view = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        stage.addView(view, -1, -1)
        wmView = WatermarkView(this).apply { watermark = this@CameraActivity.watermark; photoRect = { photoRect() } }
        stage.addView(wmView, -1, -1)
        focusRing = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.WHITE) }
            alpha = 0f
        }
        stage.addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64)))
        flashCover = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        stage.addView(flashCover, -1, -1)

        val zoomer = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return false
                val z = c.cameraInfo.zoomState.value ?: return false
                runCatching { c.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)) }
                return true
            }
        })
        var multi = false
        stage.setOnTouchListener { _, e ->
            zoomer.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> multi = false
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> if (!multi) focusAt(e.x, e.y)
            }
            true
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 위 줄
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(8))
        }
        top.addView(chip("← 편집", false) { done() })
        flashBtn = chip("플래시 끔", false) {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
            flashBtn.text = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "플래시 자동"; ImageCapture.FLASH_MODE_ON -> "플래시 켬"; else -> "플래시 끔"
            }
        }
        top.addView(flashBtn)
        frameBtn = chip("프레임 ${cur.frame.label}", false) {
            val next = Frame.values()[(cur.frame.ordinal + 1) % Frame.values().size]
            setSettings(cur.copy(frame = next))
        }
        top.addView(frameBtn)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(chip("기본 카메라", false) { done(system = true) })
        col.addView(top)
        col.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // 아래
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(0, dp(6), 0, dp(18))
        }
        status = TextView(this).apply {
            textSize = 12f; setTextColor(orange); gravity = Gravity.CENTER
            setOnLongClickListener {
                val err = cameraError ?: return@setOnLongClickListener false
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("FOFilter 카메라 오류", err.take(20_000)))
                toast("오류 내용을 복사했어요"); true
            }
        }
        bottom.addView(status)
        val evRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(12), dp(4))
        }
        evRow.addView(TextView(this).apply { text = "노출"; textSize = 12f; setTextColor(soft) }, LinearLayout.LayoutParams(dp(44), -2))
        evSeek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val c = camera ?: return
                    val idx = p + c.cameraInfo.exposureState.exposureCompensationRange.lower
                    showEv(idx)
                    if (fromUser) c.cameraControl.setExposureCompensationIndex(idx)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        evRow.addView(evSeek, LinearLayout.LayoutParams(0, -2, 1f))
        evText = TextView(this).apply { textSize = 12f; setTextColor(soft); gravity = Gravity.END }
        evRow.addView(evText, LinearLayout.LayoutParams(dp(40), -2))
        bottom.addView(evRow)

        lutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lutRow) })

        val controls = FrameLayout(this).apply { setPadding(dp(24), dp(6), dp(24), 0) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#222222")) }
            setOnClickListener { if (lastOriginal != null) done() else toast("아직 찍은 사진이 없어요") }
        }
        controls.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL))
        val shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), Color.parseColor("#555555"))
            }
            setOnClickListener { shoot() }
        }
        controls.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        val flip = TextView(this).apply {
            text = "⟲"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#222222")) }
            setOnClickListener {
                front = !front
                val has = runCatching { provider?.hasCamera(if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull()
                if (has == false) { front = !front; toast("다른 쪽 카메라가 없어요"); return@setOnClickListener }
                bind()
            }
        }
        controls.addView(flip, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))
        bottom.addView(controls)
        col.addView(bottom)

        root.addView(col, -1, -1)
        updateStatus()
        return root
    }

    private fun chip(t: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (on) Color.WHITE else soft)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(15).toFloat()
            if (on) setColor(orange) else { setColor(0x66000000); setStroke(dp(1), Color.parseColor("#555555")) }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg.take(200), Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_SYSTEM = "system"

        fun defaultSettings() = Settings(
            "look:TEAL_ORANGE", "틸앤오렌지", 1f, 0f, 0.15f, MakerParams(), Frame.ORIGINAL, false, 0.5f, 0.5f,
        )
    }
}

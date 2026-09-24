package kr.co.jnkcorp.filter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** 프레임(자르기) 비율. 가로 사진 기준 가로÷세로, 0 이면 원본 그대로 */
enum class Frame(val label: String, val ratio: Float) {
    ORIGINAL("원본", 0f),
    SCOPE("시네마스코프", 2.39f),
    WIDE("16:9", 16f / 9f),
    STD("4:3", 4f / 3f),
    PHOTO("3:2", 3f / 2f),
    SQUARE("1:1", 1f),
}

class MainActivity : Activity() {

    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var library: LutLibrary

    // 사진
    private var sourceUri: Uri? = null
    private var captureUri: Uri? = null
    private var previewFull: Bitmap? = null   // 자르기 전 미리보기
    private var preview: Bitmap? = null       // 프레임으로 자른 미리보기
    private var previewPx: IntArray? = null
    private var thumbPx: IntArray? = null
    private var thumbW = 0
    private var thumbH = 0
    private var lastRender: Bitmap? = null

    // 프레임
    private var frame = Frame.ORIGINAL
    private var frameFlip = false

    // LUT 목록과 적용 설정
    private val entries = ArrayList<LutEntry>()
    private var category = Presets.BASIC
    private var selected: LutEntry? = null
    private var intensity = 1f
    private var grain = 0f
    private var vignette = 0.15f
    private var adjust = MakerParams()        // LUT 적용 탭의 노출·대비 등

    // LUT 만들기
    private var makerMode = false
    private var makerParams = MakerParams()
    private var makerBase: LutEntry? = null
    private var transfer: ColorTransfer? = null

    private var renderGen = 0

    // 확대
    private var zoom = 1f
    private var zooming = false
    private var focusStartX = 0f
    private var focusStartY = 0f

    // 뷰
    private lateinit var image: ImageView
    private lateinit var hint: TextView
    private lateinit var frameRow: LinearLayout
    private lateinit var categoryRow: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var filterPanel: LinearLayout
    private lateinit var adjustPanel: LinearLayout
    private lateinit var makerScroll: ScrollView
    private lateinit var makerPanel: LinearLayout
    private lateinit var tabFilter: TextView
    private lateinit var tabMaker: TextView
    private val thumbs = HashMap<LutEntry, ImageView>()
    private val chips = HashMap<LutEntry, View>()

    private val orange = Color.parseColor("#E8743B")
    private val red = Color.parseColor("#D8001C")
    private val dim = Color.parseColor("#777777")
    private val soft = Color.parseColor("#BBBBBB")

    private val showOriginal = Runnable { if (!zooming) preview?.let { image.setImageBitmap(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LutLibrary(this)
        captureUri = savedInstanceState?.getString(KEY_CAPTURE)?.let(Uri::parse)
        setContentView(buildUi())

        bg.execute {
            val all = library.builtIns() + library.bundled() + library.userLuts()
            main.post {
                entries.clear(); entries.addAll(all)
                selected = entries.firstOrNull { it.look == Look.TEAL_ORANGE }
                makerBase = entries.firstOrNull()
                rebuildCategories()
                rebuildStrip()
                rebuildMaker()
                if (intent?.action == Intent.ACTION_SEND) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { load(it) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureUri?.let { outState.putString(KEY_CAPTURE, it.toString()) }
    }

    // ───────────────────────── 화면 ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
        }
        bar.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
        bar.addView(TextView(this).apply {
            text = "FOFilter"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill("촬영") { capture() })
        bar.addView(pill("열기") { pick(REQ_PICK) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        bar.addView(pill("저장", filled = true) { savePhoto() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        root.addView(bar)

        // 미리보기: 한 손가락으로 누르고 있으면 원본, 두 손가락으로 벌리면 확대 (놓으면 원래 크기)
        val stage = FrameLayout(this).apply { clipChildren = true }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        hint = TextView(this).apply {
            text = "‘촬영’ 또는 ‘열기’로 사진을 고르세요\n\n누르고 있으면 원본 · 두 손가락으로 확대"
            setTextColor(dim)
            gravity = Gravity.CENTER
            textSize = 14f
            setOnClickListener { pick(REQ_PICK) }
        }
        stage.addView(image, -1, -1)
        stage.addView(hint, -1, -1)
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (preview == null) return false
                zooming = true
                main.removeCallbacks(showOriginal)
                showFiltered()
                image.animate().cancel()
                image.pivotX = d.focusX; image.pivotY = d.focusY
                focusStartX = d.focusX; focusStartY = d.focusY
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 6f)
                image.scaleX = zoom; image.scaleY = zoom
                image.translationX = d.focusX - focusStartX
                image.translationY = d.focusY - focusStartY
                return true
            }
        })
        stage.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (preview != null) main.postDelayed(showOriginal, 120)
                MotionEvent.ACTION_POINTER_DOWN -> { main.removeCallbacks(showOriginal); showFiltered() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(showOriginal)
                    showFiltered()
                    springBack()
                }
            }
            true
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // 프레임 비율
        val frameScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        frameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(2))
        }
        frameScroll.addView(frameRow)
        root.addView(frameScroll)
        rebuildFrames()

        // 탭
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        tabFilter = tab("LUT 적용") { setMode(false) }
        tabMaker = tab("LUT 만들기") { setMode(true) }
        tabs.addView(tabFilter, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabMaker, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)

        val bottom = FrameLayout(this)

        // LUT 적용 패널: 분류 → LUT 목록 → 보정 슬라이더(스크롤)
        filterPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val catScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        catScroll.addView(categoryRow)
        filterPanel.addView(catScroll)
        val stripScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        stripScroll.addView(strip)
        filterPanel.addView(stripScroll)
        val adjustScroll = ScrollView(this)
        adjustPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        adjustScroll.addView(adjustPanel)
        filterPanel.addView(adjustScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rebuildAdjust()
        bottom.addView(filterPanel, -1, -1)

        // LUT 만들기 패널
        makerScroll = ScrollView(this).apply { visibility = View.GONE }
        makerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        makerScroll.addView(makerPanel)
        bottom.addView(makerScroll, -1, -1)

        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(360)))

        setMode(false)
        return root
    }

    private fun showFiltered() { (lastRender ?: preview)?.let { image.setImageBitmap(it) } }

    private fun springBack() {
        zooming = false
        zoom = 1f
        image.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun setMode(maker: Boolean) {
        makerMode = maker
        filterPanel.visibility = if (maker) View.GONE else View.VISIBLE
        makerScroll.visibility = if (maker) View.VISIBLE else View.GONE
        styleTab(tabFilter, !maker)
        styleTab(tabMaker, maker)
        render()
    }

    private fun rebuildFrames() {
        frameRow.removeAllViews()
        Frame.values().forEach { f ->
            frameRow.addView(smallChip(f.label, f == frame) { frame = f; rebuildFrames(); applyFrame() })
        }
        frameRow.addView(smallChip(if (frameFlip) "세로 ↕" else "가로 ↔", frameFlip) {
            frameFlip = !frameFlip; rebuildFrames(); applyFrame()
        })
    }

    private fun rebuildCategories() {
        categoryRow.removeAllViews()
        Presets.categories.forEach { c ->
            val n = entries.count { it.category == c }
            categoryRow.addView(smallChip(if (n > 0) "$c $n" else c, c == category) {
                category = c; rebuildCategories(); rebuildStrip()
            })
        }
    }

    private fun rebuildStrip() {
        strip.removeAllViews()
        thumbs.clear(); chips.clear()
        entries.filter { it.category == category }.forEach { strip.addView(chip(it)) }
        if (category == Presets.MINE) strip.addView(importChip())
        highlightChip()
        renderThumbs()
    }

    private fun rebuildAdjust() {
        adjustPanel.removeAllViews()
        val a = adjust
        adjustPanel.addView(section("LUT"))
        slider(adjustPanel, "강도", 0f, 1f, intensity, ::pct, def = 1f) { intensity = it; render() }
        adjustPanel.addView(section("보정"))
        slider(adjustPanel, "노출", -2f, 2f, a.exposure, ::ev) { a.exposure = it; render() }
        slider(adjustPanel, "대비", -1f, 1f, a.contrast, ::signedPct) { a.contrast = it; render() }
        slider(adjustPanel, "하이라이트", -1f, 1f, a.highlights, ::signedPct) { a.highlights = it; render() }
        slider(adjustPanel, "그림자", -1f, 1f, a.shadows, ::signedPct) { a.shadows = it; render() }
        slider(adjustPanel, "채도", -1f, 1f, a.saturation, ::signedPct) { a.saturation = it; render() }
        slider(adjustPanel, "색온도", -1f, 1f, a.temperature, ::signedPct) { a.temperature = it; render() }
        adjustPanel.addView(section("필름 효과"))
        slider(adjustPanel, "그레인", 0f, 1f, grain, ::pct) { grain = it; render() }
        slider(adjustPanel, "비네팅", 0f, 1f, vignette, ::pct) { vignette = it; render() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        actions.addView(pill("보정 초기화") { adjust = MakerParams(); rebuildAdjust(); render() })
        actions.addView(pill("이 설정을 LUT로", filled = true) { askNameAndSave(fromAdjust = true) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        adjustPanel.addView(actions)
    }

    private fun chip(e: LutEntry): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { select(e) }
            setOnLongClickListener { entryMenu(e); true }
        }
        val th = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#1A1A1A"))
            }
        }
        thumbs[e] = th
        chips[e] = box
        box.addView(th, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(label(e.name, 11f, Color.WHITE).apply { setPadding(0, dp(5), 0, 0); maxLines = 1 })
        box.layoutParams = LinearLayout.LayoutParams(dp(84), -2).apply { rightMargin = dp(2) }
        return box
    }

    private fun importChip(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { pickCube() }
        }
        box.addView(TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(soft)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.parseColor("#444444"))
            }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        box.addView(label(".cube 가져오기", 11f, Color.WHITE).apply { setPadding(0, dp(5), 0, 0) })
        box.layoutParams = LinearLayout.LayoutParams(dp(84), -2)
        return box
    }

    private fun select(e: LutEntry) {
        selected = e
        // 기본 룩은 그 룩에 맞는 그레인·비네팅으로 맞춰 줍니다
        e.look?.let { grain = it.grain; vignette = it.vignette; rebuildAdjust() }
        highlightChip()
        render()
    }

    private fun highlightChip() {
        chips.forEach { (e, box) ->
            box.background = if (e === selected) GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setStroke(dp(2), orange)
            } else null
        }
    }

    private fun entryMenu(e: LutEntry) {
        val items = if (e.file != null) arrayOf(".cube 로 내보내기", "이 LUT 를 기준으로 새로 만들기", "삭제")
        else arrayOf(".cube 로 내보내기", "이 LUT 를 기준으로 새로 만들기")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(e.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> bg.execute {
                        val msg = try { "${library.export(e)} 에 저장했습니다" } catch (x: Exception) { "내보내지 못했습니다: ${x.message}" }
                        main.post { toast(msg) }
                    }
                    1 -> { makerBase = e; makerParams = MakerParams(); rebuildMaker(); setMode(true) }
                    2 -> confirmDelete(e)
                }
            }.show()
    }

    private fun confirmDelete(e: LutEntry) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("‘${e.name}’ 을(를) 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                library.delete(e)
                entries.remove(e)
                if (selected === e) selected = entries.firstOrNull()
                if (makerBase === e) makerBase = entries.firstOrNull()
                rebuildCategories(); rebuildStrip(); rebuildMaker(); render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── LUT 만들기 패널

    private fun rebuildMaker() {
        makerPanel.removeAllViews()
        val p = makerParams

        makerPanel.addView(section("기준 LUT"))
        val baseScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val baseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        entries.forEach { e ->
            baseRow.addView(smallChip(e.name, e === makerBase) { makerBase = e; rebuildMaker(); render() })
        }
        baseScroll.addView(baseRow)
        makerPanel.addView(baseScroll)

        val refRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        if (transfer == null) {
            refRow.addView(smallChip("참고 사진 색감 따오기…", false) { pickReference() })
        } else {
            refRow.addView(smallChip("참고 사진 색감 적용 중  ✕", true) { transfer = null; rebuildMaker(); render() })
            refRow.addView(smallChip("다른 사진…", false) { pickReference() })
        }
        makerPanel.addView(refRow)

        makerPanel.addView(section("기본 조정"))
        slider(makerPanel, "노출", -2f, 2f, p.exposure, ::ev) { p.exposure = it; render() }
        slider(makerPanel, "대비", -1f, 1f, p.contrast, ::signedPct) { p.contrast = it; render() }
        slider(makerPanel, "하이라이트", -1f, 1f, p.highlights, ::signedPct) { p.highlights = it; render() }
        slider(makerPanel, "그림자", -1f, 1f, p.shadows, ::signedPct) { p.shadows = it; render() }
        slider(makerPanel, "채도", -1f, 1f, p.saturation, ::signedPct) { p.saturation = it; render() }
        slider(makerPanel, "색온도", -1f, 1f, p.temperature, ::signedPct) { p.temperature = it; render() }
        slider(makerPanel, "틴트", -1f, 1f, p.tint, ::signedPct) { p.tint = it; render() }
        slider(makerPanel, "페이드", 0f, 1f, p.fade, ::pct) { p.fade = it; render() }

        makerPanel.addView(section("스플릿 토닝"))
        slider(makerPanel, "그림자 색", 0f, 360f, p.shadowHue, { "●" }, ::hueColor, 190f) { p.shadowHue = it; render() }
        slider(makerPanel, "그림자 양", 0f, 1f, p.shadowAmount, ::pct) { p.shadowAmount = it; render() }
        slider(makerPanel, "밝은 곳 색", 0f, 360f, p.highlightHue, { "●" }, ::hueColor, 30f) { p.highlightHue = it; render() }
        slider(makerPanel, "밝은 곳 양", 0f, 1f, p.highlightAmount, ::pct) { p.highlightAmount = it; render() }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        actions.addView(pill("초기화") { makerParams = MakerParams(); transfer = null; rebuildMaker(); render() })
        actions.addView(pill("LUT 저장", filled = true) { askNameAndSave(fromAdjust = false) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        makerPanel.addView(actions)
    }

    /** [fromAdjust] 면 LUT 적용 탭의 (LUT + 강도 + 보정) 을, 아니면 만들기 탭 설정을 LUT 로 굽습니다. */
    private fun askNameAndSave(fromAdjust: Boolean) {
        val input = EditText(this).apply {
            hint = "예) 우리 브랜드 톤"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("LUT 이름")
            .setView(wrap)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "내 LUT ${entries.count { it.file != null } + 1}" }
                val base = if (fromAdjust) selected?.lut else makerBase?.lut
                val tr = if (fromAdjust) null else transfer
                val params = if (fromAdjust) adjust.copy() else makerParams.copy()
                val k = if (fromAdjust) intensity else 1f
                bg.execute {
                    val entry = try {
                        library.save(name, LutMaker.build(base, tr, params, name, baseIntensity = k))
                    } catch (x: Exception) { null }
                    main.post {
                        if (entry == null) { toast("저장하지 못했습니다"); return@post }
                        entries.add(entry)
                        selected = entry
                        category = Presets.MINE
                        intensity = 1f
                        if (fromAdjust) adjust = MakerParams()
                        rebuildCategories(); rebuildStrip(); rebuildAdjust(); rebuildMaker(); setMode(false)
                        toast("‘$name’ 저장됨 · 길게 누르면 .cube 로 내보낼 수 있어요")
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── 동작 ─────────────────────────

    /** 기본 카메라 앱으로 찍어서 바로 엽니다. 원본은 Pictures/FOFilter/원본 에 남습니다. */
    private fun capture() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FO_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FOFilter/원본")
        }
        val uri = try { contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null }
            ?: run { toast("촬영 준비에 실패했습니다"); return }
        captureUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            contentResolver.delete(uri, null, null)
            captureUri = null
            toast("카메라 앱을 찾을 수 없습니다")
        }
    }

    private fun pick(req: Int) {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        }
        startActivityForResult(i, req)
    }

    private fun pickCube() {
        // .cube 는 표준 MIME 이 없어서 모든 파일을 보여 줍니다
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_CUBE)
    }

    private fun pickReference() {
        if (previewPx == null) { toast("먼저 바꿀 사진을 여세요"); return }
        pick(REQ_REF)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA) {
            val uri = captureUri ?: return
            captureUri = null
            if (resultCode == RESULT_OK) load(uri) else try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PICK -> load(uri)
            REQ_REF -> loadReference(uri)
            REQ_CUBE -> importCube(uri)
        }
    }

    private fun load(uri: Uri) {
        sourceUri = uri
        hint.visibility = View.VISIBLE
        hint.text = "불러오는 중…"
        bg.execute {
            val bmp = try { decode(uri, PREVIEW_MAX) } catch (e: Exception) { null }
            main.post {
                if (bmp == null) { hint.text = "사진을 열 수 없습니다"; return@post }
                previewFull = bmp
                transfer = null
                hint.visibility = View.GONE
                rebuildMaker()
                applyFrame()
            }
        }
    }

    /** 지금 프레임 비율로 미리보기를 다시 자릅니다. */
    private fun applyFrame() {
        val full = previewFull ?: return
        val r = cropRect(full.width, full.height)
        val bmp = if (r.width() == full.width && r.height() == full.height) full
        else Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        preview = bmp
        previewPx = pixels(bmp)
        val t = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
        thumbW = t.width; thumbH = t.height; thumbPx = pixels(t)
        lastRender = null
        image.setImageBitmap(bmp)
        renderThumbs()
        render()
    }

    private fun cropRect(w: Int, h: Int): Rect {
        if (frame.ratio == 0f) return Rect(0, 0, w, h)
        val landscape = w >= h
        val target = if (landscape != frameFlip) frame.ratio else 1f / frame.ratio
        return if (w.toFloat() / h > target) {
            val cw = (h * target).roundToInt().coerceIn(1, w)
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w / target).roundToInt().coerceIn(1, h)
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }

    private fun loadReference(uri: Uri) {
        val src = preview ?: return
        toast("참고 사진 색감을 분석하는 중…")
        bg.execute {
            val t = try {
                val ref = decode(uri, 256)
                val small = Bitmap.createScaledBitmap(src, 256, max(1, 256 * src.height / src.width), true)
                ColorTransfer.from(pixels(small), pixels(ref))
            } catch (e: Exception) { null }
            main.post {
                if (t == null) { toast("참고 사진을 열 수 없습니다"); return@post }
                transfer = t
                rebuildMaker()
                render()
            }
        }
    }

    private fun importCube(uri: Uri) {
        bg.execute {
            val result = try { Result.success(library.import(uri)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                result.onSuccess { e ->
                    entries.add(e)
                    category = Presets.MINE
                    rebuildCategories(); rebuildStrip(); rebuildMaker()
                    select(e)
                    toast("‘${e.name}’ 가져옴")
                }.onFailure { toast("가져오지 못했습니다: ${it.message}") }
            }
        }
    }

    private fun renderThumbs() {
        val px = thumbPx ?: return
        val w = thumbW; val h = thumbH
        val list = entries.filter { it.category == category }
        bg.execute {
            val out = list.associateWith { e ->
                val copy = px.copyOf()
                Pipeline.process(copy, w, h, Grade(e.lut, 1f, 0f, 0f))
                Bitmap.createBitmap(copy, w, h, Bitmap.Config.ARGB_8888)
            }
            main.post { out.forEach { (e, b) -> thumbs[e]?.setImageBitmap(b) } }
        }
    }

    /** 지금 설정을 [Grade] 로. 보정·강도는 LUT 한 장에 같이 구워서 한 번에 입힙니다. */
    private fun currentGrade(): Grade {
        if (makerMode) {
            return Grade(LutMaker.build(makerBase?.lut, transfer, makerParams.copy()), 1f, grain, vignette)
        }
        val sel = selected?.lut
        val a = adjust.copy()
        val lut = if (sel == null && a == MakerParams()) null
        else LutMaker.build(sel, null, a, baseIntensity = intensity)
        return Grade(lut, 1f, grain, vignette)
    }

    /** 미리보기를 다시 그립니다. 연달아 불리면 마지막 것만 그립니다. */
    private fun render() {
        val px = previewPx ?: return
        val src = preview ?: return
        val gen = ++renderGen
        val maker = makerMode
        val base = makerBase?.lut
        val tr = transfer
        val mp = makerParams.copy()
        val sel = selected?.lut
        val a = adjust.copy()
        val k = intensity; val gr = grain; val vg = vignette
        bg.execute {
            if (gen != renderGen) return@execute
            val lut = when {
                maker -> LutMaker.build(base, tr, mp)
                sel == null && a == MakerParams() -> null
                else -> LutMaker.build(sel, null, a, baseIntensity = k)
            }
            val copy = px.copyOf()
            Pipeline.process(copy, src.width, src.height, Grade(lut, 1f, gr, vg))
            val out = Bitmap.createBitmap(copy, src.width, src.height, Bitmap.Config.ARGB_8888)
            main.post {
                if (gen != renderGen) return@post
                lastRender = out
                image.setImageBitmap(out)
            }
        }
    }

    private fun savePhoto() {
        val uri = sourceUri ?: run { toast("먼저 사진을 여세요"); return }
        val prev = preview ?: return
        val grade = currentGrade()
        val name = if (makerMode) "CUSTOM" else (selected?.name ?: "LUT")
        val f = frame; val flip = frameFlip
        toast("원본 해상도로 저장하는 중…")
        bg.execute {
            val ok = try {
                val full = decode(uri, FULL_MAX)
                val r = cropRectFor(full.width, full.height, f, flip)
                val w = r.width(); val h = r.height()
                val px = IntArray(w * h)
                full.getPixels(px, 0, w, r.left, r.top, w, h)
                full.recycle()
                val scale = max(w, h).toFloat() / max(prev.width, prev.height)
                Pipeline.process(px, w, h, grade, scale)
                val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                writeToGallery(out, name) != null
            } catch (e: Throwable) {
                false
            }
            main.post { toast(if (ok) "갤러리 Pictures/FOFilter 에 저장했습니다" else "저장하지 못했습니다") }
        }
    }

    private fun cropRectFor(w: Int, h: Int, f: Frame, flip: Boolean): Rect {
        val saveF = frame; val saveFlip = frameFlip
        frame = f; frameFlip = flip
        return cropRect(w, h).also { frame = saveF; frameFlip = saveFlip }
    }

    private fun writeToGallery(bmp: Bitmap, tag: String): Uri? {
        val safe = tag.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FOFilter_${safe}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FOFilter")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    /** EXIF 회전까지 반영해서, 긴 변이 [maxSide] 이하가 되게 디코드합니다. */
    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
            }
        }
    }

    private fun pixels(b: Bitmap): IntArray {
        val sw = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(sw.width * sw.height).also { sw.getPixels(it, 0, sw.width, 0, 0, sw.width, sw.height) }
    }

    // ───────────────────────── 작은 부품 ─────────────────────────

    private fun slider(
        parent: LinearLayout, name: String, min: Float, max: Float, value: Float,
        fmt: (Float) -> String, colorOf: ((Float) -> Int)? = null, def: Float? = null, onChange: (Float) -> Unit,
    ): SeekBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        val valueText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END
            setTextColor(soft)
        }
        fun show(v: Float) {
            valueText.text = fmt(v)
            colorOf?.let { valueText.setTextColor(it(v)); valueText.textSize = 16f }
        }
        val seek = SeekBar(this).apply {
            this.max = 1000
            progress = ((value - min) / (max - min) * 1000).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p / 1000f * (max - min)
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        show(value)
        // 이름을 누르면 기본값으로
        val reset = def ?: if (min < 0f) 0f else min
        val nameView = label(name, 12f, soft).apply {
            gravity = Gravity.START
            setOnClickListener {
                seek.progress = ((reset - min) / (max - min) * 1000).roundToInt()
                onChange(reset)
            }
        }
        row.addView(nameView, LinearLayout.LayoutParams(dp(80), -2))
        row.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueText, LinearLayout.LayoutParams(dp(46), -2))
        parent.addView(row)
        return seek
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(orange)
        setPadding(dp(16), dp(14), dp(16), dp(2))
    }

    private fun label(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
    }

    private fun tab(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun styleTab(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else dim)
        v.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = if (on) GradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#1C1C1C"))
        } else null
    }

    private fun smallChip(t: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(if (on) Color.WHITE else soft)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) setColor(orange) else setStroke(dp(1), Color.parseColor("#444444"))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
    }

    private fun pill(label: String, filled: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(if (filled) Color.WHITE else Color.parseColor("#DDDDDD"))
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (filled) setColor(orange) else setStroke(dp(1), Color.parseColor("#444444"))
            }
            setOnClickListener { onClick() }
        }

    private fun hueColor(h: Float): Int {
        val c = LutMaker.hueRgb(h)
        return Color.rgb((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun signedPct(v: Float) = String.format("%+d", (v * 100).roundToInt())
    private fun ev(v: Float) = String.format("%+.1f", v)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_REF = 2
        private const val REQ_CUBE = 3
        private const val REQ_CAMERA = 4
        private const val KEY_CAPTURE = "capture"
        private const val PREVIEW_MAX = 1400
        private const val FULL_MAX = 4096
    }
}

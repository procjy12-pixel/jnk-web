package kr.co.jnkcorp.filter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
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
import android.view.View
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

class MainActivity : Activity() {

    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var library: LutLibrary

    // 사진
    private var sourceUri: Uri? = null
    private var preview: Bitmap? = null
    private var previewPx: IntArray? = null
    private var thumbPx: IntArray? = null
    private var thumbW = 0
    private var thumbH = 0

    // LUT 목록과 적용 설정
    private val entries = ArrayList<LutEntry>()
    private var selected: LutEntry? = null
    private var intensity = 1f
    private var grain = 0f
    private var vignette = 0.15f

    // LUT 만들기
    private var makerMode = false
    private var makerParams = MakerParams()
    private var makerBase: LutEntry? = null
    private var transfer: ColorTransfer? = null
    @Volatile private var makerLut: Lut3D? = null

    private var renderGen = 0

    // 뷰
    private lateinit var image: ImageView
    private lateinit var hint: TextView
    private lateinit var strip: LinearLayout
    private lateinit var filterPanel: LinearLayout
    private lateinit var makerScroll: ScrollView
    private lateinit var makerPanel: LinearLayout
    private lateinit var tabFilter: TextView
    private lateinit var tabMaker: TextView
    private lateinit var intensitySeek: SeekBar
    private lateinit var grainSeek: SeekBar
    private lateinit var vignetteSeek: SeekBar
    private val thumbs = HashMap<LutEntry, ImageView>()
    private val chips = HashMap<LutEntry, View>()

    private val orange = Color.parseColor("#E8743B")
    private val red = Color.parseColor("#D8001C")
    private val dim = Color.parseColor("#777777")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LutLibrary(this)
        setContentView(buildUi())

        bg.execute {
            val all = library.builtIns() + library.userLuts()
            main.post {
                entries.clear(); entries.addAll(all)
                selected = entries.firstOrNull { it.look == Look.TEAL_ORANGE }
                makerBase = entries.firstOrNull()
                rebuildStrip()
                rebuildMaker()
                if (intent?.action == Intent.ACTION_SEND) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { load(it) }
                }
            }
        }
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
            text = "JNK LUT"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill("열기") { pick(REQ_PICK) })
        bar.addView(pill("사진 저장", filled = true) { savePhoto() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(bar)

        val frame = FrameLayout(this)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 누르고 있는 동안 원본 보기
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { preview?.let { setImageBitmap(it) }; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { render(); true }
                    else -> true
                }
            }
        }
        hint = TextView(this).apply {
            text = "‘열기’를 눌러 사진을 고르세요\n\n누르고 있으면 원본이 보입니다"
            setTextColor(dim)
            gravity = Gravity.CENTER
            textSize = 14f
            setOnClickListener { pick(REQ_PICK) }
        }
        frame.addView(image, -1, -1)
        frame.addView(hint, -1, -1)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

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

        // LUT 적용 패널
        filterPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        intensitySeek = slider(filterPanel, "강도", 0f, 1f, intensity, ::pct) { intensity = it; render() }
        grainSeek = slider(filterPanel, "그레인", 0f, 1f, grain, ::pct) { grain = it; render() }
        vignetteSeek = slider(filterPanel, "비네팅", 0f, 1f, vignette, ::pct) { vignette = it; render() }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(14))
        }
        scroll.addView(strip)
        filterPanel.addView(scroll)
        root.addView(filterPanel)

        // LUT 만들기 패널
        makerScroll = ScrollView(this).apply { visibility = View.GONE }
        makerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        makerScroll.addView(makerPanel)
        root.addView(makerScroll, LinearLayout.LayoutParams(-1, dp(300)))

        setMode(false)
        return root
    }

    private fun setMode(maker: Boolean) {
        makerMode = maker
        filterPanel.visibility = if (maker) View.GONE else View.VISIBLE
        makerScroll.visibility = if (maker) View.VISIBLE else View.GONE
        styleTab(tabFilter, !maker)
        styleTab(tabMaker, maker)
        render()
    }

    private fun rebuildStrip() {
        strip.removeAllViews()
        thumbs.clear(); chips.clear()
        entries.forEach { strip.addView(chip(it)) }
        strip.addView(importChip())
        highlightChip()
        renderThumbs()
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
        box.addView(th, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(label(e.name, 12f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0); maxLines = 1 })
        box.addView(label(e.sub, 9f, dim).apply { maxLines = 1 })
        box.layoutParams = LinearLayout.LayoutParams(dp(88), -2).apply { rightMargin = dp(4) }
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
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA"))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.parseColor("#444444"))
            }
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        box.addView(label(".cube", 12f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) })
        box.addView(label("가져오기", 9f, dim))
        box.layoutParams = LinearLayout.LayoutParams(dp(88), -2)
        return box
    }

    private fun select(e: LutEntry) {
        selected = e
        // 기본 룩은 그 룩에 맞는 그레인·비네팅으로 맞춰 줍니다
        e.look?.let {
            grain = it.grain; vignette = it.vignette
            setSeek(grainSeek, 0f, 1f, grain)
            setSeek(vignetteSeek, 0f, 1f, vignette)
        }
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
                rebuildStrip(); rebuildMaker(); render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── LUT 만들기 패널

    private fun rebuildMaker() {
        makerPanel.removeAllViews()
        val p = makerParams

        makerPanel.addView(section("기준"))
        val baseScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val baseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        entries.forEach { e ->
            baseRow.addView(smallChip(e.name, e === makerBase) {
                makerBase = e; rebuildMaker(); render()
            })
        }
        baseScroll.addView(baseRow)
        makerPanel.addView(baseScroll)

        val refRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        if (transfer == null) {
            refRow.addView(smallChip("참고 사진 색감 따오기…", false) { pickReference() })
        } else {
            refRow.addView(smallChip("참고 사진 색감 적용 중  ✕", true) { transfer = null; rebuildMaker(); render() })
            refRow.addView(smallChip("다른 사진…", false) { pickReference() })
        }
        makerPanel.addView(refRow)

        makerPanel.addView(section("기본 조정"))
        slider(makerPanel, "노출", -2f, 2f, p.exposure, { String.format("%+.1f", it) }) { p.exposure = it; render() }
        slider(makerPanel, "대비", -1f, 1f, p.contrast, ::signedPct) { p.contrast = it; render() }
        slider(makerPanel, "채도", -1f, 1f, p.saturation, ::signedPct) { p.saturation = it; render() }
        slider(makerPanel, "색온도", -1f, 1f, p.temperature, ::signedPct) { p.temperature = it; render() }
        slider(makerPanel, "틴트", -1f, 1f, p.tint, ::signedPct) { p.tint = it; render() }
        slider(makerPanel, "페이드", 0f, 1f, p.fade, ::pct) { p.fade = it; render() }

        makerPanel.addView(section("스플릿 토닝"))
        slider(makerPanel, "그림자색", 0f, 360f, p.shadowHue, { "●" }, ::hueColor) { p.shadowHue = it; render() }
        slider(makerPanel, "그림자양", 0f, 1f, p.shadowAmount, ::pct) { p.shadowAmount = it; render() }
        slider(makerPanel, "밝은부분색", 0f, 360f, p.highlightHue, { "●" }, ::hueColor) { p.highlightHue = it; render() }
        slider(makerPanel, "밝은부분양", 0f, 1f, p.highlightAmount, ::pct) { p.highlightAmount = it; render() }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        actions.addView(pill("초기화") { makerParams = MakerParams(); transfer = null; rebuildMaker(); render() })
        actions.addView(pill("LUT 저장", filled = true) { askNameAndSave() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        makerPanel.addView(actions)
    }

    private fun askNameAndSave() {
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
                val base = makerBase?.lut
                val tr = transfer
                val params = makerParams.copy()
                bg.execute {
                    val entry = try { library.save(name, LutMaker.build(base, tr, params, name)) } catch (x: Exception) { null }
                    main.post {
                        if (entry == null) { toast("저장하지 못했습니다"); return@post }
                        entries.add(entry)
                        selected = entry
                        intensity = 1f; setSeek(intensitySeek, 0f, 1f, 1f)
                        rebuildStrip(); rebuildMaker(); setMode(false)
                        toast("‘$name’ 저장됨 · 길게 누르면 .cube 로 내보낼 수 있어요")
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── 동작 ─────────────────────────

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
                preview = bmp
                previewPx = pixels(bmp)
                val t = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
                thumbW = t.width; thumbH = t.height; thumbPx = pixels(t)
                transfer = null
                hint.visibility = View.GONE
                rebuildMaker()
                renderThumbs()
                render()
            }
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
                    rebuildStrip(); rebuildMaker()
                    select(e)
                    toast("‘${e.name}’ 가져옴")
                }.onFailure { toast("가져오지 못했습니다: ${it.message}") }
            }
        }
    }

    private fun renderThumbs() {
        val px = thumbPx ?: return
        val w = thumbW; val h = thumbH
        val list = entries.toList()
        bg.execute {
            val out = list.associateWith { e ->
                val copy = px.copyOf()
                Pipeline.process(copy, w, h, Grade(e.lut, 1f, 0f, 0f))
                Bitmap.createBitmap(copy, w, h, Bitmap.Config.ARGB_8888)
            }
            main.post { out.forEach { (e, b) -> thumbs[e]?.setImageBitmap(b) } }
        }
    }

    /** 지금 설정 그대로 미리보기를 다시 그립니다. 연달아 불리면 마지막 것만 그립니다. */
    private fun render() {
        val px = previewPx ?: return
        val src = preview ?: return
        val gen = ++renderGen
        val maker = makerMode
        val base = makerBase?.lut
        val tr = transfer
        val params = makerParams.copy()
        val sel = selected?.lut
        val k = intensity; val gr = grain; val vg = vignette
        bg.execute {
            if (gen != renderGen) return@execute
            val grade = if (maker) {
                val lut = LutMaker.build(base, tr, params)
                makerLut = lut
                Grade(lut, 1f, gr, vg)
            } else Grade(sel, k, gr, vg)
            val copy = px.copyOf()
            Pipeline.process(copy, src.width, src.height, grade)
            val out = Bitmap.createBitmap(copy, src.width, src.height, Bitmap.Config.ARGB_8888)
            main.post { if (gen == renderGen) image.setImageBitmap(out) }
        }
    }

    private fun savePhoto() {
        val uri = sourceUri ?: run { toast("먼저 사진을 여세요"); return }
        val prev = preview ?: return
        val grade = if (makerMode) Grade(LutMaker.build(makerBase?.lut, transfer, makerParams.copy()), 1f, grain, vignette)
        else Grade(selected?.lut, intensity, grain, vignette)
        val name = if (makerMode) "CUSTOM" else (selected?.name ?: "LUT")
        toast("원본 해상도로 저장하는 중…")
        bg.execute {
            val ok = try {
                val full = decode(uri, FULL_MAX, mutable = true)
                val px = pixels(full)
                val scale = max(full.width, full.height).toFloat() / max(prev.width, prev.height)
                Pipeline.process(px, full.width, full.height, grade, scale)
                full.setPixels(px, 0, full.width, 0, 0, full.width, full.height)
                writeToGallery(full, name) != null
            } catch (e: Throwable) {
                false
            }
            main.post { toast(if (ok) "갤러리 Pictures/JNK Filter 에 저장했습니다" else "저장하지 못했습니다") }
        }
    }

    private fun writeToGallery(bmp: Bitmap, tag: String): Uri? {
        val safe = tag.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "JNK_${safe}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JNK Filter")
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
    private fun decode(uri: Uri, maxSide: Int, mutable: Boolean = false): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = mutable
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
        fmt: (Float) -> String, colorOf: ((Float) -> Int)? = null, onChange: (Float) -> Unit,
    ): SeekBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(12), dp(2))
        }
        val valueText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END
            setTextColor(Color.parseColor("#BBBBBB"))
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
        row.addView(label(name, 12f, Color.parseColor("#BBBBBB")).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(dp(72), -2))
        row.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueText, LinearLayout.LayoutParams(dp(44), -2))
        parent.addView(row)
        return seek
    }

    private fun setSeek(s: SeekBar, min: Float, max: Float, v: Float) {
        s.progress = ((v - min) / (max - min) * 1000).roundToInt()
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(orange)
        setPadding(dp(16), dp(12), dp(16), dp(4))
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
        setTextColor(if (on) Color.WHITE else Color.parseColor("#BBBBBB"))
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
            textSize = 14f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
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
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_REF = 2
        private const val REQ_CUBE = 3
        private const val PREVIEW_MAX = 1400
        private const val FULL_MAX = 4096
    }
}

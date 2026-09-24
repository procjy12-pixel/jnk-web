package kr.co.jnkcorp.filter

import android.annotation.SuppressLint
import android.app.Activity
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {

    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var sourceUri: Uri? = null
    private var preview: Bitmap? = null      // 화면용으로 줄인 원본
    private var look = Look.TEAL_ORANGE
    private var intensity = 0.8f
    private var renderGen = 0

    private lateinit var image: ImageView
    private lateinit var hint: TextView
    private lateinit var amountLabel: TextView
    private lateinit var strip: LinearLayout
    private val thumbs = HashMap<Look, ImageView>()
    private val chips = HashMap<Look, LinearLayout>()

    private val orange = Color.parseColor("#E8743B")
    private val red = Color.parseColor("#D8001C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { load(it) }
        }
    }

    // ───────────────────────── UI ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
        }

        // 상단 바: 빨간 점 + 로고 / 열기 / 저장
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
        }
        bar.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(10) })
        bar.addView(TextView(this).apply {
            text = "JNK FILTER"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill("열기") { pick() })
        bar.addView(pill("저장", filled = true) { save() },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(bar)

        // 미리보기
        val frame = android.widget.FrameLayout(this)
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
            setTextColor(Color.parseColor("#777777"))
            gravity = Gravity.CENTER
            textSize = 14f
            setOnClickListener { pick() }
        }
        frame.addView(image, -1, -1)
        frame.addView(hint, -1, -1)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 강도
        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        amountLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 12f
            minWidth = dp(64)
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = (intensity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    intensity = p / 100f
                    updateAmountLabel()
                    if (fromUser) render()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        amountRow.addView(amountLabel)
        amountRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(amountRow)
        updateAmountLabel()

        // 필터 목록
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(18))
        }
        Look.values().forEach { l -> strip.addView(chip(l)) }
        scroll.addView(strip)
        root.addView(scroll)
        highlightChip()

        return root
    }

    private fun chip(l: Look): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                look = l
                highlightChip()
                render()
            }
        }
        val th = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#1A1A1A"))
            }
        }
        thumbs[l] = th
        chips[l] = box
        box.addView(th, LinearLayout.LayoutParams(dp(76), dp(76)))
        box.addView(TextView(this).apply {
            text = l.label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = l.sub
            setTextColor(Color.parseColor("#777777"))
            textSize = 9f
            gravity = Gravity.CENTER
        })
        return box.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(92), -2).apply { rightMargin = dp(4) }
        }
    }

    private fun highlightChip() {
        chips.forEach { (l, box) ->
            box.background = if (l == look) GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), orange)
            } else null
        }
    }

    private fun pill(label: String, filled: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setTextColor(if (filled) Color.WHITE else Color.parseColor("#DDDDDD"))
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (filled) setColor(orange) else setStroke(dp(1), Color.parseColor("#444444"))
            }
            setOnClickListener { onClick() }
        }

    private fun updateAmountLabel() {
        amountLabel.text = "강도 ${(intensity * 100).toInt()}%"
    }

    // ───────────────────────── 동작 ─────────────────────────

    private fun pick() {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    private fun load(uri: Uri) {
        sourceUri = uri
        hint.text = "불러오는 중…"
        bg.execute {
            val bmp = try { decode(uri, PREVIEW_MAX) } catch (e: Exception) { null }
            val thumb = bmp?.let { Bitmap.createScaledBitmap(it, 160, max(1, 160 * it.height / it.width), true) }
            val thumbResults = thumb?.let { t -> Look.values().associateWith { LookEngine.apply(t, it, 1f) } }
            main.post {
                if (bmp == null) {
                    hint.text = "사진을 열 수 없습니다"
                    return@post
                }
                preview = bmp
                hint.visibility = View.GONE
                thumbResults?.forEach { (l, b) -> thumbs[l]?.setImageBitmap(b) }
                render()
            }
        }
    }

    private fun render() {
        val src = preview ?: return
        val gen = ++renderGen
        val l = look
        val k = intensity
        bg.execute {
            if (gen != renderGen) return@execute   // 더 새 요청이 있으면 건너뜀
            val out = LookEngine.apply(src, l, k)
            main.post { if (gen == renderGen) image.setImageBitmap(out) }
        }
    }

    private fun save() {
        val uri = sourceUri ?: run { toast("먼저 사진을 여세요"); return }
        val l = look
        val k = intensity
        toast("원본 해상도로 저장하는 중…")
        bg.execute {
            val result = try {
                val full = decode(uri, FULL_MAX)
                val out = LookEngine.apply(full, l, k)
                writeToGallery(out, l)
            } catch (e: Exception) {
                null
            }
            main.post { toast(if (result != null) "갤러리 Pictures/JNK Filter 에 저장했습니다" else "저장하지 못했습니다") }
        }
    }

    private fun writeToGallery(bmp: Bitmap, l: Look): Uri? {
        val name = "JNK_${l.name}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
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
    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQ_PICK = 1
        private const val PREVIEW_MAX = 1400
        private const val FULL_MAX = 4096
    }
}

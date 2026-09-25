package kr.co.jnkcorp.filter

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * 폰 안에서 얼굴 윤곽·눈·눈썹·코·입을 찾습니다 (Google ML Kit, 모델이 앱에 들어 있어 인터넷 불필요).
 * 백그라운드 스레드에서 부르세요. 실패하거나 얼굴이 없으면 null.
 */
object FaceGuard {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
    }

    private val protectTypes = listOf(
        FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
        FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
        FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
        FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
        FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM,
        FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
    )

    fun detect(bmp: Bitmap): FaceRegions? = try {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        // 윤곽은 가장 뚜렷한 얼굴 하나에만 나옵니다
        val face = faces.firstOrNull { it.getContour(FaceContour.FACE) != null } ?: faces.firstOrNull()
        val oval = face?.getContour(FaceContour.FACE)?.points?.map { floatArrayOf(it.x, it.y) }
        if (face == null || oval == null || oval.size < 3) null
        else {
            fun pts(t: Int) = face.getContour(t)?.points?.map { floatArrayOf(it.x, it.y) } ?: emptyList()
            val protect = ArrayList<List<FloatArray>>()
            // 눈썹은 위·아래를 합쳐 한 덩어리, 입술은 네 줄을 합쳐 한 덩어리, 코는 콧등+콧방울
            protect += pts(FaceContour.LEFT_EYE); protect += pts(FaceContour.RIGHT_EYE)
            protect += pts(FaceContour.LEFT_EYEBROW_TOP) + pts(FaceContour.LEFT_EYEBROW_BOTTOM)
            protect += pts(FaceContour.RIGHT_EYEBROW_TOP) + pts(FaceContour.RIGHT_EYEBROW_BOTTOM)
            protect += pts(FaceContour.UPPER_LIP_TOP) + pts(FaceContour.UPPER_LIP_BOTTOM) +
                pts(FaceContour.LOWER_LIP_TOP) + pts(FaceContour.LOWER_LIP_BOTTOM)
            protect += pts(FaceContour.NOSE_BRIDGE) + pts(FaceContour.NOSE_BOTTOM)
            FaceRegions(oval, protect.filter { it.isNotEmpty() })
        }
    } catch (e: Throwable) {
        null
    }

    @Suppress("unused") private val keep = protectTypes
}

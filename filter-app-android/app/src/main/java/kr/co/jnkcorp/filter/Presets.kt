package kr.co.jnkcorp.filter

/**
 * 앱에 같이 넣은 라이카 룩 LUT (assets/luts/leica).
 * 같은 장면을 일반 카메라와 라이카 룩으로 찍은 사진 쌍을 맞춰 놓고 색 변화를 피팅해 33³ LUT 로 구웠습니다.
 * 비네팅도 같은 사진에서 잰 값이 기본값입니다 (LUT 에는 위치 효과가 담기지 않으므로).
 */
object Presets {

    class Preset(val path: String, val name: String, val sub: String, val category: String, val grain: Float, val vignette: Float)

    const val BASIC = "기본"
    const val LEICA = "라이카"
    const val RICOH = "리코"
    const val MINE = "내 LUT"

    val categories = listOf(BASIC, LEICA, RICOH, MINE)

    val all = listOf(
        Preset("leica/leica_teal", "라이카 틸", "Leica Teal", LEICA, 0f, 0.36f),
        Preset("leica/leica_eternal", "라이카 이터널", "Leica Eternal", LEICA, 0f, 0.34f),
        Preset("leica/leica_contemporary", "라이카 컨템퍼러리", "Leica Contemporary · CNT", LEICA, 0f, 0.27f),
        Preset("leica/leica_classic", "라이카 클래식", "Leica Classic · CLS", LEICA, 0f, 0.25f),
    )

    /** 예전 버전에서 저장한 설정의 LUT 키를 지금 목록의 키로 바꿉니다. 없어진 LUT 면 null (원본). */
    fun migrateKey(key: String?): String? = when {
        key == null -> null
        key == "look:LEICA_CLASSIC" -> "preset:leica/leica_classic"
        key == "look:LEICA_CONTEMPORARY" -> "preset:leica/leica_contemporary"
        key == "look:LEICA_ETERNAL" -> "preset:leica/leica_eternal"
        key.startsWith("look:") -> key.takeIf { k -> Look.values().any { "look:${it.name}" == k } }
        key.startsWith("preset:") -> key.takeIf { k -> all.any { "preset:${it.path}" == k } }
        else -> key
    }
}

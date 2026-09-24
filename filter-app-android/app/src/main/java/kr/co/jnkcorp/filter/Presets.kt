package kr.co.jnkcorp.filter

/**
 * 앱에 같이 넣은 필름 에뮬레이션 LUT (assets/luts).
 * 출처: G'MIC Film LUTs Collection (github.com/YahiaAngelo/Film-Luts, MIT) — LICENSE-Film-Luts.txt
 */
object Presets {

    class Preset(val path: String, val name: String, val category: String)

    const val BASIC = "기본"
    const val COLOR_NEG = "컬러 네거"
    const val SLIDE = "슬라이드"
    const val FUJI = "후지 시뮬"
    const val CINEMA = "시네마"
    const val BW = "흑백"
    const val INSTANT = "인스턴트"
    const val MINE = "내 LUT"

    val categories = listOf(BASIC, COLOR_NEG, SLIDE, FUJI, CINEMA, BW, INSTANT, MINE)

    val all = listOf(
        Preset("negative_new/kodak_portra_400", "Portra 400", COLOR_NEG),
        Preset("negative_new/kodak_portra_160", "Portra 160", COLOR_NEG),
        Preset("negative_new/kodak_portra_800_hc", "Portra 800 HC", COLOR_NEG),
        Preset("negative_new/fuji_400h", "Fuji 400H", COLOR_NEG),
        Preset("negative_color/kodak_ektar_100", "Ektar 100", COLOR_NEG),
        Preset("negative_old/fuji_superia_400", "Superia 400", COLOR_NEG),
        Preset("negative_color/agfa_vista_200", "Vista 200", COLOR_NEG),
        Preset("negative_color/lomography_redscale_100", "Redscale 100", COLOR_NEG),

        Preset("colorslide/fuji_velvia_50", "Velvia 50", SLIDE),
        Preset("colorslide/fuji_provia_100f", "Provia 100F", SLIDE),
        Preset("colorslide/kodak_kodachrome_64", "Kodachrome 64", SLIDE),
        Preset("colorslide/kodak_ektachrome_100_vs", "Ektachrome VS", SLIDE),
        Preset("colorslide/lomography_x-pro_slide_200", "X-Pro 200", SLIDE),

        Preset("fujixtransiii/fuji_xtrans_iii_classic_chrome", "Classic Chrome", FUJI),
        Preset("fujixtransiii/fuji_xtrans_iii_pro_neg_std", "Pro Neg Std", FUJI),
        Preset("fujixtransiii/fuji_xtrans_iii_astia", "Astia", FUJI),
        Preset("fujixtransiii/fuji_xtrans_iii_acros+r", "Acros + R", FUJI),

        Preset("print/kodak_2383_constlmap", "Kodak 2383", CINEMA),
        Preset("print/kodak_2393_constlmap", "Kodak 2393", CINEMA),
        Preset("print/fuji_3513_constlmap", "Fuji 3513", CINEMA),

        Preset("bw/kodak_tri-x_400", "Tri-X 400", BW),
        Preset("bw/ilford_hp_5_plus_400", "HP5 Plus", BW),
        Preset("bw/ilford_delta_3200", "Delta 3200", BW),
        Preset("bw/fuji_neopan_acros_100", "Acros 100", BW),
        Preset("bw/kodak_t-max_100", "T-Max 100", BW),

        Preset("instant_consumer/polaroid_px-70", "PX-70", INSTANT),
        Preset("instant_pro/fuji_fp-100c", "FP-100C", INSTANT),
        Preset("instant_pro/polaroid_665", "Polaroid 665", INSTANT),
        Preset("colorslide/polaroid_690", "Polaroid 690", INSTANT),
    )
}

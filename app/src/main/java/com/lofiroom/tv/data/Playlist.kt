package com.lofiroom.tv.data

/** 曲目与诗意文案数据 */
data class Track(val title: String, val sub: String, val asset: String)

data class Caption(val label: String, val cn: String, val en: String)

object Playlist {

    val tracks = listOf(
        Track(
            "Midnight Espresso",
            "LOFI JAZZ · 72 BPM",
            "music/midnight_espresso.m4a"
        ),
        Track(
            "Rainy Window",
            "PIANO & RAIN · 60 BPM",
            "music/rainy_window.m4a"
        ),
        Track(
            "Bossa Candlelight",
            "BOSSA NOVA · 84 BPM",
            "music/bossa_candlelight.m4a"
        )
    )

    val captions = listOf(
        Caption(
            "SLOW DOWN",
            "慢下来，让爵士与夜色慢慢发酵",
            "LET JAZZ AND NIGHT AIR FERMENT SLOWLY"
        ),
        Caption(
            "WARM LIGHT",
            "一盏灯，一杯咖啡，一段不被打扰的时光",
            "ONE LAMP, ONE CUP, ONE UNINTERRUPTED HOUR"
        ),
        Caption(
            "MIDNIGHT",
            "午夜的琴声，替你收好今天的疲惫",
            "PIANO AFTER MIDNIGHT KEEPS THE DAY'S FATIGUE"
        ),
        Caption(
            "QUIET FLOW",
            "在悠扬的旋律里，思绪像水一样流过",
            "THOUGHTS DRIFT LIKE WATER ON A SLOW MELODY"
        )
    )
}

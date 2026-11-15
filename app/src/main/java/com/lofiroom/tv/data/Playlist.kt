package com.lofiroom.tv.data

/** 曲目与诗意文案数据 */
data class Track(val title: String, val sub: String, val asset: String)

data class Caption(val label: String, val cn: String, val en: String)

data class Wallpaper(val cn: String, val en: String)

object Playlist {

    val tracks = listOf(
        Track(
            "Jazz Ballad Rainy Night",
            "JAZZ BALLAD · ALEX MORGAN",
            "music/jazz_ballad_rainy_night.m4a"
        ),
        Track(
            "Music On Hold Without a Beat",
            "SOFT AMBIENT · ALEX MORGAN",
            "music/music_on_hold_without_a_beat.m4a"
        ),
        Track(
            "Ambient Corporate Soft Bed",
            "AMBIENT PAD · ALEX MORGAN",
            "music/ambient_corporate_soft_bed.m4a"
        ),
        Track(
            "Calm Piano for Quiet Moments",
            "CALM PIANO · ALEX MORGAN",
            "music/calm_piano_for_quiet_moments.m4a"
        ),
        Track(
            "Emotional Piano With Soft Pads",
            "PIANO & PADS · ALEX MORGAN",
            "music/emotional_piano_with_soft_pads.m4a"
        ),
        Track(
            "Love Background Warm Tenderness",
            "WARM BALLAD · ALEX MORGAN",
            "music/love_background_warm_tenderness.m4a"
        ),
        Track(
            "Ivory Keys Dark Piano Warmth",
            "DARK PIANO · ALEX MORGAN",
            "music/ivory_keys_dark_piano_warmth.m4a"
        ),
        Track(
            "Luxury Hotel BGM",
            "HOTEL LOUNGE · LAO BAN",
            "music/luxury_hotel_bgm.m4a"
        ),
        Track(
            "Fine Dining BGM",
            "DINNER JAZZ · LAO BAN",
            "music/fine_dining_bgm.m4a"
        )
    )

    /** 壁纸显示名，顺序与 assets/wallpapers/wall_01..17.jpg 一一对应 */
    val wallpapers = listOf(
        Wallpaper("城市夜景", "City Night"),
        Wallpaper("书桌", "Desk"),
        Wallpaper("客厅", "Interior"),
        Wallpaper("窗台植物", "Plants"),
        Wallpaper("壁炉小猫", "Kitten"),
        Wallpaper("森林单车", "Forest Ride"),
        Wallpaper("雨窗花田", "Forest Window"),
        Wallpaper("深夜书桌", "Night Desk"),
        Wallpaper("炉边小猫", "Fireside Cat"),
        Wallpaper("藤椅窗边", "Rattan Chair"),
        Wallpaper("暮色百叶窗", "Dusk Blinds"),
        Wallpaper("书桌工位", "Work Desk"),
        Wallpaper("窗边小猫", "Window Cat"),
        Wallpaper("电视墙", "TV Wall"),
        Wallpaper("客厅全景", "Living Room"),
        Wallpaper("沙发阅读", "Sofa Reading"),
        Wallpaper("暖光卧室", "Warm Bedroom")
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

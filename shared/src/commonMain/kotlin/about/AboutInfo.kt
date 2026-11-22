package com.sniffsub.sniff_sub_player.about

data class AboutLink(val name: String, val url: String)

object AboutInfo {
    val sourceUrl = "https://github.com/snifferhub/SniffsubPlayer"
    val releasesUrl = "https://github.com/snifferhub/SniffSubPlayer-Releases/releases"
    val licenseName = "GPL-3.0-only"
    val licenseUrl = "$sourceUrl/blob/main/LICENSE"

    private val sharedProjects = listOf(
        AboutLink("Kotlin Multiplatform", "https://kotlinlang.org/multiplatform/"),
        AboutLink("Compose Multiplatform", "https://github.com/JetBrains/compose-multiplatform"),
    )

    val androidProjects = sharedProjects + AboutLink(
        "AndroidX Media3 (ExoPlayer)",
        "https://github.com/androidx/media",
    )

    val iosProjects = sharedProjects + AboutLink(
        "SafariConverterLib",
        "https://github.com/AdguardTeam/SafariConverterLib",
    )
}

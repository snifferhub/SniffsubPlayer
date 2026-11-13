package com.sniffsub.sniff_sub_player

object BuildIdentity {
    const val VERSION_NAME = BuildConfig.VERSION_NAME
    const val VERSION_CODE = BuildConfig.VERSION_CODE
    const val BUILD_NUMBER = BuildConfig.RELEASE_BUILD_NUMBER
    const val MIN_SDK = 28
    val displayVersion: String get() = if (BUILD_NUMBER > 0) "$VERSION_NAME · build $BUILD_NUMBER" else VERSION_NAME
}

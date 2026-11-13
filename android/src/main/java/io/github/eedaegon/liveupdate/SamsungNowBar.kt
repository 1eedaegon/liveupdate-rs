package io.github.eedaegon.liveupdate

/** Closed, opt-in mapping of One UI 9 Now Bar extras. */
internal object SamsungNowBar {
    private const val MIN_SDK = 37
    private const val PREFIX = "android.ongoingActivityNoti."

    fun extras(sdk: Int, primary: String, secondary: String?): Map<String, Any> {
        if (sdk < MIN_SDK) return emptyMap()
        val extras = linkedMapOf<String, Any>(
            "${PREFIX}style" to 1,
            "${PREFIX}primaryInfo" to primary,
            "${PREFIX}nowbarPrimaryInfo" to primary,
        )
        if (secondary != null) {
            extras["${PREFIX}secondaryInfo"] = secondary
            extras["${PREFIX}nowbarSecondaryInfo"] = secondary
        }
        return extras
    }

    fun isCharSequence(key: String): Boolean =
        key == "${PREFIX}primaryInfo" || key == "${PREFIX}secondaryInfo"
}

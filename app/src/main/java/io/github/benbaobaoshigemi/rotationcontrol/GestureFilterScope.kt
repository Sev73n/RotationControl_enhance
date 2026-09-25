package io.github.benbaobaoshigemi.rotationcontrol

/**
 * 过滤名单作用范围。ALL 沿用原来的 lsp_rot_blocked_apps，旧名单仍然表示三种手势一起禁。
 */
enum class GestureFilterScope {
    ALL,
    TWO_FINGER,
    BOTTOM_SWIPE,
    TRIPLE_TAP;

    val storageKey: String
        get() = when (this) {
            ALL -> "lsp_rot_blocked_apps"
            TWO_FINGER -> "lsp_rot_blocked_two_finger"
            BOTTOM_SWIPE -> "lsp_rot_blocked_bottom_swipe"
            TRIPLE_TAP -> "lsp_rot_blocked_triple_tap"
        }

    companion object {
        fun fromStorageKey(key: String): GestureFilterScope? =
            entries.firstOrNull { it.storageKey == key }
    }
}

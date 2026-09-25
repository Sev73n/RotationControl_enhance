package io.github.benbaobaoshigemi.rotationcontrol

/**
 * 前台过滤：包名精确匹配；也可填写 Activity 类名或「包名:进程后缀」。
 * 微信策略默认开启——聊天和主界面放行，只拦小程序容器（:appbrand* / AppBrandUI*）。
 * 过滤名单若勾选整个 com.tencent.mm，仍按整包拦截，策略不会把已勾选的微信重新放行。
 */
object ForegroundFilterPolicy {
    const val WECHAT_PACKAGE = "com.tencent.mm"

    private const val WECHAT_APPBRAND_PROCESS = "com.tencent.mm:appbrand"
    private const val WECHAT_APPBRAND_UI = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
    private const val WECHAT_APPBRAND_GAME_UI = "com.tencent.mm.plugin.appbrand.ui.AppBrandGameUI"
    private const val WECHAT_APPBRAND_LAUNCH_PROXY =
        "com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI"

    private val RULE_REGEX =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+(:[A-Za-z][A-Za-z0-9_]*)?$")

    data class Foreground(
        val packageName: String,
        val className: String,
        val processName: String
    )

    fun canonicalizeRule(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val slash = text.indexOf('/')
        if (slash >= 0) {
            val pkg = text.substring(0, slash).trim()
            var cls = text.substring(slash + 1).trim()
            if (pkg.isEmpty() || cls.isEmpty() || '/' in cls) return null
            if (cls.startsWith(".")) cls = pkg + cls
            text = cls
        }
        return text.takeIf { RULE_REGEX.matches(it) }
    }

    fun isValidRule(name: String): Boolean = canonicalizeRule(name) == name

    fun suppressionReason(
        foreground: Foreground?,
        allGestureRules: Set<String>,
        thisGestureRules: Set<String>,
        weChatMiniProgramGuard: Boolean
    ): String? {
        if (foreground == null) return null
        if (weChatMiniProgramGuard && isWeChatMiniProgram(foreground)) {
            return "wechat miniprogram class=${foreground.className} process=${foreground.processName}"
        }
        val detail = "class=${foreground.className} process=${foreground.processName}"
        val allMatch = allGestureRules.firstOrNull { ruleMatches(it, foreground) }
        if (allMatch != null) return "all-gestures rule $allMatch $detail"
        val matched = thisGestureRules.firstOrNull { ruleMatches(it, foreground) } ?: return null
        return "gesture rule $matched $detail"
    }

    fun isWeChatMiniProgram(foreground: Foreground): Boolean {
        val inWeChat = foreground.packageName == WECHAT_PACKAGE ||
                foreground.processName.startsWith("$WECHAT_PACKAGE:")
        if (!inWeChat) return false
        if (matchesFamily(foreground.processName, WECHAT_APPBRAND_PROCESS)) return true
        if (matchesFamily(foreground.className, WECHAT_APPBRAND_UI)) return true
        if (matchesFamily(foreground.className, WECHAT_APPBRAND_GAME_UI)) return true
        if (matchesFamily(foreground.className, WECHAT_APPBRAND_LAUNCH_PROXY)) return true
        return false
    }

    fun ruleMatches(rule: String, foreground: Foreground): Boolean {
        if (rule == foreground.packageName || rule == foreground.className || rule == foreground.processName) {
            return true
        }
        if (':' in rule) return matchesFamily(foreground.processName, rule)
        return matchesFamily(foreground.className, rule)
    }

    /**
     * 命中前缀本身，或前缀后再接纯数字（AppBrandUI2、:appbrand0）。
     * 后面若是字母，则是另一个名字，不命中。
     */
    internal fun matchesFamily(value: String, prefix: String): Boolean {
        if (prefix.isEmpty() || value == prefix) return value == prefix && prefix.isNotEmpty()
        if (!value.startsWith(prefix)) return false
        val rest = value.substring(prefix.length)
        return rest.isNotEmpty() && rest.all { it.isDigit() }
    }
}

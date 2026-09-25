package io.github.benbaobaoshigemi.rotationcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFilterPolicyTest {
    private val launcher = fg(
        className = "com.tencent.mm.ui.LauncherUI",
        processName = "com.tencent.mm"
    )
    private val chatting = fg(
        className = "com.tencent.mm.ui.chatting.ChattingUI",
        processName = "com.tencent.mm"
    )
    private val webView = fg(
        className = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
        processName = "com.tencent.mm"
    )
    private val mini0 = fg(
        className = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
        processName = "com.tencent.mm:appbrand0"
    )
    private val mini4 = fg(
        className = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI4",
        processName = "com.tencent.mm:appbrand4"
    )
    private val game = fg(
        className = "com.tencent.mm.plugin.appbrand.ui.AppBrandGameUI",
        processName = "com.tencent.mm:appbrand1"
    )
    private val launching = fg(
        className = "com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI",
        processName = "com.tencent.mm"
    )

    @Test
    fun weChatGuardBlocksMiniProgramsAndLeavesTheMainAppAlone() {
        assertNull(reason(launcher, guard = true))
        assertNull(reason(chatting, guard = true))
        assertNull(reason(webView, guard = true))
        assertTrue(reason(mini0, guard = true)!!.startsWith("wechat miniprogram"))
        assertTrue(reason(mini4, guard = true)!!.startsWith("wechat miniprogram"))
        assertTrue(reason(game, guard = true)!!.startsWith("wechat miniprogram"))
        assertTrue(reason(launching, guard = true)!!.startsWith("wechat miniprogram"))
    }

    @Test
    fun weChatGuardCanBeTurnedOff() {
        assertNull(reason(mini0, guard = false))
        assertNull(reason(mini4, guard = false))
        assertNull(reason(launching, guard = false))
    }

    @Test
    fun classMatchStillWorksWhenProcessLookupFallsBackToThePackage() {
        val unresolved = fg(
            className = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI2",
            processName = "com.tencent.mm"
        )
        assertTrue(reason(unresolved, guard = true)!!.contains("AppBrandUI2"))
    }

    @Test
    fun checkingTheWholeWeChatPackageStillBlocksChats() {
        assertTrue(reason(launcher, guard = true, "com.tencent.mm")!!.startsWith("rule com.tencent.mm"))
        assertTrue(reason(chatting, guard = false, "com.tencent.mm")!!.startsWith("rule "))
    }

    @Test
    fun activityAndProcessRulesDoNotSwallowTheRestOfWeChat() {
        val activity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
        assertTrue(ForegroundFilterPolicy.ruleMatches(activity, mini0))
        assertTrue(ForegroundFilterPolicy.ruleMatches(activity, mini4))
        assertFalse(ForegroundFilterPolicy.ruleMatches(activity, launcher))
        assertFalse(ForegroundFilterPolicy.ruleMatches(activity, webView))

        assertTrue(ForegroundFilterPolicy.ruleMatches("com.tencent.mm:appbrand0", mini0))
        assertFalse(ForegroundFilterPolicy.ruleMatches("com.tencent.mm:appbrand0", mini4))
        assertTrue(ForegroundFilterPolicy.ruleMatches("com.tencent.mm:appbrand", mini0))
        assertTrue(ForegroundFilterPolicy.ruleMatches("com.tencent.mm:appbrand", mini4))
        assertFalse(ForegroundFilterPolicy.ruleMatches("com.tencent.mm:appbrand", launcher))
        assertFalse(ForegroundFilterPolicy.ruleMatches("com.tencent.mm.ui", launcher))
    }

    @Test
    fun otherPackagesAreNotTreatedAsWeChat() {
        val other = ForegroundFilterPolicy.Foreground(
            packageName = "com.example.browser",
            className = "com.example.browser.MainActivity",
            processName = "com.example.browser"
        )
        assertNull(reason(other, guard = true))
        assertTrue(reason(other, guard = true, "com.example.browser")!!.startsWith("rule "))
    }

    @Test
    fun canonicalizeAcceptsPackageActivityProcessAndDumpsysForms() {
        assertEquals("com.tencent.mm", ForegroundFilterPolicy.canonicalizeRule(" com.tencent.mm "))
        assertEquals("com.tencent.mm:appbrand0", ForegroundFilterPolicy.canonicalizeRule("com.tencent.mm:appbrand0"))
        assertEquals(
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            ForegroundFilterPolicy.canonicalizeRule("com.tencent.mm/.plugin.appbrand.ui.AppBrandUI")
        )
        assertEquals(
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI2",
            ForegroundFilterPolicy.canonicalizeRule(
                "com.tencent.mm/com.tencent.mm.plugin.appbrand.ui.AppBrandUI2"
            )
        )
        assertNull(ForegroundFilterPolicy.canonicalizeRule("com.tencent.mm:"))
        assertNull(ForegroundFilterPolicy.canonicalizeRule("wechat"))
        assertNull(ForegroundFilterPolicy.canonicalizeRule("com.tencent.mm:appbrand:extra"))
        assertTrue(ForegroundFilterPolicy.isValidRule("com.tencent.mm:appbrand"))
        assertFalse(ForegroundFilterPolicy.isValidRule("com.tencent.mm/.plugin.appbrand.ui.AppBrandUI"))
    }

    private fun fg(className: String, processName: String) = ForegroundFilterPolicy.Foreground(
        packageName = "com.tencent.mm",
        className = className,
        processName = processName
    )

    private fun reason(
        foreground: ForegroundFilterPolicy.Foreground,
        guard: Boolean,
        vararg rules: String
    ) = ForegroundFilterPolicy.suppressionReason(foreground, rules.toSet(), guard)
}

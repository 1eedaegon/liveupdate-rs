package io.github.gon.liveupdate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    // LU-V2-LINK-1
    @Test
    fun aLinkIsAnAbsolutePrintableAsciiUrlWithAnAllowedLowercaseScheme() {
        val longest = "a:" + "x".repeat(2_046)
        for (url in listOf(
            "myapp://orders/42",
            "https://example.com/orders/42?tab=map#eta",
            "a1+b.c-d:x",
            "app:!~",
            longest,
        )) {
            assertTrue(url, isValidLink(url))
        }
        for (url in listOf(
            "",
            "orders/42",
            ":orders",
            "myapp:",
            "MyApp://orders",
            "myApp://orders",
            "1app://orders",
            "my_app://orders",
            "myapp://orders 42",
            "myapp://orders\t42",
            "myapp://orders\u007f",
            "myapp://주문",
            "intent://orders#Intent;end",
            "content://provider/1",
            "file:///data/x",
            "javascript:alert(1)",
            "data:text/plain,x",
            "android-app://package/x",
            longest + "x",
        )) {
            assertFalse(url, isValidLink(url))
        }
    }
}

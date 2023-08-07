package android.net.cts

import android.Manifest.permission.NETWORK_SETTINGS
import android.os.Build
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.PermissionUtils
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class PermissionUtilsTest {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }

    @Test
    fun testIsSystemSignaturePermission() {
        assertTrue(PermissionUtils.isSystemSignaturePermission(context, NETWORK_SETTINGS))
        assertFalse(
            PermissionUtils
                .isSystemSignaturePermission(context, "android.permission.MAINLINE_NETWORK_STACK")
        )
    }
}

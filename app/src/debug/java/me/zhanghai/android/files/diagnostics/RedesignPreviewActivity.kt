package me.zhanghai.android.files.diagnostics

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import me.zhanghai.android.files.design.DevicesDestination
import me.zhanghai.android.files.design.TransfersDestination
import me.zhanghai.android.files.features.FeatureActivity

internal object RedesignPreviewConfig { var fontScale = 1.5f }

/** Exercises the production destinations with a local font override, never a device setting. */
class RedesignPreviewActivity : FeatureActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration)
        configuration.fontScale = RedesignPreviewConfig.fontScale
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName.endsWith(".redesignqa"))
        composeScreen {
            if (intent.getBooleanExtra("transfers", false)) TransfersDestination { finish() }
            else DevicesDestination()
        }
    }
}

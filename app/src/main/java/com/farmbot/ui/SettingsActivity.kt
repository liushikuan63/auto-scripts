package com.farmbot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.farmbot.config.ConfigManager
import com.farmbot.config.FarmConfig
import com.farmbot.ui.theme.FarmTheme

/**
 * 设置页宿主 Activity
 *
 * 以 Compose 承载设置界面（FarmTheme + SettingsContent），
 * 负责把配置读写接到 ConfigManager 上。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFFF5F5F5)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF5F5F5))
                    ) {
                        var currentConfig by remember {
                            mutableStateOf(ConfigManager.getConfig())
                        }

                        SettingsContent(
                            config = currentConfig,
                            onConfigChange = { newConfig ->
                                currentConfig = newConfig
                            },
                            onClose = {
                                ConfigManager.save(currentConfig)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }
}

package com.shahar.shaharsat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.shahar.shaharsat.ui.nav.SatNavHost
import com.shahar.shaharsat.ui.theme.MissionColors
import com.shahar.shaharsat.ui.theme.ShaharSatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as SatApplication).container

        setContent {
            ShaharSatTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MissionColors.Background) {
                    SatNavHost(container)
                }
            }
        }
    }
}

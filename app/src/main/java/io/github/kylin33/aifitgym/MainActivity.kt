package io.github.kylin33.aifitgym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kylin33.aifitgym.ui.FitGymRoot
import io.github.kylin33.aifitgym.ui.theme.FitGymTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FitGymApp).container
        setContent {
            FitGymTheme {
                FitGymRoot(container)
            }
        }
    }
}

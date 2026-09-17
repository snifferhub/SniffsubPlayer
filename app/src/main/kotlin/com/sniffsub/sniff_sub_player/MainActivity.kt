package com.sniffsub.sniff_sub_player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.sniffsub.sniff_sub_player.ui.theme.SniffSubPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SniffSubPlayerTheme {
                Text("SniffSubPlayer")
            }
        }
    }
}

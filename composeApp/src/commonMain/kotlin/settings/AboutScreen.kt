package com.sniffsub.sniff_sub_player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sniffsub.sniff_sub_player.about.AboutInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    Scaffold(topBar = { SettingsTopBar(title = "关于 SniffSubPlayer", onBack = onBack) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp),
        ) {
            SettingsSection {
                SettingsRow(label = "版本", value = version)
                HorizontalDivider()
                SettingsRow(label = "源代码", onClick = { onOpenLink(AboutInfo.sourceUrl) })
                HorizontalDivider()
                SettingsRow(label = "版本发布", onClick = { onOpenLink(AboutInfo.releasesUrl) })
                HorizontalDivider()
                SettingsRow(
                    label = "开源许可",
                    value = AboutInfo.licenseName,
                    onClick = { onOpenLink(AboutInfo.licenseUrl) },
                )
            }
            SettingsSection(title = "开源项目") {
                AboutInfo.androidProjects.forEachIndexed { index, project ->
                    if (index > 0) HorizontalDivider()
                    SettingsRow(label = project.name, onClick = { onOpenLink(project.url) })
                }
            }
        }
    }
}

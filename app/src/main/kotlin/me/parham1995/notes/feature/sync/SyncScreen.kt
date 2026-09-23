package me.parham1995.notes.feature.sync

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.data.SyncTransport
import me.parham1995.notes.ui.icon.LucideGlyph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    onManageSshKeys: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The access token is on screen here, so keep it out of the recents
    // thumbnail and out of screenshots.
    val context = LocalContext.current
    DisposableEffect(section) {
        val secret = section == SettingsSection.REPOSITORIES
        val window = (context as? Activity)?.window
        if (secret) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { if (secret) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LucideGlyph(
                            "arrow-left",
                            size = 22.dp,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                SettingsSection.REPOSITORIES -> {
                    RepositoryCard(state, viewModel)
                    TransportCard(state, viewModel)
                    if (state.settings.transport == SyncTransport.REST) {
                        TokenCard(state, viewModel)
                    } else {
                        SshKeyCard(state, onManageSshKeys)
                    }
                }

                SettingsSection.READING -> ReadingCard(state, viewModel)

                SettingsSection.WRITING -> {
                    WritingCard(state, viewModel)
                    ScratchpadCard(state, viewModel)
                    if (state.queuedEdits.isNotEmpty()) QueuedEditsCard(state, viewModel)
                }

                SettingsSection.SYNC -> {
                    BackgroundSyncCard(state, viewModel)
                    ImagesCard(state, viewModel)
                    StatusCard(state, viewModel)
                }

                SettingsSection.NOTIFICATIONS -> TaskDigestCard(state, viewModel)

                SettingsSection.ADVANCED -> {
                    state.lastCrash?.let { CrashCard(it, viewModel) }
                    LogCard(viewModel)
                    AboutCard()
                }
            }
        }
    }
}

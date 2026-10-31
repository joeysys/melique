package com.hiwtata.melique.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiwtata.melique.R
import com.hiwtata.melique.data.Access
import com.hiwtata.melique.ui.theme.LocalLibraryColors
import com.hiwtata.melique.ui.theme.Paper
import kotlinx.coroutines.delay

private enum class Sheet { Quick, Queue }

@Composable
fun SonaApp(vm: MainViewModel) {
    val context = LocalContext.current
    val audioGranted by vm.audioGranted.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val playerOpen by vm.playerOpen.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val cache = remember { ArtCache(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refreshAccess() }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        vm.onSystemDeleteResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.systemDeletes.collect { sender ->
            deleteLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
            )
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { text ->
            toast = text
            delay(2400)
            toast = null
        }
    }
    LaunchedEffect(audioGranted) {
        if (!audioGranted) permissionLauncher.launch(Access.audioPermissions())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val view = LocalView.current
    val library = LocalLibraryColors.current
    val barsDark = playerOpen || isSystemInDarkTheme()
    val activity = context as ComponentActivity
    SideEffect {
        activity.enableEdgeToEdge(
            statusBarStyle = if (barsDark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (barsDark) {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            },
        )
    }
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = playerOpen && sheet == null) { vm.closePlayer() }
    BackHandler(enabled = sheet != null) { sheet = null }

    androidx.compose.runtime.CompositionLocalProvider(LocalArtCache provides cache) {
        Box(Modifier.fillMaxSize().background(library.background)) {
            if (!audioGranted) {
                PermissionGate(
                    onRequest = { permissionLauncher.launch(Access.audioPermissions()) },
                )
            } else {
                MaterialTheme(colorScheme = library.scheme) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    LibraryPane(
                        vm = vm,
                        bottomInset = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        onQuick = { sheet = Sheet.Quick },
                        onAllFiles = {
                            runCatching { context.startActivity(Access.allFilesIntent(context)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (!playerOpen) {
                        MiniPlayer(
                            vm = vm,
                            onOpen = vm::openPlayer,
                            modifier = Modifier.navigationBarsPadding(),
                        )
                    }
                }
                }
            }
            if (playerOpen) {
                PlayerPane(vm = vm)
            }
            if (sheet == Sheet.Quick) {
                MaterialTheme(colorScheme = library.scheme) {
                    QuickSheet(vm) { sheet = null }
                }
            }
            if (audioGranted) {
                UndoStrip(
                    vm,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (playerOpen) 12.dp else 64.dp),
                )
            }
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            ) {
                Text(
                    toast.orEmpty(),
                    color = Paper,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .consumeTouches()
                        .floatingNotice()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val library = LocalLibraryColors.current
        Text("Melique", color = library.onBackground, fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.permission_body), color = library.faint, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.grant_permission),
            color = library.onAccent,
            modifier = Modifier
                .clipClickable(onRequest),
        )
    }
}

@Composable
private fun Modifier.clipClickable(onClick: () -> Unit): Modifier {
    return this
        .clickable(onClick = onClick)
        .background(LocalLibraryColors.current.accent, RoundedCornerShape(14.dp))
        .padding(horizontal = 18.dp, vertical = 12.dp)
}

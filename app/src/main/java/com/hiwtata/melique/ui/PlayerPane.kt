package com.hiwtata.melique.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hiwtata.melique.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.Song
import com.hiwtata.melique.player.formatSleepRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hiwtata.melique.ui.theme.Faint
import com.hiwtata.melique.ui.theme.KeepTone
import com.hiwtata.melique.ui.theme.Muted
import com.hiwtata.melique.ui.theme.LocalLibraryColors
import com.hiwtata.melique.ui.theme.Paper
import com.hiwtata.melique.ui.theme.PendingTone

@Composable
fun PlayerPane(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val index by vm.queueIndex.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val shuffleOn by vm.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by vm.repeatMode.collectAsStateWithLifecycle()
    val gestureLocked by vm.gestureLocked.collectAsStateWithLifecycle()
    val deleteGesture by vm.deleteGesture.collectAsStateWithLifecycle()
    val sleepDeadline by vm.sleepDeadlineElapsed.collectAsStateWithLifecycle()
    val sleepUntilEnd by vm.sleepUntilTrackEnds.collectAsStateWithLifecycle()
    val timerOn = sleepDeadline != null || sleepUntilEnd
    val context = LocalContext.current
    var sleepOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val song = queue.getOrNull(index)
    val mark = song?.let { marks[it.key] } ?: Disposition.NONE
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var coverShiftX by remember { mutableFloatStateOf(0f) }
    var coverShiftY by remember { mutableFloatStateOf(0f) }
    var coverScale by remember { mutableFloatStateOf(1f) }
    var selectMix by remember { mutableFloatStateOf(if (selecting) 1f else 0f) }
    LaunchedEffect(selecting) {
        glide(selectMix, if (selecting) 1f else 0f, 0f) { selectMix = it }
    }
    val modeMix = selectMix
    var playerShift by remember { mutableFloatStateOf(0f) }
    var queueShift by remember { mutableFloatStateOf(0f) }
    var queueShown by remember { mutableStateOf(false) }
    var queueReady by remember { mutableStateOf(false) }
    var rootHeight by remember { mutableFloatStateOf(1f) }
    var coverWidth by remember { mutableFloatStateOf(1f) }
    var motionBusy by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    val cache = rememberArtCache()
    var visual by remember { mutableStateOf<ArtVisual?>(null) }
    var previousVisual by remember { mutableStateOf<ArtVisual?>(null) }
    var nextVisual by remember { mutableStateOf<ArtVisual?>(null) }
    var successorVisual by remember { mutableStateOf<ArtVisual?>(null) }
    LaunchedEffect(song?.key, shuffleOn, repeatMode) {
        val current = song ?: return@LaunchedEffect
        visual = cache.load(current)
        nextVisual = vm.neighborSong(true)?.let { cache.load(it) }
        previousVisual = vm.neighborSong(false)?.let { cache.load(it) }
    }
    LaunchedEffect(deleteGesture?.songKey) {
        successorVisual = if (deleteGesture == null) {
            null
        } else {
            vm.songAfterAlbumDelete()?.let { cache.load(it) }
        }
    }
    val accent = visual?.accent ?: Color(0xFF8A7564)
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.height > 0) rootHeight = size.height.toFloat()
            }
            .graphicsLayer { translationY = playerShift }
            .consumeTouches()
            .background(accent.shade(0.45f)),
    ) {
        Backdrop(visual)
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        motionBusy = true
                        glide(playerShift, rootHeight, 0f) { playerShift = it }
                        vm.closePlayer()
                        motionBusy = false
                    }
                }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.collapse), tint = Paper)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::toggleSelecting) {
                    if (selecting) {
                        Box(
                            Modifier.size(36.dp).background(Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Swipe,
                                contentDescription = stringResource(R.string.leave_select),
                                tint = Color(0xFF141311),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Outlined.Swipe,
                            contentDescription = stringResource(R.string.select_songs),
                            tint = Color.White,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu), tint = Paper)
                    }
                    if (timerOn) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 12.dp, end = 12.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(PendingTone),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue)) },
                            onClick = {
                                menuOpen = false
                                if (queueShown) return@DropdownMenuItem
                                scope.launch {
                                    queueShift = rootHeight
                                    queueShown = true
                                    glide(rootHeight, 0f, 0f) { queueShift = it }
                                    queueReady = true
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.keep_album)) },
                            onClick = {
                                menuOpen = false
                                vm.keepAlbum()
                            },
                        )
                        if (mark == Disposition.KEEP) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_keep)) },
                                onClick = {
                                    menuOpen = false
                                    vm.clearKeep()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(if (timerOn) R.string.timer_running else R.string.timer)) },
                            onClick = {
                                menuOpen = false
                                sleepOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (gestureLocked) R.string.unlock else R.string.lock_screen)) },
                            onClick = {
                                menuOpen = false
                                vm.toggleGestureLock()
                            },
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val side = minOf(maxWidth * 0.82f, maxHeight * 0.42f)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Spacer(Modifier.weight(0.5f))
                if (song != null) {
                    val cover = visual?.cover
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    coverWidth = with(density) { side.toPx() }
                    val threshold = with(density) { 72.dp.toPx() }
                    Box(
                        Modifier
                            .size(side)
                            .graphicsLayer {
                                translationY = coverShiftY
                                val fitted = coverScale * (1f - 0.04f * modeMix)
                                scaleX = fitted
                                scaleY = fitted
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(side)
                                .shadow(32.dp, RoundedCornerShape(8.dp), ambientColor = accent, spotColor = accent)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF161616))
                                .coverDrag(
                                    enabled = !gestureLocked && !busy && !motionBusy,
                                    onMove = { drag ->
                                        gestureHint = gestureCaption(
                                            context,
                                            drag,
                                            selecting = selecting,
                                            confirming = deleteGesture != null,
                                            kept = mark == Disposition.KEEP,
                                        )
                                        when (drag.axis) {
                                            CoverAxis.Horizontal -> {
                                                coverShiftX = drag.dx
                                                coverShiftY = 0f
                                                playerShift = 0f
                                            }
                                            CoverAxis.Vertical -> {
                                                coverShiftX = 0f
                                                when {
                                                    drag.dy > 0f && deleteGesture == null -> {
                                                        playerShift = drag.dy
                                                        coverShiftY = 0f
                                                    }
                                                    drag.dy < 0f && deleteGesture == null && !selecting -> {
                                                        queueShift = (rootHeight + drag.dy).coerceIn(0f, rootHeight)
                                                        queueShown = true
                                                        coverShiftY = 0f
                                                        playerShift = 0f
                                                    }
                                                    else -> {
                                                        coverShiftY = drag.dy
                                                        playerShift = 0f
                                                    }
                                                }
                                            }
                                            null -> Unit
                                        }
                                    },
                                    onSettle = { drag ->
                                        val confirmingNow = deleteGesture != null
                                        val selectingNow = selecting
                                        val keptNow = mark == Disposition.KEEP
                                        val canForward = confirmingNow || vm.neighborSong(true) != null
                                        val canBack = confirmingNow || vm.neighborSong(false) != null
                                        val fromX = coverShiftX
                                        val fromY = coverShiftY
                                        val fromPlayer = playerShift
                                        val fromQueue = queueShift
                                        scope.launch {
                                            motionBusy = true
                                            followRelease(
                                                drag = drag,
                                                threshold = threshold,
                                                coverWidth = coverWidth,
                                                rootHeight = rootHeight,
                                                selecting = selectingNow,
                                                confirming = confirmingNow,
                                                kept = keptNow,
                                                canForward = canForward,
                                                canBack = canBack,
                                                fromX = fromX,
                                                fromY = fromY,
                                                fromPlayer = fromPlayer,
                                                fromQueue = fromQueue,
                                                writeX = { coverShiftX = it },
                                                writeY = { coverShiftY = it },
                                                writePlayer = { playerShift = it },
                                                writeQueue = { queueShift = it },
                                                onAction = { action ->
                                                    if (action != CoverAction.Ignore) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                    vm.cover(action)
                                                },
                                                onHandoff = { forward ->
                                                    if (forward && nextVisual != null) {
                                                        previousVisual = visual
                                                        visual = nextVisual
                                                    } else if (!forward && deleteGesture != null) {
                                                        if (successorVisual != null) {
                                                            nextVisual = visual
                                                            visual = successorVisual
                                                        }
                                                    } else if (!forward && previousVisual != null) {
                                                        nextVisual = visual
                                                        visual = previousVisual
                                                    }
                                                },
                                                onQueue = { open ->
                                                    queueShown = open
                                                    queueReady = open
                                                },
                                            )
                                            gestureHint = null
                                            motionBusy = false
                                        }
                                    },
                                    onTap = {},
                                    onLong = {
                                        if (!selecting || deleteGesture != null || motionBusy) return@coverDrag
                                        val keeping = song ?: return@coverDrag
                                        scope.launch {
                                            motionBusy = true
                                            vm.markSong(keeping, Disposition.KEEP)
                                            gestureHint = HINT_KEEP
                                            delay(480)
                                            gestureHint = null
                                            if (vm.neighborSong(true) != null) {
                                                slideTrack(coverShiftX, coverWidth, forward = true, velocity = -coverWidth * 5f, write = { coverShiftX = it }) {
                                                    if (nextVisual != null) {
                                                        previousVisual = visual
                                                        visual = nextVisual
                                                    }
                                                    vm.cover(CoverAction.Next)
                                                }
                                            }
                                            motionBusy = false
                                        }
                                    },
                                ),
                        ) {
                            Box(Modifier.fillMaxSize().graphicsLayer { translationX = coverShiftX }) {
                                val leftVisual = if (deleteGesture != null) successorVisual else previousVisual
                                leftVisual?.cover?.let { bitmap ->
                                    Image(
                                        bitmap,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().graphicsLayer { translationX = -coverWidth },
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (cover != null) {
                                    Image(
                                        cover,
                                        contentDescription = song.displayAlbum(LocalContext.current),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                nextVisual?.cover?.let { bitmap ->
                                    Image(
                                        bitmap,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().graphicsLayer { translationX = coverWidth },
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (mark == Disposition.PENDING && deleteGesture == null) {
                                    MarkChip(
                                        mark,
                                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                                    )
                                }
                                deleteGesture?.let { gesture ->
                                val albumEnabled = gesture.albumSongs.isNotEmpty()
                                val albumTone = if (albumEnabled) Paper else Faint
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
                                    Column(
                                        Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(Icons.Filled.SwipeLeft, contentDescription = stringResource(R.string.swipe_delete_song), tint = Paper, modifier = Modifier.size(48.dp))
                                        Text(stringResource(R.string.this_song), color = Paper, fontSize = 14.sp)
                                    }
                                    Column(
                                        Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(Icons.Filled.SwipeRight, contentDescription = stringResource(R.string.swipe_delete_album), tint = albumTone, modifier = Modifier.size(48.dp))
                                        Text(stringResource(R.string.delete_count, gesture.albumSongs.size), color = albumTone, fontSize = 14.sp)
                                        Text(stringResource(R.string.keep_count_short, gesture.kept), color = albumTone, fontSize = 14.sp)
                                    }
                                    Column(
                                        Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(Icons.Filled.SwipeDown, contentDescription = stringResource(R.string.swipe_cancel), tint = Paper, modifier = Modifier.size(48.dp))
                                        Text(stringResource(R.string.cancel), color = Paper, fontSize = 14.sp)
                                    }
                                }
                            }
                            val hint = gestureHint
                            if (hint != null && deleteGesture == null) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(accent.shade(0.22f).copy(alpha = 0.62f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (hint == HINT_KEEP) {
                                        Icon(
                                            Icons.Filled.Favorite,
                                            contentDescription = hint,
                                            tint = KeepTone,
                                            modifier = Modifier.size(72.dp),
                                        )
                                    } else {
                                        Text(
                                            hint,
                                            color = Paper,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(0.85f))
            Column(
                Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(40.dp))
                Text(
                    song?.title ?: stringResource(R.string.nothing_playing),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(enabled = song != null, onClick = vm::toggleKeep),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (mark == Disposition.KEEP) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(if (mark == Disposition.KEEP) R.string.clear_keep else R.string.keep),
                        tint = if (mark == Disposition.KEEP) KeepTone else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                song?.displayArtist(LocalContext.current) ?: " ",
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                song?.displayAlbum(LocalContext.current) ?: " ",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            }
            Spacer(Modifier.weight(0.42f))
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                SeekRow(vm, enabled = song != null)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .graphicsLayer { alpha = 1f - 0.55f * modeMix },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = stringResource(if (shuffleOn) R.string.turn_shuffle_off else R.string.turn_shuffle_on),
                        tint = if (shuffleOn) Color.White else Color.White.copy(alpha = 0.38f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (motionBusy) return@IconButton
                        scope.launch {
                            motionBusy = true
                            if (vm.neighborSong(false) != null) {
                                slideTrack(coverShiftX, coverWidth, forward = false, velocity = coverWidth * 5f, write = { coverShiftX = it }) {
                                    if (previousVisual != null) {
                                        nextVisual = visual
                                        visual = previousVisual
                                    }
                                    vm.previous()
                                }
                            } else {
                                vm.previous()
                            }
                            motionBusy = false
                        }
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(28.dp))
                }
                PlayPauseButton(playing, vm::toggle)
                IconButton(
                    onClick = {
                        if (motionBusy) return@IconButton
                        scope.launch {
                            motionBusy = true
                            if (vm.neighborSong(true) != null) {
                                slideTrack(coverShiftX, coverWidth, forward = true, velocity = -coverWidth * 5f, write = { coverShiftX = it }) {
                                    if (nextVisual != null) {
                                        previousVisual = visual
                                        visual = nextVisual
                                    }
                                    vm.next()
                                }
                            } else {
                                vm.next()
                            }
                            motionBusy = false
                        }
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.next), tint = Color.White, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = vm::cycleRepeat, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                        contentDescription = when (repeatMode) {
                            Player.REPEAT_MODE_ALL -> stringResource(R.string.repeat_all)
                            Player.REPEAT_MODE_ONE -> stringResource(R.string.repeat_one)
                            else -> stringResource(R.string.play_in_order)
                        },
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.38f) else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.weight(0.72f))
            }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(10.dp))
        }
        if (queueShown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = queueShift },
            ) {
                QueueSheet(
                    vm = vm,
                    interactive = queueReady,
                    scrimAlpha = 0f,
                    onDismiss = {
                        scope.launch {
                            queueReady = false
                            glide(queueShift, rootHeight, 0f) { queueShift = it }
                            queueShown = false
                        }
                    },
                )
            }
            BackHandler {
                scope.launch {
                    queueReady = false
                    glide(queueShift, rootHeight, 0f) { queueShift = it }
                    queueShown = false
                }
            }
        }
        if (deleteGesture != null) {
            BackHandler { vm.cancelDeleteGesture() }
        }
        if (sleepOpen) {
            SleepSheet(vm) { sleepOpen = false }
        }
    }
}

@Composable
private fun Backdrop(visual: ArtVisual?) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val scale = transition.animateFloat(
        initialValue = 1.12f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val drift = transition.animateFloat(
        initialValue = -28f,
        targetValue = 28f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val blur = visual?.blur
    val filter = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.92f, 0f, 0f, 0f, 0f,
                0f, 0.92f, 0f, 0f, 0f,
                0f, 0f, 0.92f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    Box(
        Modifier.fillMaxSize().clipToBounds().drawBehind {
            if (blur != null) {
                val fitted = maxOf(size.width / blur.width, size.height / blur.height)
                val srcW = size.width / fitted
                val srcH = size.height / fitted
                scale(scale.value, scale.value, pivot = center) {
                    translate(left = drift.value, top = drift.value * 0.35f) {
                        drawImage(
                            blur,
                            srcOffset = androidx.compose.ui.unit.IntOffset(
                                ((blur.width - srcW) / 2f).toInt(),
                                ((blur.height - srcH) / 2f).toInt(),
                            ),
                            srcSize = androidx.compose.ui.unit.IntSize(srcW.toInt().coerceAtLeast(1), srcH.toInt().coerceAtLeast(1)),
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                            colorFilter = filter,
                        )
                    }
                }
            }
            drawRect(Color.Black.copy(alpha = 0.18f))
        },
    )
}

@Composable
private fun PlayPauseButton(playing: Boolean, onToggle: () -> Unit) {
    val rotation = remember { Animatable(if (playing) 180f else 0f) }
    LaunchedEffect(playing) {
        rotation.animateTo(
            if (playing) 180f else 0f,
            tween(360, easing = FastOutSlowInEasing),
        )
    }
    val angle = rotation.value
    val turned = ((angle % 360f) + 360f) % 360f in 90f..270f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        Modifier
            .size(64.dp)
            .border(1.2.dp, Color.White.copy(alpha = 0.92f), CircleShape)
            .pointerInput(onToggle) { detectTapGestures(onTap = { onToggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (turned) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (turned) R.string.pause else R.string.play),
            tint = Color.White,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    rotationY = if (turned) angle + 180f else angle
                    cameraDistance = 5.5f * density
                    if (!turned) translationX = 1.5.dp.toPx()
                },
        )
    }
}

@Composable
private fun SeekRow(vm: MainViewModel, enabled: Boolean) {
    val position by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    val shown = if (seeking) seek.toLong() else position
    val range = duration.coerceAtLeast(1L).toFloat()
    val canSeek = enabled && duration > 0L
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(canSeek, range) {
                    if (!canSeek) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(x: Float) = (x / size.width).coerceIn(0f, 1f) * range
                        seeking = true
                        seek = at(down.position.x)
                        val finished = drag(down.id) { change ->
                            seek = at(change.position.x)
                            change.consume()
                        }
                        if (finished) vm.seekTo(seek.toLong())
                        seeking = false
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val fraction = (shown / range).coerceIn(0f, 1f)
                val radius = 7.dp.toPx()
                val stroke = 2.5.dp.toPx()
                val centerY = size.height / 2f
                val centerX = radius + (size.width - radius * 2f) * fraction
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(0f, centerY),
                    end = Offset(centerX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawCircle(Color.White, radius, Offset(centerX, centerY))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(formatTime(shown), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val index by vm.queueIndex.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val sleepDeadline by vm.sleepDeadlineElapsed.collectAsStateWithLifecycle()
    val sleepUntilEnd by vm.sleepUntilTrackEnds.collectAsStateWithLifecycle()
    val sleepStatus = rememberSleepStatus(sleepDeadline, sleepUntilEnd)
    val song = queue.getOrNull(index)
    if (song == null) return
    val colors = LocalLibraryColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(colors.card.copy(alpha = 0.94f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song, Modifier.size(46.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(
                if (sleepStatus == null) {
                    song.displayArtist(LocalContext.current)
                } else {
                    stringResource(R.string.dot_join, song.displayArtist(LocalContext.current), sleepStatus)
                },
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = vm::toggle) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                tint = colors.accent,
            )
        }
    }
}

private val follow = spring<Float>(
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = 0.5f,
)

private suspend fun glide(from: Float, to: Float, velocity: Float, write: (Float) -> Unit) {
    if (kotlin.math.abs(to - from) < 0.5f && kotlin.math.abs(velocity) < 40f) {
        write(to)
        return
    }
    AnimationState(initialValue = from, initialVelocity = velocity).animateTo(to, follow) {
        write(value)
    }
    write(to)
}

private suspend fun slideTrack(
    from: Float,
    width: Float,
    forward: Boolean,
    velocity: Float,
    write: (Float) -> Unit,
    commit: () -> Unit,
) {
    val target = if (forward) -width else width
    glide(from, target, velocity, write)
    commit()
    write(0f)
}

private suspend fun followRelease(
    drag: CoverDrag,
    threshold: Float,
    coverWidth: Float,
    rootHeight: Float,
    selecting: Boolean,
    confirming: Boolean,
    kept: Boolean,
    canForward: Boolean,
    canBack: Boolean,
    fromX: Float,
    fromY: Float,
    fromPlayer: Float,
    fromQueue: Float,
    writeX: (Float) -> Unit,
    writeY: (Float) -> Unit,
    writePlayer: (Float) -> Unit,
    writeQueue: (Float) -> Unit,
    onAction: (CoverAction) -> Unit,
    onHandoff: (Boolean) -> Unit,
    onQueue: (Boolean) -> Unit,
) {
    val fling = 1200f
    when (drag.axis) {
        CoverAxis.Horizontal -> {
            val target = when {
                drag.vx <= -fling -> -coverWidth
                drag.vx >= fling -> coverWidth
                drag.dx <= -threshold -> -coverWidth
                drag.dx >= threshold -> coverWidth
                else -> 0f
            }
            val forward = target < 0f
            val allowed = target == 0f || if (forward) canForward else canBack
            if (!allowed || target == 0f) {
                glide(fromX, 0f, drag.vx, writeX)
            } else {
                val input = if (forward) CoverInput.Left else CoverInput.Right
                slideTrack(fromX, coverWidth, forward, drag.vx, writeX) {
                    onHandoff(forward)
                    onAction(interpretCover(input, selecting, confirming, kept))
                }
            }
            if (fromY != 0f) glide(fromY, 0f, 0f, writeY)
        }
        CoverAxis.Vertical -> {
            when {
                drag.dy > 0f && !confirming -> {
                    val dismiss = when {
                        drag.vy >= fling -> true
                        drag.vy <= -fling -> false
                        else -> drag.dy >= threshold
                    }
                    glide(fromPlayer, if (dismiss) rootHeight else 0f, drag.vy, writePlayer)
                    if (dismiss) onAction(CoverAction.Collapse)
                }
                drag.dy > 0f -> {
                    val cancel = when {
                        drag.vy >= fling -> true
                        drag.vy <= -fling -> false
                        else -> drag.dy >= threshold
                    }
                    if (cancel) onAction(CoverAction.CancelDelete)
                    glide(fromY, 0f, drag.vy, writeY)
                }
                drag.dy < 0f && selecting -> {
                    val open = when {
                        drag.vy <= -fling -> true
                        drag.vy >= fling -> false
                        else -> drag.dy <= -threshold
                    }
                    if (open) onAction(CoverAction.OpenDelete)
                    glide(fromY, 0f, drag.vy, writeY)
                }
                drag.dy < 0f -> {
                    val open = when {
                        drag.vy <= -fling -> true
                        drag.vy >= fling -> false
                        else -> drag.dy <= -threshold
                    }
                    glide(fromQueue, if (open) 0f else rootHeight, drag.vy, writeQueue)
                    onQueue(open)
                }
                else -> glide(fromY, 0f, drag.vy, writeY)
            }
            if (fromX != 0f) glide(fromX, 0f, 0f, writeX)
        }
        null -> {
            if (fromX != 0f) glide(fromX, 0f, drag.vx, writeX)
            if (fromY != 0f) glide(fromY, 0f, drag.vy, writeY)
        }
    }
}

private fun Color.shade(factor: Float): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = 1f,
)

private const val HINT_KEEP = "keep"

private fun gestureCaption(
    context: android.content.Context,
    drag: CoverDrag,
    selecting: Boolean,
    confirming: Boolean,
    kept: Boolean,
): String? {
    if (drag.axis == null) return null
    fun label(id: Int) = context.getString(id)
    if (confirming) {
        return when {
            drag.axis == CoverAxis.Horizontal && drag.dx < 0f -> label(R.string.caption_delete_song)
            drag.axis == CoverAxis.Horizontal && drag.dx > 0f -> label(R.string.caption_delete_album)
            drag.dy > 0f -> label(R.string.cancel)
            else -> null
        }
    }
    if (selecting) {
        return when {
            drag.axis == CoverAxis.Horizontal -> label(if (kept) R.string.caption_skip else R.string.pending)
            drag.dy < 0f -> label(R.string.caption_delete)
            drag.dy > 0f -> label(R.string.collapse)
            else -> null
        }
    }
    return when {
        drag.axis == CoverAxis.Horizontal && drag.dx < 0f -> label(R.string.next)
        drag.axis == CoverAxis.Horizontal -> label(R.string.previous)
        drag.dy < 0f -> label(R.string.queue)
        drag.dy > 0f -> label(R.string.collapse)
        else -> null
    }
}

@Composable
internal fun rememberSleepStatus(deadlineElapsed: Long?, untilTrackEnds: Boolean): String? {
    val now by produceState(SystemClock.elapsedRealtime(), deadlineElapsed, untilTrackEnds) {
        if (deadlineElapsed == null || untilTrackEnds) {
            value = SystemClock.elapsedRealtime()
            return@produceState
        }
        while (true) {
            value = SystemClock.elapsedRealtime()
            if (value >= deadlineElapsed) break
            delay(1_000)
        }
    }
    val afterSong = stringResource(R.string.pauses_after_song)
    val remaining = deadlineElapsed?.minus(now) ?: return if (untilTrackEnds) afterSong else null
    return when {
        untilTrackEnds -> afterSong
        remaining <= 0L -> null
        else -> stringResource(R.string.pauses_in, formatSleepRemaining(remaining))
    }
}

internal fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000L
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val seconds = safe % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}



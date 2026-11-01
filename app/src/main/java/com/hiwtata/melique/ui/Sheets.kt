package com.hiwtata.melique.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hiwtata.melique.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.QueueMode
import com.hiwtata.melique.data.TrashBatch
import com.hiwtata.melique.data.TrashManager
import com.hiwtata.melique.ui.theme.Card
import com.hiwtata.melique.ui.theme.Faint
import com.hiwtata.melique.ui.theme.LocalLibraryColors
import com.hiwtata.melique.ui.theme.Muted
import com.hiwtata.melique.ui.theme.Paper
import kotlinx.coroutines.delay

@Composable
fun UndoStrip(vm: MainViewModel, modifier: Modifier = Modifier) {
    val batches by vm.trashBatches.collectAsStateWithLifecycle()
    val latest = batches.lastOrNull() ?: return
    UndoStripBody(latest, modifier, vm::undo)
}

@Composable
private fun UndoStripBody(batch: TrashBatch, modifier: Modifier = Modifier, onUndo: () -> Unit) {
    val now by produceState(SystemClock.elapsedRealtime(), batch.token) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(50)
        }
    }
    val remain = (batch.armedAtElapsed + TrashManager.UNDO_WINDOW_MS - now).coerceAtLeast(0L)
    val fraction = remain / TrashManager.UNDO_WINDOW_MS.toFloat()
    val seconds = ((remain + 999) / 1000L).coerceAtLeast(1)
    Column(
        modifier
            .fillMaxWidth()
            .consumeTouches(onUndo)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .floatingNotice()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                batch.label,
                color = Paper,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.undo),
                color = Paper,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                "${seconds}s",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(Paper),
            )
        }
    }
}

internal fun Modifier.floatingNotice(shape: Shape = RoundedCornerShape(18.dp)): Modifier {
    return this
        .clip(shape)
        .background(Color(0xFF141311).copy(alpha = 0.32f))
        .border(1.dp, Paper.copy(alpha = 0.34f), shape)
}

@Composable
internal fun Modifier.consumeTouches(onClick: () -> Unit = {}): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
    )
}

@Composable
fun QuickSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val colors = LocalLibraryColors.current
    var mode by remember { mutableStateOf(QueueMode.ALBUM_SHUFFLE) }
    val library by vm.library.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val liveMarks = remember(library, marks) {
        val keys = library.map { it.key }.toSet()
        marks.filterKeys { it in keys }
    }
    SheetFrame(
        title = stringResource(R.string.quick_playlists),
        onDismiss = onDismiss,
        surface = colors.card,
        titleColor = colors.onBackground,
        hint = colors.onBackground.copy(alpha = 0.2f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.album_shuffle), mode == QueueMode.ALBUM_SHUFFLE) { mode = QueueMode.ALBUM_SHUFFLE }
            ModeChip(stringResource(R.string.song_shuffle), mode == QueueMode.SONG_SHUFFLE) { mode = QueueMode.SONG_SHUFFLE }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (mode == QueueMode.ALBUM_SHUFFLE) R.string.album_shuffle_hint else R.string.song_shuffle_hint),
            color = colors.faint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        quickRows(library.size, liveMarks).forEach { row ->
            QuickRow(
                title = row.title,
                count = row.count,
                onPlay = {
                    vm.playQuick(row.allow, mode)
                    onDismiss()
                },
            )
        }
    }
}

private data class QuickRowModel(val title: String, val allow: Set<Disposition>?, val count: Int)

@Composable
private fun quickRows(total: Int, marks: Map<String, Disposition>): List<QuickRowModel> {
    val counts = marks.values.groupingBy { it }.eachCount()
    val pending = counts[Disposition.PENDING] ?: 0
    val keep = counts[Disposition.KEEP] ?: 0
    val none = (total - pending - keep).coerceAtLeast(0)
    return listOf(
        QuickRowModel(stringResource(R.string.all), null, total),
        QuickRowModel(stringResource(R.string.unmarked), setOf(Disposition.NONE), none),
        QuickRowModel(stringResource(R.string.pending), setOf(Disposition.PENDING), pending),
        QuickRowModel(stringResource(R.string.keep), setOf(Disposition.KEEP), keep),
        QuickRowModel(stringResource(R.string.unmarked_and_pending), setOf(Disposition.NONE, Disposition.PENDING), none + pending),
    )
}

@Composable
private fun QuickRow(title: String, count: Int, onPlay: () -> Unit) {
    val colors = LocalLibraryColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.onBackground, fontSize = 16.sp)
            Text(pluralStringResource(R.plurals.song_count, count, count), color = colors.faint, fontSize = 12.sp)
        }
        Text(
            stringResource(R.string.play),
            color = colors.onAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent)
                .clickable(onClick = onPlay)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLibraryColors.current
    Text(
        label,
        color = if (selected) colors.onAccent else colors.onBackground,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.accent else colors.onBackground.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun QueueSheet(
    vm: MainViewModel,
    onDismiss: () -> Unit,
    interactive: Boolean = true,
    scrimAlpha: Float = 0.46f,
) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val index by vm.queueIndex.collectAsStateWithLifecycle()
    val title by vm.queueTitle.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (index in queue.indices) listState.scrollToItem(index)
    }
    SheetFrame(
        title = title.ifBlank { stringResource(R.string.play_queue) },
        onDismiss = onDismiss,
        interactive = interactive,
        slideIn = false,
        scrimAlpha = scrimAlpha,
    ) {
        if (queue.isEmpty()) {
            Text(stringResource(R.string.queue_empty), color = Faint)
        } else {
            LazyColumn(Modifier.weight(1f), state = listState) {
                itemsIndexed(queue, key = { position, song -> "$position-${song.key}" }) { position, song ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.jumpTo(position)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(song, Modifier.size(44.dp))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = if (position == index) Paper else Paper.copy(alpha = 0.86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.displayArtist(LocalContext.current), color = Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MarkChip(marks[song.key] ?: Disposition.NONE)
                    }
                }
            }
        }
    }
}

@Composable
fun SleepSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val deadline by vm.sleepDeadlineElapsed.collectAsStateWithLifecycle()
    val untilEnd by vm.sleepUntilTrackEnds.collectAsStateWithLifecycle()
    val active = deadline != null || untilEnd
    BackHandler(onBack = onDismiss)
    SheetFrame(title = stringResource(R.string.sleep_timer), onDismiss = onDismiss, heightFraction = 0.58f) {
        Text(
            stringResource(
                when {
                    untilEnd -> R.string.sleep_until_end_body
                    deadline != null -> R.string.sleep_running_body
                    else -> R.string.sleep_idle_body
                },
            ),
            color = Faint,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        listOf(15, 30, 45, 60, 90).forEach { minutes ->
            SleepChoice(stringResource(R.string.minutes, minutes)) {
                vm.startSleep(minutes)
                onDismiss()
            }
        }
        SleepChoice(stringResource(R.string.after_this_song)) {
            vm.sleepUntilThisTrackEnds()
            onDismiss()
        }
        if (active) {
            SleepChoice(stringResource(R.string.turn_off_timer)) {
                vm.clearSleep()
                onDismiss()
            }
        }
    }
}

@Composable
private fun SleepChoice(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Paper,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun SheetFrame(
    title: String,
    onDismiss: () -> Unit,
    heightFraction: Float = 0.74f,
    interactive: Boolean = true,
    slideIn: Boolean = true,
    scrimAlpha: Float = 0.46f,
    surface: Color = Card,
    titleColor: Color = Paper,
    hint: Color = Color.White.copy(alpha = 0.2f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val rise = remember { Animatable(if (slideIn) 1f else 0f) }
    LaunchedEffect(slideIn) {
        if (slideIn) rise.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = rise.value * size.height }) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (scrimAlpha > 0f) {
                        Modifier.background(Color.Black.copy(alpha = scrimAlpha.coerceIn(0f, 1f)))
                    } else {
                        Modifier
                    },
                )
                .then(if (interactive) Modifier.clickable(onClick = onDismiss) else Modifier),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(hint),
            )
            Spacer(Modifier.height(14.dp))
            Text(title, color = titleColor, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

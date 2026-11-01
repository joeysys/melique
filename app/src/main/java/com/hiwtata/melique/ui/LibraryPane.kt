package com.hiwtata.melique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiwtata.melique.R
import com.hiwtata.melique.data.AlbumGroup
import com.hiwtata.melique.data.toAlbums
import com.hiwtata.melique.data.Disposition
import com.hiwtata.melique.data.Song
import com.hiwtata.melique.ui.theme.KeepTone
import com.hiwtata.melique.ui.theme.LocalLibraryColors
import com.hiwtata.melique.ui.theme.PendingTone

private enum class LibraryTab(val label: Int) {
    Albums(R.string.albums),
    Songs(R.string.songs),
}

@Composable
fun LibraryPane(
    vm: MainViewModel,
    bottomInset: androidx.compose.foundation.layout.PaddingValues,
    onQuick: () -> Unit,
    onAllFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLibraryColors.current
    val songs by vm.library.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val canUndo by vm.canUndoDelete.collectAsStateWithLifecycle()
    val currentKey by vm.queue.collectAsStateWithLifecycle()
    val index by vm.queueIndex.collectAsStateWithLifecycle()
    val playingKey = currentKey.getOrNull(index)?.key
    var tab by remember { mutableStateOf(LibraryTab.Albums) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val filtered = remember(songs, query) {
        val needle = query.trim()
        if (needle.isEmpty()) songs
        else songs.filter {
            it.title.contains(needle, true) ||
                it.artist.contains(needle, true) ||
                it.album.contains(needle, true)
        }
    }
    val context = LocalContext.current
    val albums = remember(filtered, context) { filtered.toAlbums(context) }

    Column(modifier.fillMaxSize().padding(bottom = bottomInset.calculateBottomPadding())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.music), color = colors.onBackground, fontSize = 34.sp, letterSpacing = (-0.6).sp)
                Text(
                    if (scanning) {
                        stringResource(R.string.scanning)
                    } else {
                        stringResource(
                            R.string.library_counts,
                            pluralStringResource(R.plurals.song_count, songs.size, songs.size),
                            pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                        )
                    },
                    color = colors.faint,
                    fontSize = 13.sp,
                )
            }
            IconButton(onClick = {
                searching = !searching
                if (!searching) query = ""
            }) {
                Icon(
                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = colors.onBackground,
                )
            }
        }
        if (searching) {
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.onBackground, fontSize = 16.sp),
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = colors.faint, fontSize = 16.sp)
                    inner()
                },
            )
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LibraryTab.entries.forEach { item ->
                val selected = item == tab
                Text(
                    stringResource(item.label),
                    color = if (selected) colors.accent else colors.faint,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable { tab = item }.padding(bottom = 6.dp),
                )
            }
        }
        if (!canUndo && com.hiwtata.melique.data.Access.needsAllFilesEntry()) {
            Row(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.card)
                    .clickable(onClick = onAllFiles)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.all_files_title), color = colors.onBackground, fontSize = 14.sp)
                    Text(stringResource(R.string.all_files_body), color = colors.faint, fontSize = 12.sp)
                }
                Text(stringResource(R.string.allow), color = colors.accent, fontSize = 14.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                LibraryTab.Albums -> AlbumGrid(albums, marks) { vm.playAlbum(it) }
                LibraryTab.Songs -> SongList(
                    songs = filtered,
                    marks = marks,
                    playingKey = playingKey,
                    onPlay = { vm.playSong(it, filtered) },
                    onMark = vm::markSong,
                )
            }
            if (!scanning && songs.isEmpty()) {
                Text(
                    stringResource(R.string.no_local_music),
                    color = colors.muted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 18.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(onClick = onQuick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    contentDescription = stringResource(R.string.quick_playlist),
                    tint = colors.onAccent,
                )
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    marks: Map<String, Disposition>,
    playingKey: String?,
    onPlay: (Song) -> Unit,
    onMark: (Song, Disposition) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(songs, key = { it.key }) { song ->
            SongRow(song, marks[song.key] ?: Disposition.NONE, song.key == playingKey, onPlay, onMark)
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    mark: Disposition,
    playing: Boolean,
    onPlay: (Song) -> Unit,
    onMark: (Song, Disposition) -> Unit,
) {
    val colors = LocalLibraryColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPlay(song) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = colors.onBackground.copy(alpha = if (playing) 1f else 0.92f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.dot_join,
                    song.displayArtist(LocalContext.current),
                    song.displayAlbum(LocalContext.current),
                ),
                color = colors.faint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MarkChip(mark)
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mark), tint = colors.faint)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pending)) },
                    onClick = { onMark(song, Disposition.PENDING); menu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keep)) },
                    onClick = { onMark(song, Disposition.KEEP); menu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear_mark)) },
                    onClick = { onMark(song, Disposition.NONE); menu = false },
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<AlbumGroup>,
    marks: Map<String, Disposition>,
    onPlay: (AlbumGroup) -> Unit,
) {
    val colors = LocalLibraryColors.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { it.albumKey }, span = { GridItemSpan(1) }) { album ->
            Column(Modifier.clickable { onPlay(album) }) {
                Cover(album.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                Text(album.title, color = colors.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        R.string.dot_join,
                        album.artist,
                        pluralStringResource(R.plurals.song_count, album.songs.size, album.songs.size),
                    ),
                    color = colors.faint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = albumSummary(album, marks)
                if (summary != null) {
                    Text(summary, color = colors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Cover(song: Song, modifier: Modifier = Modifier) {
    val cache = rememberArtCache()
    val visual by androidx.compose.runtime.produceState<ArtVisual?>(null, song.albumKey, cache) {
        value = cache.load(song)
    }
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.clip(shape).background(Color(0xFF1A1A1C))) {
        val cover = visual?.cover
        if (cover != null) {
            androidx.compose.foundation.Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun rememberArtCache(): ArtCache = LocalArtCache.current

@Composable
internal fun MarkChip(mark: Disposition, prominent: Boolean = false, modifier: Modifier = Modifier) {
    if (mark == Disposition.NONE) return
    val keep = mark == Disposition.KEEP
    val color = if (keep) KeepTone else PendingTone
    val label = stringResource(if (keep) R.string.keep else R.string.pending)
    Text(
        label,
        color = if (prominent) Color(0xFF142018) else color,
        fontSize = if (prominent) 14.sp else 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (prominent) color else color.copy(alpha = if (keep) 0.28f else 0.16f))
            .padding(horizontal = if (prominent) 12.dp else 8.dp, vertical = if (prominent) 5.dp else 3.dp),
    )
}

@Composable
private fun albumSummary(album: AlbumGroup, marks: Map<String, Disposition>): String? {
    var keep = 0
    var pending = 0
    album.songs.forEach { song ->
        when (marks[song.key]) {
            Disposition.KEEP -> keep++
            Disposition.PENDING -> pending++
            else -> Unit
        }
    }
    if (keep == 0 && pending == 0) return null
    return buildList {
        if (keep > 0) add(stringResource(R.string.kept_count, keep))
        if (pending > 0) add(stringResource(R.string.pending_count, pending))
    }.joinToString(" · ")
}

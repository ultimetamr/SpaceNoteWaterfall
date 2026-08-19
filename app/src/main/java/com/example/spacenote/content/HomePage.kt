package com.example.spacenote.content

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.NumberBadge
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private const val MAX_NOTES = 20
private const val FALL_MS = 8000L
private const val LAST_RESULT_PREFS = "space_note_waterfall_last_result"
private const val LAST_RESULT_KEY = "notes"

private val TodayColor = Color(0xFFD88A62) // design-style: fixed-figma-color category identity palette
private val LaterColor = Color(0xFF668DB2) // design-style: fixed-figma-color category identity palette
private val DelegateColor = Color(0xFF7F9870) // design-style: fixed-figma-color category identity palette
private val UndecidedColor = Color(0xFF8C8495) // design-style: fixed-figma-color category identity palette
private val PaperColor = Color(0xFFFFF8E8) // design-style: fixed-figma-color paper surface
private val PaperInk = Color(0xFF332F2A) // design-style: fixed-figma-color paper ink
private val PaperOutline = Color(0xFFC7BEB2) // design-style: fixed-figma-color paper outline
private val ActionColor = Color(0xFFB77858) // design-style: fixed-figma-color primary action

private enum class Page { INPUT, SORT, RESULT }

internal enum class Bucket(val label: String, val symbol: String, val color: Color, val tone: Int) {
    TODAY("今天", "●", TodayColor, ToneGenerator.TONE_PROP_BEEP),
    LATER("以后", "■", LaterColor, ToneGenerator.TONE_PROP_ACK),
    DELEGATE("交给别人", "▲", DelegateColor, ToneGenerator.TONE_PROP_PROMPT),
    UNDECIDED("待决定池", "◆", UndecidedColor, ToneGenerator.TONE_PROP_NACK)
}

internal data class Note(val id: Long, val text: String)
internal data class SavedScreenshot(val uri: Uri, val displayName: String)

internal data class CategorySnapshot(
    val today: List<Note> = emptyList(),
    val later: List<Note> = emptyList(),
    val delegate: List<Note> = emptyList(),
    val undecided: List<Note> = emptyList()
) {
    fun listFor(bucket: Bucket): List<Note> = when (bucket) {
        Bucket.TODAY -> today
        Bucket.LATER -> later
        Bucket.DELEGATE -> delegate
        Bucket.UNDECIDED -> undecided
    }

    fun allNotes(): List<Note> = today + later + delegate + undecided
    fun totalSize(): Int = Bucket.entries.sumOf { listFor(it).size }
}

/** Four independent lists are the sole mutable classification source of truth. */
internal class CategoryTaskLists(
    val today: MutableList<Note>,
    val later: MutableList<Note>,
    val delegate: MutableList<Note>,
    val undecided: MutableList<Note>
) {
    fun listFor(bucket: Bucket): MutableList<Note> = when (bucket) {
        Bucket.TODAY -> today
        Bucket.LATER -> later
        Bucket.DELEGATE -> delegate
        Bucket.UNDECIDED -> undecided
    }

    fun place(note: Note, bucket: Bucket) {
        remove(note.id)
        listFor(bucket).add(note)
    }

    fun reclaim(id: Long): Note? {
        val note = Bucket.entries.firstNotNullOfOrNull { listFor(it).firstOrNull { note -> note.id == id } }
        if (note != null) remove(id)
        return note
    }

    fun move(id: Long, bucket: Bucket) {
        val note = reclaim(id) ?: return
        place(note, bucket)
    }

    fun update(note: Note) {
        Bucket.entries.forEach { bucket ->
            val list = listFor(bucket)
            val index = list.indexOfFirst { it.id == note.id }
            if (index >= 0) list[index] = note
        }
    }

    fun remove(id: Long) {
        Bucket.entries.forEach { listFor(it).removeAll { note -> note.id == id } }
    }

    fun clear() = Bucket.entries.forEach { listFor(it).clear() }
    fun totalSize(): Int = Bucket.entries.sumOf { listFor(it).size }

    fun snapshot(): CategorySnapshot = CategorySnapshot(
        today = today.toList(),
        later = later.toList(),
        delegate = delegate.toList(),
        undecided = undecided.toList()
    )

    /** Canonicalizes duplicates and restores every missing input note to the undecided list. */
    fun reconcileWith(inputNotes: List<Note>): Int {
        val canonical = inputNotes.associateBy { it.id }
        val seen = mutableSetOf<Long>()
        Bucket.entries.forEach { bucket ->
            val list = listFor(bucket)
            val cleaned = list.mapNotNull { note -> canonical[note.id]?.takeIf { seen.add(note.id) } }
            list.clear()
            list.addAll(cleaned)
        }
        val missing = inputNotes.filter { seen.add(it.id) }
        undecided.addAll(missing)
        return missing.size
    }
}

internal fun isReadyToViewResults(
    inputCount: Int,
    unassignedCount: Int,
    activeId: Long?,
    classifiedCount: Int
): Boolean = inputCount > 0 &&
    unassignedCount == 0 &&
    activeId == null &&
    classifiedCount == inputCount

@Composable
fun HomePage() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val demoSorting = remember(activity) { activity?.intent?.getBooleanExtra("demo_sorting", false) == true }
    val demoScheduler = remember(activity) { activity?.intent?.getBooleanExtra("demo_scheduler", false) == true }
    val demoCaptureSorting = remember(activity) { activity?.intent?.getBooleanExtra("demo_capture_sorting", false) == true }
    val demoResult = remember(activity) { activity?.intent?.getBooleanExtra("demo_result", false) == true }
    val demoCaptureResult = remember(activity) { activity?.intent?.getBooleanExtra("demo_capture_result", false) == true }
    val demoPersistResult = remember(activity) { activity?.intent?.getBooleanExtra("demo_persist_result", false) == true }
    val demoClearSaved = remember(activity) { activity?.intent?.getBooleanExtra("demo_clear_saved", false) == true }
    remember(context, demoClearSaved) {
        if (demoClearSaved) clearLastResult(context)
        demoClearSaved
    }

    val restoredSnapshot = remember(context, demoSorting, demoResult) {
        if (!demoSorting && !demoResult) loadLastResult(context) else CategorySnapshot()
    }
    val restoredResult = restoredSnapshot.totalSize() > 0
    val demoSortNotes = remember(demoScheduler) {
        if (demoScheduler) (1L..20L).map { Note(it, "测试待办 $it") }
        else listOf(Note(1, "整理旅行票据"), Note(2, "回复小林项目时间"), Note(3, "预约牙医复诊"))
    }
    val demoResultSnapshot = remember {
        CategorySnapshot(
            today = listOf(Note(1, "整理旅行票据"), Note(2, "提交费用报销")),
            later = listOf(Note(3, "预约牙医复诊"), Note(4, "阅读新项目资料"), Note(5, "整理下周采购清单")),
            delegate = listOf(Note(6, "回复小林项目时间"), Note(7, "请同事确认会议室")),
            undecided = listOf(Note(8, "比较两种收纳方案"))
        )
    }
    val initialSnapshot = when {
        demoResult -> demoResultSnapshot
        restoredResult -> restoredSnapshot
        else -> CategorySnapshot()
    }

    var page by remember {
        mutableStateOf(when {
            demoResult || restoredResult -> Page.RESULT
            demoSorting -> Page.SORT
            else -> Page.INPUT
        })
    }
    val inputNotes = remember {
        mutableStateListOf<Note>().apply {
            if (demoSorting) addAll(demoSortNotes) else addAll(initialSnapshot.allNotes())
        }
    }
    val unassignedNotes = remember {
        mutableStateListOf<Note>().apply { if (demoSorting) addAll(demoSortNotes) }
    }
    val categories = remember {
        CategoryTaskLists(
            today = mutableStateListOf(),
            later = mutableStateListOf(),
            delegate = mutableStateListOf(),
            undecided = mutableStateListOf()
        ).apply {
            today.addAll(initialSnapshot.today)
            later.addAll(initialSnapshot.later)
            delegate.addAll(initialSnapshot.delegate)
            undecided.addAll(initialSnapshot.undecided)
        }
    }

    var draft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(if (restoredResult) "已恢复上次分拣结果" else "纯本地使用；一次最多 20 条") }
    var nextId by remember { mutableStateOf((inputNotes.maxOfOrNull { it.id } ?: 0L) + 1L) }
    var activeId by remember { mutableStateOf<Long?>(if (demoSorting && !demoScheduler) 1L else null) }
    var waiting by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    val tone = remember { ToneGenerator(AudioManager.STREAM_SYSTEM, 28) }
    val view = LocalView.current

    fun addLines(raw: String) {
        val incoming = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val room = MAX_NOTES - inputNotes.size
        incoming.take(room).forEach { inputNotes += Note(nextId++, it) }
        draft = ""
        message = if (incoming.size > room || inputNotes.size == MAX_NOTES) {
            "先整理这 20 条吧，下一批再继续。"
        } else {
            "已接收 ${inputNotes.size} 条"
        }
    }

    fun finishIfComplete() {
        if (unassignedNotes.isNotEmpty() || activeId != null || inputNotes.isEmpty()) return
        val filled = categories.reconcileWith(inputNotes)
        check(categories.totalSize() == inputNotes.size) {
            "Classification invariant failed: ${categories.totalSize()} != ${inputNotes.size}"
        }
        message = if (filled > 0) "已将遗漏的 $filled 条补入待决定池" else "全部 ${categories.totalSize()} 条已完成分拣"
        Log.i(
            "SpaceNoteWaterfall",
            "classification_complete input=${inputNotes.size} total=${categories.totalSize()} " +
                Bucket.entries.joinToString { "${it.name}=${categories.listFor(it).size}" }
        )
    }

    fun assign(id: Long, bucket: Bucket) {
        val note = inputNotes.firstOrNull { it.id == id } ?: return
        categories.place(note, bucket)
        unassignedNotes.removeAll { it.id == id }
        tone.startTone(bucket.tone, 90)
        activeId = null
        waiting = false
        finishIfComplete()
    }

    val pendingIds = unassignedNotes.map { it.id }
    val canViewResults = isReadyToViewResults(
        inputCount = inputNotes.size,
        unassignedCount = unassignedNotes.size,
        activeId = activeId,
        classifiedCount = categories.totalSize()
    )
    LaunchedEffect(page, activeId, pendingIds) {
        if (page == Page.SORT && activeId == null && pendingIds.isNotEmpty()) {
            waiting = true
            try {
                val next = awaitNextPendingId(activeId, pendingIds)
                if (next != null) activeId = next
            } finally {
                waiting = false
            }
        } else {
            waiting = false
        }
    }
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        delay(FALL_MS)
        if (activeId == id) assign(id, Bucket.UNDECIDED)
    }
    LaunchedEffect(demoCaptureSorting) {
        if (demoSorting && demoCaptureSorting) {
            delay(3500)
            message = runCatching { saveViewPng(view, context) }.fold(
                { "分拣过程截图已保存：${it.displayName}" },
                { "分拣过程截图保存失败" }
            )
        }
    }
    LaunchedEffect(demoCaptureResult) {
        if ((demoResult || restoredResult) && demoCaptureResult) {
            delay(2500)
            message = runCatching { saveViewPng(view, context) }.fold(
                {
                    openScreenshot(context, it.uri)
                    "结果页截图已保存并打开：${it.displayName}"
                },
                { "结果页截图保存失败" }
            )
        }
    }
    LaunchedEffect(demoPersistResult) {
        if (demoResult && demoPersistResult) {
            delay(1200)
            val saved = saveLastResult(context, categories.snapshot())
            Log.i("SpaceNoteWaterfall", "last_result_saved=$saved count=${categories.totalSize()}")
        }
    }
    LaunchedEffect(restoredResult) {
        if (restoredResult) Log.i("SpaceNoteWaterfall", "last_result_restored count=${categories.totalSize()}")
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        when (page) {
            Page.INPUT -> InputPage(
                notes = inputNotes,
                draft = draft,
                message = message,
                onDraft = { draft = it },
                onAdd = { addLines(draft) },
                onStart = {
                    if (inputNotes.isNotEmpty()) {
                        categories.clear()
                        unassignedNotes.clear()
                        unassignedNotes.addAll(inputNotes)
                        activeId = null
                        page = Page.SORT
                        message = ""
                    }
                },
                onDelete = { id -> inputNotes.removeAll { it.id == id } }
            )
            Page.SORT -> SortPage(
                inputNotes = inputNotes,
                unassignedNotes = unassignedNotes,
                categories = categories,
                activeId = activeId,
                waiting = waiting,
                canViewResults = canViewResults,
                onViewResults = {
                    finishIfComplete()
                    if (isReadyToViewResults(inputNotes.size, unassignedNotes.size, activeId, categories.totalSize())) {
                        page = Page.RESULT
                    }
                },
                assign = ::assign,
                reclaim = { id ->
                    if (activeId == null) {
                        categories.reclaim(id)?.let { note ->
                            if (unassignedNotes.none { it.id == id }) unassignedNotes.add(0, note)
                            activeId = id
                            waiting = false
                        }
                    }
                }
            )
            Page.RESULT -> ResultPage(
                categories = categories,
                editId = editId,
                editText = editText,
                message = message,
                onEdit = { note -> editId = note.id; editText = note.text },
                onEditText = { editText = it },
                onSaveEdit = {
                    val index = inputNotes.indexOfFirst { it.id == editId }
                    if (index >= 0 && editText.isNotBlank()) {
                        val updated = inputNotes[index].copy(text = editText.trim())
                        inputNotes[index] = updated
                        categories.update(updated)
                    }
                    editId = null
                },
                onDelete = { id ->
                    inputNotes.removeAll { it.id == id }
                    categories.remove(id)
                },
                onMove = { id, bucket ->
                    categories.move(id, bucket)
                    tone.startTone(bucket.tone, 90)
                },
                onBackToSort = {
                    editId = null
                    activeId = null
                    waiting = false
                    page = Page.SORT
                    message = "可继续抓回便签并调整分类"
                },
                onScreenshot = {
                    message = runCatching { saveViewPng(view, context) }.fold(
                        {
                            openScreenshot(context, it.uri)
                            "已保存并打开相册：DCIM/SpaceNoteWaterfall/${it.displayName}"
                        },
                        { "截图保存失败，请重试" }
                    )
                },
                onRestart = {
                    if (saveLastResult(context, categories.snapshot())) {
                        inputNotes.clear()
                        unassignedNotes.clear()
                        categories.clear()
                        page = Page.INPUT
                        activeId = null
                        message = "本次结果已保存；下次打开会自动显示"
                    } else {
                        message = "结果保存失败，请重试"
                    }
                }
            )
        }
    }
}

@Composable
private fun InputPage(
    notes: List<Note>,
    draft: String,
    message: String,
    onDraft: (String) -> Unit,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onDelete: (Long) -> Unit
) {
    Header("空间便签瀑布", "已输入 ${notes.size} / $MAX_NOTES")
    Text("把长清单拆成一次一个的小决定。", color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyLarge)
    Spacer(Modifier.height(20.dp))
    BasicTextField(
        value = draft,
        onValueChange = { value -> if (notes.size < MAX_NOTES) onDraft(value) },
        modifier = Modifier.fillMaxWidth().height(180.dp).background(PaperColor, RoundedCornerShape(18.dp))
            .border(1.dp, PaperOutline, RoundedCornerShape(18.dp)).padding(20.dp),
        textStyle = PicoTheme.typography.bodyLarge.copy(color = PaperInk),
        decorationBox = { inner ->
            Box {
                if (draft.isEmpty()) Text("输入待办；也可以一次粘贴多行", color = PicoTheme.colorScheme.labelSecondary)
                inner()
            }
        }
    )
    Spacer(Modifier.height(12.dp))
    Text(message, color = PicoTheme.colorScheme.labelSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("添加", notes.size < MAX_NOTES && draft.isNotBlank(), onAdd)
        ActionButton("开始分拣", notes.isNotEmpty(), onStart)
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().height(600.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        notes.forEach { note ->
            Row(
                Modifier.fillMaxWidth().background(PaperColor, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(note.text, modifier = Modifier.width(650.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                ActionButton("删除", true) { onDelete(note.id) }
            }
        }
    }
}

@Composable
private fun SortPage(
    inputNotes: List<Note>,
    unassignedNotes: List<Note>,
    categories: CategoryTaskLists,
    activeId: Long?,
    waiting: Boolean,
    canViewResults: Boolean,
    onViewResults: () -> Unit,
    assign: (Long, Bucket) -> Unit,
    reclaim: (Long) -> Unit
) {
    val active = inputNotes.firstOrNull { it.id == activeId }
    var highlighted by remember { mutableStateOf<Bucket?>(null) }
    Header("慢慢分，一次一张", "还剩 ${unassignedNotes.size} 条")
    Box(
        Modifier.fillMaxWidth().height(500.dp).border(1.dp, PaperOutline, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.TopCenter
    ) {
        if (active != null) {
            DraggableNote(active, { highlighted = it }) { bucket ->
                highlighted = null
                if (bucket != null) assign(active.id, bucket)
            }
        } else {
            Text(
                when {
                    canViewResults -> "全部便签已分类，可继续抓回调整"
                    waiting -> "下一张便签将在 2 秒后出现"
                    else -> "准备下一张…"
                },
                modifier = Modifier.padding(32.dp),
                color = PicoTheme.colorScheme.labelSecondary
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Bucket.entries.forEach { bucket ->
            CategoryActionButton(bucket, categories.listFor(bucket).size, active != null) {
                active?.let { assign(it.id, bucket) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Bucket.entries.take(3).forEach { bucket ->
            BucketView(
                bucket = bucket,
                items = categories.listFor(bucket),
                disabled = active != null,
                highlighted = highlighted == bucket,
                reclaim = reclaim,
                modifier = Modifier.width(285.dp).height(170.dp)
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().height(116.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        BucketView(
            bucket = Bucket.UNDECIDED,
            items = categories.undecided,
            disabled = active != null,
            highlighted = false,
            reclaim = reclaim,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        Button(onClick = onViewResults, enabled = canViewResults) { Text("查看结果") }
    }
}

@Composable
private fun DraggableNote(note: Note, onTargetChanged: (Bucket?) -> Unit, onDrop: (Bucket?) -> Unit) {
    var drag by remember(note.id) { mutableStateOf(Offset.Zero) }
    val fall = remember(note.id) { Animatable(0f) }
    LaunchedEffect(note.id) { fall.animateTo(390f, tween(FALL_MS.toInt(), easing = LinearEasing)) }
    fun target(offset: Offset): Bucket? = if (offset.y < 80f && fall.value < 260f) null else when {
        offset.x < -100f -> Bucket.TODAY
        offset.x > 100f -> Bucket.DELEGATE
        else -> Bucket.LATER
    }
    Box(
        Modifier.offset { IntOffset(drag.x.roundToInt(), (fall.value + drag.y).roundToInt()) }
            .padding(top = 30.dp).width(420.dp).height(136.dp)
            .shadow(12.dp, RoundedCornerShape(18.dp)).background(PaperColor, RoundedCornerShape(18.dp))
            .pointerInput(note.id) {
                detectDragGestures(
                    onDragCancel = { onTargetChanged(null); drag = Offset.Zero },
                    onDragEnd = {
                        val destination = target(drag)
                        onDrop(destination)
                        if (destination == null) drag = Offset.Zero
                    }
                ) { change, amount ->
                    change.consume()
                    drag += amount
                    onTargetChanged(target(drag))
                }
            }.padding(24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(note.text, color = PaperInk, style = PicoTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BucketView(
    bucket: Bucket,
    items: List<Note>,
    disabled: Boolean,
    highlighted: Boolean,
    reclaim: (Long) -> Unit,
    modifier: Modifier
) {
    val scale by animateFloatAsState(if (highlighted) 1.03f else 1f, tween(140), label = "basketHit")
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }
                .background(PaperColor, RoundedCornerShape(18.dp))
                .border(if (highlighted) 4.dp else 2.dp, bucket.color, RoundedCornerShape(18.dp))
                .padding(12.dp)
        ) {
            Text("${bucket.symbol} ${bucket.label}", color = bucket.color, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items.forEach { note ->
                    Box(
                        Modifier.fillMaxWidth().height(56.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .clickable(enabled = !disabled) { reclaim(note.id) }.padding(8.dp)
                    ) {
                        Text(note.text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = PaperInk)
                    }
                }
            }
        }
        NumberBadge(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp), items.size)
    }
}

@Composable
private fun CategoryActionButton(bucket: Bucket, count: Int, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier.width(216.dp).fillMaxHeight().alpha(if (enabled) 1f else .45f)
            .background(PaperColor, RoundedCornerShape(12.dp))
            .border(2.dp, bucket.color, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = action),
        contentAlignment = Alignment.Center
    ) {
        Text("${bucket.symbol} ${bucket.label} $count", color = bucket.color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResultPage(
    categories: CategoryTaskLists,
    editId: Long?,
    editText: String,
    message: String,
    onEdit: (Note) -> Unit,
    onEditText: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Bucket) -> Unit,
    onBackToSort: () -> Unit,
    onScreenshot: () -> Unit,
    onRestart: () -> Unit
) {
    val total = categories.totalSize()
    Header("分拣结果", "共 $total 条")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onBackToSort) { Text("返回分拣") }
        ActionButton("保存截图", true, onScreenshot)
        ActionButton("完成这次分拣", true, onRestart)
    }
    if (message.isNotBlank()) Text(message, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    Row(
        Modifier.fillMaxWidth().height(850.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Bucket.entries.forEach { bucket ->
            val items = categories.listFor(bucket)
            Column(
                Modifier.width(210.dp).fillMaxHeight().background(PaperColor, RoundedCornerShape(18.dp))
                    .padding(12.dp).verticalScroll(rememberScrollState())
            ) {
                Text("${bucket.symbol} ${bucket.label}  ${items.size} 条", color = bucket.color, fontWeight = FontWeight.Bold)
                items.forEach { note ->
                    ResultNoteCard(note, bucket, editId, editText, onEdit, onEditText, onSaveEdit, onDelete, onMove)
                }
            }
        }
    }
}

@Composable
private fun ResultNoteCard(
    note: Note,
    bucket: Bucket,
    editId: Long?,
    editText: String,
    onEdit: (Note) -> Unit,
    onEditText: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Bucket) -> Unit
) {
    var cardDrag by remember(note.id) { mutableStateOf(Offset.Zero) }
    Column(
        Modifier.offset { IntOffset(cardDrag.x.roundToInt(), cardDrag.y.roundToInt()) }
            .fillMaxWidth().padding(top = 10.dp).background(Color.White, RoundedCornerShape(12.dp))
            .pointerInput(note.id, bucket) {
                detectDragGestures(
                    onDragCancel = { cardDrag = Offset.Zero },
                    onDragEnd = {
                        val current = Bucket.entries.indexOf(bucket)
                        val next = when {
                            cardDrag.x > 70f -> (current + 1).coerceAtMost(Bucket.entries.lastIndex)
                            cardDrag.x < -70f -> (current - 1).coerceAtLeast(0)
                            else -> current
                        }
                        cardDrag = Offset.Zero
                        if (next != current) onMove(note.id, Bucket.entries[next])
                    }
                ) { change, amount -> change.consume(); cardDrag += amount }
            }.padding(10.dp)
    ) {
        if (editId == note.id) {
            BasicTextField(
                editText,
                onEditText,
                Modifier.fillMaxWidth().height(72.dp).border(1.dp, bucket.color, RoundedCornerShape(8.dp)).padding(8.dp),
                textStyle = PicoTheme.typography.bodyMedium.copy(color = PaperInk)
            )
            ActionButton("保存", editText.isNotBlank(), onSaveEdit)
        } else {
            Text(note.text, color = PaperInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton("编辑", true) { onEdit(note) }
            ActionButton("删除", true) { onDelete(note.id) }
        }
        Row {
            Bucket.entries.filter { it != bucket }.take(3).forEach { target ->
                Box(Modifier.size(56.dp).clickable { onMove(note.id, target) }, contentAlignment = Alignment.Center) {
                    Text(target.symbol, color = target.color)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, status: String) {
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = PicoTheme.typography.titleLarge)
        Text(status, color = PicoTheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier.height(56.dp).alpha(if (enabled) 1f else .45f)
            .background(ActionColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = action).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

internal fun saveViewPng(view: View, context: Context): SavedScreenshot {
    val displayName = "SpaceNoteWaterfall-${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/SpaceNoteWaterfall")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.rgb(246, 242, 235)) }
    view.draw(canvas)
    try {
        resolver.openOutputStream(uri, "w").use { output ->
            checkNotNull(output)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return SavedScreenshot(uri, displayName)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    } finally {
        bitmap.recycle()
    }
}

internal fun openScreenshot(context: Context, uri: Uri) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

internal fun saveLastResult(
    context: Context,
    snapshot: CategorySnapshot,
    prefsName: String = LAST_RESULT_PREFS
): Boolean {
    val json = JSONArray().apply {
        Bucket.entries.forEach { bucket ->
            snapshot.listFor(bucket).forEach { note ->
                put(JSONObject().apply {
                    put("id", note.id)
                    put("text", note.text)
                    put("bucket", bucket.name)
                })
            }
        }
    }
    return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .edit().putString(LAST_RESULT_KEY, json.toString()).commit()
}

internal fun loadLastResult(
    context: Context,
    prefsName: String = LAST_RESULT_PREFS
): CategorySnapshot = runCatching {
    val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(LAST_RESULT_KEY, null) ?: return@runCatching CategorySnapshot()
    val lists = Bucket.entries.associateWith { mutableListOf<Note>() }
    val json = JSONArray(raw)
    for (index in 0 until json.length()) {
        val item = json.getJSONObject(index)
        val bucket = item.optString("bucket").takeIf { it.isNotBlank() }
            ?.let { runCatching { Bucket.valueOf(it) }.getOrNull() }
            ?: Bucket.UNDECIDED
        lists.getValue(bucket).add(Note(item.getLong("id"), item.getString("text")))
    }
    CategorySnapshot(
        today = lists.getValue(Bucket.TODAY),
        later = lists.getValue(Bucket.LATER),
        delegate = lists.getValue(Bucket.DELEGATE),
        undecided = lists.getValue(Bucket.UNDECIDED)
    )
}.getOrDefault(CategorySnapshot())

internal fun clearLastResult(
    context: Context,
    prefsName: String = LAST_RESULT_PREFS
): Boolean = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal suspend fun awaitNextPendingId(
    activeId: Long?,
    pendingIds: List<Long>,
    delayMillis: Long = 2000L
): Long? {
    if (activeId != null || pendingIds.isEmpty()) return null
    delay(delayMillis)
    return pendingIds.firstOrNull()
}

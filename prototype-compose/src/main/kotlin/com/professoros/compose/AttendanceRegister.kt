package com.professoros.compose

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.professoros.model.AttendanceStatus
import com.professoros.service.PortalPublishingClient.*
import java.util.UUID
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class RegisterViewState {
    var lectureId by mutableStateOf<UUID?>(null)
    var matrix by mutableStateOf(false)
    var query by mutableStateOf("")
    var filter by mutableStateOf("ALL")
}

@Composable
internal fun RegisterMenu(label: String, value: String, choices: List<Choice>, enabled: Boolean, tag: String, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(value,"chevrons",enabled=enabled,
            modifier=Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription="$label, $value" }) { open=true }
        if(open) Popup(alignment=Alignment.BottomStart,onDismissRequest={open=false},properties=PopupProperties(focusable=true)) {
            Column(Modifier.width(320.dp).heightIn(max=330.dp).clip(ActionShape).background(Ink.panel)
                .border(1.dp,Ink.border,ActionShape).padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Copy(label,13,weight=FontWeight.SemiBold)
                var search by remember { mutableStateOf("") }
                if(choices.size>8) Input("Find an option",search,{search=it})
                LazyColumn(Modifier.heightIn(max=235.dp)) {
                    items(choices.filter {it.label.contains(search,true)},key={it.value}) { choice ->
                        Action(choice.label,quiet=true,modifier=Modifier.fillMaxWidth()) {open=false;select(choice.value)}
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttendanceRegister(editor: AttendanceSheetEditor, busy: Boolean, view: RegisterViewState = remember(editor){RegisterViewState()}, save: () -> Unit) {
    val lectures = editor.sheet.lectures()
    val selected = lectures.find { it.id() == view.lectureId } ?: lectures.firstOrNull()
    var searchFocused by remember { mutableStateOf(false) }
    var discard by remember(editor) { mutableStateOf(false) }
    val students = remember(editor.sheet, view.query) {
        editor.sheet.students().filter { (it.name()+" "+it.number()).contains(view.query.trim(),true) }
            .sortedWith(compareBy({ it.number() }, { it.name() }, { it.id() }))
    }
    val roster = students.filter { student ->
        selected == null || view.filter == "ALL" || (editor.value(RegisterKey(student.id(),selected.id()))?.name ?: "UNMARKED") == view.filter
    }
    val pending = editor.edits.size
    val conflicts = editor.edits.keys.count { editor.conflict(it) || !editor.editable(it) }
    val canSave = pending > 0 && conflicts == 0 && !editor.needsRefresh && !busy
    Column(Modifier.fillMaxSize().onPreviewKeyEvent {
        if(!searchFocused && it.type==KeyEventType.KeyDown && it.key==Key.S && (it.isCtrlPressed || it.isMetaPressed)) {
            if(canSave) save()
            true
        } else false
    }, verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(selected==null) Copy("No lectures in this offering.",14,Ink.muted,modifier=Modifier.weight(1f))
        else {
            val statuses = listOf(Choice("ALL","All statuses"))+attendanceChoices.map { Choice(it?.name ?: "UNMARKED",attendanceLabel(it)) }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp),
                itemVerticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.width(218.dp)) {
                    RegisterMenu("Lecture",selected.day()+" · "+selected.title(),lectures.map {Choice(it.id().toString(),it.day()+" · "+it.title())},
                        !busy,"sheet-lecture"){view.lectureId=UUID.fromString(it)}
                }
                Input("Find student",view.query,{view.query=it},Modifier.width(145.dp),tag="sheet-search",compact=true,onEditingFocus={searchFocused=it})
                Box(Modifier.width(127.dp)) {
                    RegisterMenu("Lecture status",statuses.first {it.value==view.filter}.label,statuses,!busy,"sheet-status"){view.filter=it}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Action("Lecture view","lecture",active=!view.matrix,quiet=true){view.matrix=false}
                    Action("Matrix view","grid",active=view.matrix,quiet=true){view.matrix=true}
                }
            }
            val totals = editor.sheet.students().groupingBy { editor.value(RegisterKey(it.id(),selected.id())) }.eachCount()
            val blanks = roster.count {
                val key=RegisterKey(it.id(),selected.id())
                editor.editable(key) && editor.value(key)==null && key !in editor.edits
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Copy(attendanceChoices.joinToString("   ·   ") { "${totals[it] ?: 0} ${attendanceLabel(it)}" },
                    12,Ink.muted,modifier=Modifier.weight(1f))
                val options=buildList {
                    if(selected.editable() && editor.sheet.complete() && blanks>0)
                        attendanceChoices.filterNotNull().forEach {add(Choice(it.name,"Fill $blanks blanks as ${attendanceLabel(it)}"))}
                    if(pending>0) add(Choice("DISCARD","Discard edits…"))
                }
                if(options.isNotEmpty()) Box(Modifier.width(112.dp)) {
                    RegisterMenu("Attendance actions","Actions",options,!busy,"sheet-actions") {
                        if(it=="DISCARD") discard=true else editor.fillBlanks(roster,selected.id(),AttendanceStatus.valueOf(it))
                    }
                }
            }
            if(!editor.sheet.complete()) Copy("Recorded students only. Only existing marks can be edited.",12,Ink.muted)
            if(!selected.editable()) Copy("This lecture is read-only.",12,Ink.muted)
            if(conflicts>0) Copy("$conflicts changed or unavailable on the server. Review the highlighted cells or discard these edits.",13,LocalPalette.current.warning)
            RegisterGrid(editor,roster,if(view.matrix) lectures else listOf(selected),view.matrix,busy,Modifier.weight(1f)) {
                view.lectureId=it;view.matrix=false
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Copy(if(pending==0) "No pending edits" else "$pending unsaved in this offering",13,if(pending==0) Ink.muted else Ink.violet,modifier=Modifier.weight(1f))
            Action("Save attendance","check",variant=ActionVariant.PRIMARY,enabled=canSave,
                modifier=Modifier.testTag("sheet-save").semantics { contentDescription="Save attendance, Control S" },onClick=save)
        }
        if(discard) Modal("Discard attendance edits?",{discard=false},footer={
            Toolbar {
                Action("Keep edits"){discard=false}
                Action("Confirm discard",variant=ActionVariant.DESTRUCTIVE,enabled=!busy){editor.edits.clear();discard=false}
            }
        }) { Copy("$pending pending edits in this offering will be discarded.") }
    }
}

@Composable
internal fun RegisterGrid(editor: AttendanceSheetEditor, students: List<RegisterStudent>, lectures: List<RegisterLecture>,
                         matrix: Boolean, busy: Boolean, modifier: Modifier, openLecture:(UUID)->Unit) {
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusTargets = remember { mutableStateMapOf<RegisterKey,FocusRequester>() }
    var navigation by remember { mutableStateOf<Job?>(null) }
    BoxWithConstraints(modifier.widthIn(max=if(matrix) Dp.Unspecified else 580.dp).fillMaxWidth().border(1.dp,Ink.border,ActionShape).clip(ActionShape)) {
        val nameWidth = if(matrix) (maxWidth*.28f).coerceIn(132.dp,238.dp) else (maxWidth-164.dp).coerceAtLeast(118.dp)
        val cellWidth = if(matrix) 76.dp else 153.dp
        val cellPixels = with(LocalDensity.current) { cellWidth.roundToPx() }
        val viewport = with(LocalDensity.current) { (maxWidth-nameWidth-2.dp).roundToPx() }
        fun move(row:Int,column:Int,event:KeyEvent):Boolean {
            val columns=lectures.size
            var targetRow=row
            var targetColumn=column
            when(event.key) {
                Key.DirectionUp -> targetRow--
                Key.DirectionDown -> targetRow++
                Key.DirectionLeft -> targetColumn--
                Key.DirectionRight -> targetColumn++
                Key.Tab -> {
                    val position=row*columns+column+(if(event.isShiftPressed) -1 else 1)
                    if(position !in 0 until students.size*columns) return false
                    targetRow=position/columns
                    targetColumn=position%columns
                }
                else -> return false
            }
            if(targetRow !in students.indices || targetColumn !in lectures.indices) return true
            val target=RegisterKey(students[targetRow].id(),lectures[targetColumn].id())
            navigation?.cancel()
            navigation=scope.launch {
                val layout=list.layoutInfo
                val item=layout.visibleItemsInfo.firstOrNull { it.index==targetRow }
                if(item==null || item.offset<layout.viewportStartOffset || item.offset+item.size>layout.viewportEndOffset)
                    list.scrollToItem(targetRow)
                val left=targetColumn*cellPixels
                if(left<scroll.value) scroll.scrollTo(left)
                else if(left+cellPixels>scroll.value+viewport) scroll.scrollTo(left+cellPixels-viewport)
                snapshotFlow { focusTargets[target] }.first { it!=null }?.requestFocus()
            }
            return true
        }
        Column(Modifier.fillMaxSize().padding(1.dp)) {
            Row(Modifier.fillMaxWidth().background(Ink.raised),verticalAlignment=Alignment.CenterVertically) {
                Copy("Student",13,weight=FontWeight.SemiBold,modifier=Modifier.width(nameWidth).padding(horizontal=8.dp,vertical=6.dp))
                Row(Modifier.weight(1f).then(if(matrix) Modifier.horizontalScroll(scroll) else Modifier)) {
                    lectures.forEach { l ->
                        Column(Modifier.width(cellWidth).clickable(enabled=matrix){openLecture(l.id())}.padding(6.dp).semantics {contentDescription=l.day()+" "+l.title()}) {
                            val date = remember(l.day()) {
                                runCatching { LocalDate.parse(l.day()).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrDefault(l.day())
                            }
                            Copy(if(matrix) date else "Attendance status",12,weight=FontWeight.SemiBold,lines=1)
                            if(matrix) Copy(l.title(),11,Ink.muted,lines=1)
                        }
                    }
                }
            }
            Rule()
            if(students.isEmpty()) Copy("No students match this selection.",14,Ink.muted,modifier=Modifier.padding(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(),state=list) {
                    itemsIndexed(students,key={_,student->student.id()}) { row,student ->
                        Row(Modifier.fillMaxWidth().height(40.dp).background(Ink.panel),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.width(nameWidth).padding(horizontal=8.dp)) {
                                Copy(student.name(),13,lines=1)
                                if(student.number().isNotEmpty())Copy(student.number(),11,Ink.muted,lines=1)
                            }
                            Row(Modifier.weight(1f).then(if(matrix) Modifier.horizontalScroll(scroll) else Modifier),verticalAlignment=Alignment.CenterVertically) {
                                lectures.forEachIndexed { column,lecture ->
                                    key(lecture.id()) {
                                        val target=RegisterKey(student.id(),lecture.id())
                                        val requester=remember {FocusRequester()}
                                        DisposableEffect(target) {
                                            focusTargets[target]=requester
                                            onDispose { if(focusTargets[target]===requester) focusTargets.remove(target) }
                                        }
                                        Box(Modifier.width(cellWidth).padding(horizontal=3.dp)) {
                                            MarkCell(editor,student,lecture,matrix,busy,requester){move(row,column,it)}
                                        }
                                    }
                                }
                            }
                        }
                        Rule()
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list),Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
            }
            if(matrix) HorizontalScrollbar(rememberScrollbarAdapter(scroll),Modifier.padding(start=nameWidth).fillMaxWidth().height(10.dp))
        }
    }
}

@Composable
private fun MarkCell(editor: AttendanceSheetEditor,student:RegisterStudent,lecture:RegisterLecture,compact:Boolean,busy:Boolean,
                     requester:FocusRequester,move:(KeyEvent)->Boolean) {
    val key = RegisterKey(student.id(),lecture.id())
    val value = editor.value(key)
    val dirty = key in editor.edits
    val conflict = editor.conflict(key)
    val editable = !busy && editor.editable(key)
    var expanded by remember(key) {mutableStateOf(false)}
    val interaction = remember {MutableInteractionSource()}
    var focus by remember { mutableStateOf(false) }
    val color = when(value) {
        AttendanceStatus.PRESENT -> Ink.positive
        AttendanceStatus.ABSENT -> LocalPalette.current.danger
        AttendanceStatus.LATE -> LocalPalette.current.warning
        AttendanceStatus.EXCUSED -> LocalPalette.current.info
        null -> Ink.muted
    }
    val label = attendanceLabel(value)
    Box {
        Box(Modifier.fillMaxWidth().height(30.dp).clip(ActionShape).background(color.copy(alpha=.09f))
            .border(if(focus || dirty) 2.dp else 1.dp, when {focus->Ink.text; conflict->LocalPalette.current.warning; dirty->Ink.violet; else->Ink.border},ActionShape)
            .focusRequester(requester).onFocusChanged { focus=it.isFocused }
            .onPreviewKeyEvent { event ->
                if(event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else if(event.key==Key.Enter || event.key==Key.NumPadEnter || event.key==Key.Spacebar) {
                    if(editable && event.type==KeyEventType.KeyDown) expanded=true
                    editable
                }
                else if(event.type!=KeyEventType.KeyDown) false
                else {
                    if(move(event)) return@onPreviewKeyEvent true
                    if(!editable) return@onPreviewKeyEvent false
                    val next = when(event.key) {
                        Key.P->AttendanceStatus.PRESENT; Key.A->AttendanceStatus.ABSENT; Key.L->AttendanceStatus.LATE; Key.E->AttendanceStatus.EXCUSED
                        Key.U,Key.Delete->null; else->return@onPreviewKeyEvent false
                    }
                    editor.change(key,next);true
                }
            }.then(if(editable) Modifier.clickable(interactionSource=interaction,indication=LocalIndication.current,role=Role.Button){expanded=true}
                else Modifier.focusable())
            .testTag("mark-${student.id()}-${lecture.id()}").semantics {
                contentDescription="${student.name()}, ${lecture.title()}, $label"
                stateDescription=if(conflict)"Conflict" else if(dirty)"Unsaved" else label
            },contentAlignment=Alignment.Center) {
            val text = if(compact) value?.name?.take(1) ?: "—" else label
            Copy(text + if(dirty)" *" else "",13,color,FontWeight.SemiBold)
        }
        if(expanded) Popup(alignment=Alignment.BottomStart,onDismissRequest={expanded=false;requester.requestFocus()},properties=PopupProperties(focusable=true)) {
            val options=attendanceChoices.filter {it!=null || editor.sheet.complete()}
            val firstFocus=remember { FocusRequester() }
            val manager=LocalFocusManager.current
            Column(Modifier.width(146.dp).clip(ActionShape).background(Ink.panel)
                .border(1.dp,Ink.border,ActionShape).padding(4.dp).onPreviewKeyEvent { event ->
                    event.type==KeyEventType.KeyDown && when(event.key) {
                        Key.DirectionDown -> manager.moveFocus(FocusDirection.Next)
                        Key.DirectionUp -> manager.moveFocus(FocusDirection.Previous)
                        else -> false
                    }
                }) {
                options.forEach {status->
                    Action(attendanceLabel(status),active=status==value,quiet=true,
                        modifier=Modifier.fillMaxWidth().then(if(status==value) Modifier.focusRequester(firstFocus) else Modifier)) {
                        editor.change(key,status);expanded=false;requester.requestFocus()
                    }
                }
                if(conflict) Action("Use saved mark"){editor.edits.remove(key);expanded=false}
            }
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
        }
    }
}

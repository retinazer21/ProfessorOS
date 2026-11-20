package com.professoros.compose

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
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

@Composable
internal fun AttendanceRegister(editor: AttendanceSheetEditor, busy: Boolean, save: () -> Unit) {
    val lectures = editor.sheet.lectures()
    var lectureId by remember(editor) { mutableStateOf(lectures.firstOrNull()?.id()) }
    val selected = lectures.find { it.id() == lectureId } ?: lectures.firstOrNull()
    var matrix by remember { mutableStateOf(false) }
    var query by remember(editor) { mutableStateOf("") }
    var filter by remember(editor) { mutableStateOf("ALL") }
    var bulk by remember { mutableStateOf(AttendanceStatus.PRESENT) }
    var discard by remember(editor) { mutableStateOf(false) }
    val students = remember(editor.sheet, query) {
        editor.sheet.students().filter { (it.name()+" "+it.number()).contains(query.trim(),true) }
            .sortedWith(compareBy({ it.number() }, { it.name() }, { it.id() }))
    }
    val roster = students.filter { student ->
        selected == null || filter == "ALL" || (editor.value(RegisterKey(student.id(),selected.id()))?.name ?: "UNMARKED") == filter
    }
    val pending = editor.edits.size
    val conflicts = editor.edits.keys.count { editor.conflict(it) || !editor.editable(it) }
    val canSave = pending > 0 && conflicts == 0 && !editor.needsRefresh && !busy
    Column(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.S && (it.isCtrlPressed || it.isMetaPressed)) {
            if (canSave) save()
            true
        } else false
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Toolbar {
            Action("Lecture view","lecture",active=!matrix){matrix=false}
            Action("Matrix view","grid",active=matrix){matrix=true}
        }
        if (selected == null) { Copy("No lectures in this offering.",14,Ink.muted); return@Column }
        val statuses = attendanceChoices.map { Choice(it?.name ?: "UNMARKED",attendanceLabel(it)) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 720.dp
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val lecturePicker: @Composable () -> Unit = {
                    Select("Lecture",selected.id().toString(),lectures.map { Choice(it.id().toString(),it.day()+" · "+it.title()) },
                        {lectureId=UUID.fromString(it)},"sheet-lecture")
                }
                if (narrow) lecturePicker()
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Bottom) {
                    if (!narrow) Column(Modifier.weight(1.7f)){lecturePicker()}
                    Input("Find student",query,{query=it},Modifier.weight(1.15f),tag="sheet-search")
                    Column(Modifier.weight(1f)) { Select("Lecture status",filter,listOf(Choice("ALL","All statuses"))+statuses,{filter=it}) }
                }
            }
        }
        val totals = editor.sheet.students().groupingBy { editor.value(RegisterKey(it.id(),selected.id())) }.eachCount()
        Copy(attendanceChoices.joinToString("   ·   ") { "${totals[it] ?: 0} ${attendanceLabel(it)}" },13,Ink.text)
        if (selected.editable() && editor.sheet.complete()) {
            val blanks = roster.count {
                val k=RegisterKey(it.id(),selected.id())
                editor.editable(k) && editor.value(k)==null && k !in editor.edits
            }
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(142.dp)){Select("Bulk status",bulk.name,statuses.filter { it.value!="UNMARKED" },{bulk=AttendanceStatus.valueOf(it)})}
                Action("Fill $blanks visible blanks","people",enabled=!busy && blanks>0) {editor.fillBlanks(roster,selected.id(),bulk)}
            }
        }
        if (!editor.sheet.complete()) Copy("Recorded students only. The current server supports changes to existing marks.",12,Ink.muted)
        if (!selected.editable()) Copy("This lecture is read-only.",12,Ink.muted)
        if (conflicts>0) Copy("$conflicts changed or unavailable on the server. Review the highlighted cells or discard these edits.",13,LocalPalette.current.warning)
        Copy("P Present   A Absent   L Late   E Excused   U Unmarked   ·   Arrow keys move   ·   Ctrl+S saves",12,Ink.muted)
        RegisterGrid(editor,roster,if(matrix) lectures else listOf(selected),matrix,busy,Modifier.weight(1f)) {
            lectureId=it;matrix=false
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action("Save attendance","check",variant=ActionVariant.PRIMARY,enabled=canSave,modifier=Modifier.testTag("sheet-save"),onClick=save)
            Copy(if(pending==0) "No pending edits" else "$pending unsaved in this offering",13,if(pending==0) Ink.muted else Ink.violet,modifier=Modifier.weight(1f))
            if(pending>0) Action(if(discard) "Confirm discard" else "Discard edits",quiet=true,enabled=!busy) {
                if(discard) editor.edits.clear()
                discard=!discard
            }
            if(discard) Action("Keep edits",quiet=true){discard=false}
        }
    }
}

@Composable
internal fun RegisterGrid(editor: AttendanceSheetEditor, students: List<RegisterStudent>, lectures: List<RegisterLecture>,
                         matrix: Boolean, busy: Boolean, modifier: Modifier, openLecture:(UUID)->Unit) {
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxWidth().border(1.dp,Ink.border,ActionShape).clip(ActionShape)) {
        val nameWidth = if(matrix) (maxWidth*.30f).coerceIn(148.dp,280.dp) else (maxWidth*.55f).coerceAtMost(540.dp)
        val cellWidth = if(matrix) 90.dp else 180.dp
        Column(Modifier.fillMaxSize().padding(1.dp)) {
            Row(Modifier.fillMaxWidth().background(Ink.raised),verticalAlignment=Alignment.CenterVertically) {
                Copy("Student",13,weight=FontWeight.SemiBold,modifier=Modifier.width(nameWidth).padding(10.dp))
                Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                    lectures.forEach { l ->
                        Column(Modifier.width(cellWidth).clickable(enabled=matrix){openLecture(l.id())}.padding(6.dp).semantics {contentDescription=l.day()+" "+l.title()}) {
                            Copy(if(matrix) l.day() else "Attendance status",12,weight=FontWeight.SemiBold)
                            if(matrix) Copy(l.title(),11,Ink.muted,lines=1)
                        }
                    }
                }
            }
            Rule()
            if(students.isEmpty()) Copy("No students match this selection.",14,Ink.muted,modifier=Modifier.padding(16.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(),state=list) {
                    items(students,key={it.id()}) { student ->
                        Row(Modifier.fillMaxWidth().height(54.dp).background(Ink.panel),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.width(nameWidth).padding(horizontal=10.dp)) {
                                Copy(student.name(),14,lines=1)
                                if(student.number().isNotEmpty())Copy(student.number(),11,Ink.muted,lines=1)
                            }
                            Row(Modifier.weight(1f).horizontalScroll(scroll),verticalAlignment=Alignment.CenterVertically) {
                                lectures.forEach { lecture ->
                                    Box(Modifier.width(cellWidth).padding(horizontal=5.dp)) {
                                        MarkCell(editor,student,lecture,matrix,busy)
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
private fun MarkCell(editor: AttendanceSheetEditor,student:RegisterStudent,lecture:RegisterLecture,compact:Boolean,busy:Boolean) {
    val key = RegisterKey(student.id(),lecture.id())
    val value = editor.value(key)
    val dirty = key in editor.edits
    val conflict = editor.conflict(key)
    val editable = !busy && editor.editable(key)
    var expanded by remember(key) {mutableStateOf(false)}
    val interaction = remember {MutableInteractionSource()}
    val focus by interaction.collectIsFocusedAsState()
    val focusManager=LocalFocusManager.current
    val color = when(value) {
        AttendanceStatus.PRESENT -> Ink.positive
        AttendanceStatus.ABSENT -> LocalPalette.current.danger
        AttendanceStatus.LATE -> LocalPalette.current.warning
        AttendanceStatus.EXCUSED -> LocalPalette.current.info
        null -> Ink.muted
    }
    val label = attendanceLabel(value)
    Box {
        Box(Modifier.fillMaxWidth().height(36.dp).clip(ActionShape).background(color.copy(alpha=.09f))
            .border(if(focus || dirty) 2.dp else 1.dp, when {focus->Ink.text; conflict->LocalPalette.current.warning; dirty->Ink.violet; else->Ink.border},ActionShape)
            .onPreviewKeyEvent { event ->
                if(event.type!=KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else {
                    val direction=when(event.key) {
                        Key.DirectionUp->FocusDirection.Up; Key.DirectionDown->FocusDirection.Down
                        Key.DirectionRight->FocusDirection.Right; Key.DirectionLeft->FocusDirection.Left; else->null
                    }
                    if(direction!=null) return@onPreviewKeyEvent focusManager.moveFocus(direction)
                    if(!editable) return@onPreviewKeyEvent false
                    val next = when(event.key) {
                        Key.P->AttendanceStatus.PRESENT; Key.A->AttendanceStatus.ABSENT; Key.L->AttendanceStatus.LATE; Key.E->AttendanceStatus.EXCUSED
                        Key.U,Key.Delete->null; else->return@onPreviewKeyEvent false
                    }
                    editor.change(key,next);true
                }
            }.clickable(enabled=editable,interactionSource=interaction,indication=LocalIndication.current,role=Role.Button){expanded=true}
            .testTag("mark-${student.id()}-${lecture.id()}").semantics {
                contentDescription="${student.name()}, ${lecture.title()}, $label"
                stateDescription=if(conflict)"Conflict" else if(dirty)"Unsaved" else label
            },contentAlignment=Alignment.Center) {
            val text = if(compact) value?.name?.take(1) ?: "—" else label
            Copy(text + if(dirty)" *" else "",13,color,FontWeight.SemiBold)
        }
        if(expanded) Popup(alignment=Alignment.BottomStart,offset=IntOffset(0,36),onDismissRequest={expanded=false},properties=PopupProperties(focusable=true)) {
            Panel(Modifier.width(164.dp)) {
                attendanceChoices.filter {it!=null || editor.sheet.complete()}.forEach {status->
                    Action(attendanceLabel(status),active=status==value) {editor.change(key,status);expanded=false}
                }
                if(conflict) Action("Use saved mark"){editor.edits.remove(key);expanded=false}
            }
        }
    }
}

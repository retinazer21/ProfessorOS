package com.professoros.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.professoros.service.PortalPublishingClient
import com.professoros.service.PortalPublishingClient.*
import java.util.UUID
import kotlinx.coroutines.*

@Composable
internal fun OnlineAttendance(client: PortalPublishingClient, recovered: MutableMap<UUID,Map<RegisterKey,PendingMark>>,
                              stateChanged:(Boolean)->Unit, logout:()->Unit) {
    val scope = rememberCoroutineScope()
    var catalog by remember {mutableStateOf(emptyList<Offering>())}
    var selected by remember {mutableStateOf<Offering?>(null)}
    val editors = remember {mutableStateMapOf<UUID,AttendanceSheetEditor>()}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var loaded by remember {mutableStateOf(false)}
    val drafts=editors.mapValues {it.value.edits.toMap()}
    val pending=drafts.values.sumOf {it.size}+recovered.filterKeys {it !in editors}.values.sumOf {it.size}
    SideEffect {
        stateChanged(busy)
        drafts.forEach {(id,edits)->if(edits.isEmpty()) recovered.remove(id) else recovered[id]=edits}
    }
    fun load(next:Offering?,refreshCatalog:Boolean) {
        if(busy) return
        busy=true
        message=""
        scope.launch {
            try {
                if(refreshCatalog) catalog=withContext(Dispatchers.IO){client.attendanceOfferings()}
                val offering=catalog.firstOrNull {it.id()==next?.id()} ?: catalog.firstOrNull()
                selected=offering
                loaded=true
                if(offering!=null) {
                    val data=withContext(Dispatchers.IO){client.attendanceSheet(offering.id())}
                    val existing=editors[offering.id()]
                    if(existing==null) {
                        val editor=AttendanceSheetEditor(data)
                        recovered[offering.id()]?.let {editor.edits.putAll(it);editor.refresh(data)}
                        editors[offering.id()]=editor
                    } else existing.refresh(data)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){message="Could not load the register. Your edits are retained. Check the connection and refresh."}
            finally {busy=false}
        }
    }
    LaunchedEffect(client){load(null,true)}
    fun save(offering:Offering,editor:AttendanceSheetEditor) {
        if(busy || editor.needsRefresh || editor.edits.isEmpty()) return
        val batch=editor.edits.toMap()
        busy=true
        message=""
        scope.launch {
            try {
                if(editor.sheet.complete()) {
                    val edits=batch.map {(key,value)->AttendanceEdit(key.student,key.lecture,value.expected?.name,value.desired?.name)}
                    withContext(Dispatchers.IO){client.saveAttendanceSheet(offering.id(),edits)}
                    editor.confirmed(batch)
                } else {
                    // The deployed v1 endpoint acknowledges each recorded cell independently.
                    for((key,edit) in batch) {
                        val original=AttendanceMark(key.student,key.lecture,offering.title(),"","","",requireNotNull(edit.expected).name)
                        withContext(Dispatchers.IO){client.changeAttendance(original,requireNotNull(edit.desired).name)}
                        editor.confirmed(mapOf(key to edit))
                    }
                }
                message="Attendance saved."
            } catch(e:CancellationException){throw e}
            catch(e:AttendanceConflict){
                editor.batchRejected()
                message=requireNotNull(e.message)
            }
            catch(e:Exception){message="Save interrupted. ${batch.size-editor.edits.size} confirmed, ${editor.edits.size} pending. Refresh to compare saved marks."}
            finally {busy=false}
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.background)) {
        Column(Modifier.fillMaxSize().padding(if(maxWidth<720.dp)12.dp else 24.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Copy("Attendance",26,weight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                Action("Refresh","refresh",enabled=!busy){load(selected,true)}
                Action("Sign out",quiet=true,enabled=!busy,onClick=logout)
            }
            if(message.isNotEmpty()) Copy(message,13,Ink.text)
            if(busy) Copy("Contacting Supabase…",12,Ink.muted)
            val offering=selected
            if(offering==null) {
                if(loaded && catalog.isEmpty()) Copy("No imported offerings are assigned to this account.",14,Ink.muted)
                return@Column
            }
            val termOffers=catalog.filter {it.term()==offering.term()}
            val courseOffers=termOffers.filter {it.code()==offering.code()}
            CompositionLocalProvider(LocalControlsEnabled provides !busy) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)){Select("Term",offering.term(),catalog.distinctBy {it.term()}.map {Choice(it.term(),it.term())},
                        {v->load(catalog.first {it.term()==v},false)})}
                    Column(Modifier.weight(1.4f)){Select("Course",offering.code(),termOffers.distinctBy {it.code()}.map {Choice(it.code(),it.code()+" · "+it.title())},
                        {v->load(termOffers.first {it.code()==v},false)})}
                    Column(Modifier.weight(.8f)){Select("Offering",offering.id().toString(),courseOffers.map {Choice(it.id().toString(),"Section "+it.section())},
                        {v->load(courseOffers.first {it.id().toString()==v},false)})}
                }
            }
            val editor=editors[offering.id()]
            val elsewhere=pending-(editor?.edits?.size ?: 0)
            if(elsewhere>0) Copy("$elsewhere unsaved in other offerings",12,Ink.violet)
            if(editor!=null) key(offering.id()){AttendanceRegister(editor,busy){save(offering,editor)}}
        }
    }
}

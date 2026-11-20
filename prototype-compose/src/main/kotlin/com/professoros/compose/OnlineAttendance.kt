package com.professoros.compose

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.professoros.service.PortalPublishingClient
import com.professoros.service.PortalPublishingClient.*
import java.util.UUID
import kotlinx.coroutines.*

internal class OnlineAttendanceState {
    var catalog by mutableStateOf(emptyList<Offering>())
    var selected by mutableStateOf<Offering?>(null)
    val editors = mutableStateMapOf<UUID,AttendanceSheetEditor>()
    val views = mutableMapOf<UUID,RegisterViewState>()
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var loaded by mutableStateOf(false)
}

@Composable
internal fun OnlineAttendance(client: PortalPublishingClient, recovered: MutableMap<UUID,Map<RegisterKey,PendingMark>>,
                              stateChanged:(Boolean)->Unit, logout:()->Unit) =
    OnlineAttendance(client,recovered,stateChanged,logout,remember(client){OnlineAttendanceState()},0,false)

@Composable
internal fun OnlineAttendance(client: PortalPublishingClient, recovered: MutableMap<UUID,Map<RegisterKey,PendingMark>>,
                              stateChanged:(Boolean)->Unit, logout:()->Unit, state:OnlineAttendanceState,
                              refresh:Int, embedded:Boolean, scope:CoroutineScope = rememberCoroutineScope()) {
    with(state) {
        val drafts=editors.mapValues {it.value.edits.toMap()}
        val pending=drafts.values.sumOf {it.size}+recovered.filterKeys {it !in editors}.values.sumOf {it.size}
        SideEffect {
            stateChanged(busy)
            drafts.forEach { (id,edits) ->
                if(edits.isEmpty()) {
                    if(id in recovered) recovered.remove(id)
                } else if(recovered[id]!=edits) recovered[id]=edits
            }
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
        LaunchedEffect(client,refresh){load(selected,true)}
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
        Column(Modifier.fillMaxSize().then(if(embedded) Modifier else Modifier.padding(12.dp)),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            val offering = selected
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if (offering!=null) Box(Modifier.weight(1f)) {
                    RegisterMenu("Teaching context",offering.contextLabel(),
                        catalog.map { Choice(it.id().toString(),it.contextLabel()) },!busy,"sheet-context") { id ->
                        load(catalog.first {it.id().toString()==id},false)
                    }
                } else Copy(if(loaded) "No imported offerings assigned" else "Loading attendance…",modifier=Modifier.weight(1f))
                if(!embedded) {
                    Action("Refresh","refresh",enabled=!busy){load(selected,true)}
                    Action("Sign out",quiet=true,enabled=!busy,onClick=logout)
                }
            }
            if(message.isNotEmpty()) Copy(message,13,Ink.text)
            else if(busy && offering!=null) Copy("Contacting Supabase…",12,Ink.muted)
            if(offering!=null) {
                val editor=editors[offering.id()]
                val elsewhere=pending-(editor?.edits?.size ?: 0)
                if(elsewhere>0) Copy("$elsewhere unsaved in other offerings",12,Ink.violet)
                if(editor!=null) key(offering.id()) {
                    AttendanceRegister(editor,busy,views.getOrPut(offering.id()){RegisterViewState()}){save(offering,editor)}
                }
            }
        }
    }
}

private fun Offering.contextLabel() = "${term()}  /  ${code()} ${title()}  /  Section ${section()}"

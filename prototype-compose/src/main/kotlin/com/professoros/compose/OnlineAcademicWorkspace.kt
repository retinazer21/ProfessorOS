package com.professoros.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.professoros.database.WorkspaceProjection
import com.professoros.service.PortalPublishingClient
import com.professoros.service.CloudAcademicWrites
import kotlinx.coroutines.*

/** Existing academic views backed by the authenticated professor's private snapshot. */
@Composable
internal fun OnlineAcademicWorkspace(client: PortalPublishingClient, settings: AppearanceSettings,
                                     closeGuard: (((() -> Unit) -> Unit)?) -> Unit,
                                     drafts: MutableMap<java.util.UUID,Map<RegisterKey,PendingMark>>, stateChanged: (Boolean) -> Unit, logout: () -> Unit) {
    var projection by remember(client) { mutableStateOf<WorkspaceProjection?>(null) }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(client,attempt) {
        var owned: WorkspaceProjection? = null
        try {
            error = false
            withContext(Dispatchers.IO) { owned = client.openWorkspaceView() }
            projection = owned
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            java.util.logging.Logger.getLogger("OnlineWorkspace").warning("Workspace read failed: " + failure.javaClass.simpleName)
            error = true
        } finally {
            projection = null
            owned?.close()
        }
    }
    val attendance = remember(client) { OnlineAttendanceState() }
    val attendanceScope = rememberCoroutineScope()
    var page by remember(client) { mutableStateOf(if(drafts.isEmpty()) "Overview" else "Attendance") }
    var attendanceRefresh by remember(client) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Ink.background)) {
        val model = projection
        if (model == null && page == "Attendance") {
            ProfessorDashboard(null,attendance.busy,null,page,{ page=it },{ attendanceRefresh++ },{},
                embeddedAttendance=true,sessionActions={ Action("Sign out",quiet=true,enabled=!attendance.busy,onClick=logout) }) {
                OnlineAttendance(client,drafts,stateChanged,logout,attendance,attendanceRefresh,embedded=true,scope=attendanceScope)
            }
            return@Column
        }
        if (model == null) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Copy(if (error) "The academic workspace could not be opened. Check your connection and workspace assignment."
                    else "Loading academic records…")
                if (error) Action("Retry") { attempt++ }
                Action("Attendance"){page="Attendance"}
                Action("Sign out",quiet=true,onClick=logout)
            }
            return@Column
        }
        val scope = rememberCoroutineScope()
        val workspace = remember(model) {
            var initial = true
            val services = WorkspaceServices(model.readOnlyDatabase(), CloudAcademicWrites(client,model), client) {
                if (initial) initial = false else client.refreshWorkspaceView(model)
            }
            WorkspaceController(services,scope)
        }
        DisposableEffect(workspace) {
            closeGuard { close -> workspace.guard(action = close) }
            onDispose { closeGuard(null) }
        }
        LaunchedEffect(workspace) { workspace.refresh() }
        ProfessorDashboard(workspace.snapshot,workspace.busy || attendance.busy,workspace.loadError.takeUnless { page=="Attendance" },page,
            {next -> if(!attendance.busy) workspace.guard {page=next} },
            { if(page=="Attendance") attendanceRefresh++ else workspace.guard {workspace.refresh()} },
            {id -> workspace.select(id) {page="Teaching context"}},
            embeddedAttendance=true,
            sessionActions={ Action("Sign out",quiet=true,enabled=!workspace.busy&&!attendance.busy) {workspace.guard {logout()}} },
            featureContent={destination ->
                if(destination=="Attendance")
                    OnlineAttendance(client,drafts,stateChanged,logout,attendance,attendanceRefresh,embedded=true,scope=attendanceScope)
                else CompositionLocalProvider(LocalControlsEnabled provides !workspace.busy) {
                    AcademicScreen(destination,workspace,settings) {page=it}
                }
            })
        WorkspaceOverlays(workspace)
    }
}

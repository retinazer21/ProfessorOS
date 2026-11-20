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
                                     attendance: () -> Unit, logout: () -> Unit) {
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
    Column(Modifier.fillMaxSize().background(Ink.background)) {
        val model = projection
        if (model == null) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Copy(if (error) "The academic workspace could not be opened. Check your connection and workspace assignment."
                    else "Loading academic records…")
                if (error) Action("Retry") { attempt++ }
                Action("Attendance",onClick=attendance)
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
        var page by remember(model) { mutableStateOf("Overview") }
        LaunchedEffect(workspace) { workspace.refresh() }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp, vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
            Copy("Online academic workspace", modifier=Modifier.weight(1f))
            Action("Attendance",quiet=true,enabled=!workspace.busy) {workspace.guard {attendance()}}
            Action("Sign out",quiet=true,enabled=!workspace.busy) {workspace.guard {logout()}}
        }
        ProfessorDashboard(workspace.snapshot,workspace.busy,workspace.loadError,page,
            {next -> workspace.guard {if(next=="Attendance") attendance() else page=next}},
            {workspace.guard {workspace.refresh()}},{id -> workspace.select(id) {page="Teaching context"}},
            featureContent={destination ->
                CompositionLocalProvider(LocalControlsEnabled provides !workspace.busy) {
                    AcademicScreen(destination,workspace,settings) {page=it}
                }
            })
        WorkspaceOverlays(workspace)
    }
}

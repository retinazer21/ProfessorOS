package com.professoros.compose

import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.professoros.app.AppPaths
import com.professoros.service.PortalPublishingClient
import com.professoros.service.DesktopSessionStore
import kotlinx.coroutines.*

/** Online cutover entry. The SQLite recovery database is never opened by this application. */
fun onlineDesktop() = application {
    val scope=rememberCoroutineScope()
    val settings=remember {AppearanceSettings(AppPaths.databaseFile(),scope)}
    var session by remember {mutableStateOf<PortalPublishingClient?>(null)}
    var busy by remember {mutableStateOf(true)}
    var restoreBlocked by remember {mutableStateOf(false)}
    var vault by remember {mutableStateOf<DesktopSessionStore?>(null)}
    var storageReady by remember {mutableStateOf(false)}
    var failure by remember {mutableStateOf<String?>(null)}
    var registerBusy by remember { mutableStateOf(false) }
    var academicCloseGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val drafts=remember {mutableStateMapOf<java.util.UUID,Map<RegisterKey,PendingMark>>()}
    val pending=drafts.values.sumOf {it.size}
    val width = System.getProperty("professoros.preview.width")?.toIntOrNull()?.coerceIn(880,3840) ?: 1360
    val height = System.getProperty("professoros.preview.height")?.toIntOrNull()?.coerceIn(660,2160) ?: 860
    var draftOwner by remember {mutableStateOf<java.util.UUID?>(null)}
    var exitAfterDiscard by remember { mutableStateOf<Boolean?>(null) }
    val origin = System.getenv("PROFESSOR_OS_SUPABASE_URL")
    fun newClient() = PortalPublishingClient(origin,System.getenv("PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY"))
    suspend fun restore() {
        busy=true
        try {
            if (!storageReady) {
                vault=withContext(Dispatchers.IO){DesktopSessionStore.open(origin)}
                storageReady=true
            }
            val client=withContext(Dispatchers.IO) {
                if(vault==null) null else newClient().let { if(it.restoreSession(vault)) it else null }
            }
            session=client
            draftOwner=client?.professorId()
            restoreBlocked=false
            failure=null
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(e:Exception) {
            restoreBlocked=true
            failure="Could not restore your saved session. Check the connection and close any other Professor OS windows, then retry."
        } finally {busy=false}
    }
    LaunchedEffect(Unit) {restore()}
    DisposableEffect(vault) {
        val current=vault
        onDispose { current?.close() }
    }
    fun logout(close:Boolean,retainDrafts:Boolean=false) {
        if(busy)return
        val previous=session
        session=null
        if(!retainDrafts) {drafts.clear();draftOwner=null}
        registerBusy=false
        exitAfterDiscard=null
        busy=true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when {
                        close || retainDrafts -> previous?.closeLocalSession()
                        previous != null -> previous.signOut()
                        else -> vault?.clear()
                    }
                }
                restoreBlocked=false
                failure=if(retainDrafts) "Your professor access ended. Sign in again to continue." else null
            }
            catch(e:Exception) {failure="The workspace is locked. Server sign-out could not be confirmed."}
            finally {busy=false;if(close)exitApplication()}
        }
    }
    fun requestExit(close:Boolean) {
        if(!registerBusy && !busy) {
            if(pending>0) exitAfterDiscard=close else logout(close)
        }
    }
    Window(onCloseRequest={
        val guard = academicCloseGuard
        if (guard == null) requestExit(true) else guard { requestExit(true) }
    },title="Professor OS",icon=painterResource("icons/professor-os-256.png"),
    state=rememberWindowState(width=width.dp,height=height.dp)) {
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(880,660)
            onDispose {}
        }
        val active=session
        val focused=LocalWindowInfo.current.isWindowFocused
        CompositionLocalProvider(LocalPalette provides workspacePalette(settings.mode,isSystemInDarkTheme(),settings.accent),
            LocalReduced provides (settings.reduced || systemReducesMotion())) {
            if(active==null && restoreBlocked) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    Panel {
                        Copy(failure.orEmpty())
                        Toolbar {
                            Action("Retry",enabled=!busy){scope.launch {restore()}}
                            Action("Forget saved session",enabled=!busy&&storageReady){logout(false)}
                        }
                    }
                }
            } else if(active==null) ProfessorLogin(busy,if(busy) "Checking your professor session…" else failure) {email,password,totp->
                busy=true;failure=null
                scope.launch {
                    try {
                        session=withContext(Dispatchers.IO){
                            val client=newClient()
                            try {
                                client.signIn(email,password,totp)
                                if(drafts.isNotEmpty() && draftOwner!=client.professorId())
                                    throw com.professoros.model.DomainValidationException("Sign in with the account that owns the pending edits.")
                                client.rememberSession(vault)
                                client
                            }
                            catch(e:Exception){try {client.signOut()}catch(cleanup:Exception){e.addSuppressed(cleanup)};throw e}
                        }
                        draftOwner=session?.professorId()
                    } catch(e:Exception) {failure=if(e is com.professoros.model.DomainValidationException)e.message else "Sign-in unavailable. Check the connection and try again."}
                    finally {busy=false}
                }
            } else {
                LaunchedEffect(active,focused) {
                    while(session===active) {
                        val authorized=try {withContext(Dispatchers.IO){active.authorizedProfessor()}}
                            catch(e:java.io.IOException){null}
                            catch(e:com.professoros.model.DomainValidationException){null}
                        if(authorized==false && session===active) {logout(false,true);break}
                        delay(30_000)
                    }
                }
                key(active) {
                    OnlineAcademicWorkspace(active,settings,{academicCloseGuard=it},drafts,{registerBusy=it}) {requestExit(false)}
                }
            }
            exitAfterDiscard?.let { close ->
                    Modal("Discard unsaved attendance?",{exitAfterDiscard=null},footer={
                        Toolbar {
                            Action("Keep editing"){exitAfterDiscard=null}
                            Action("Discard and "+if(close)"close" else "sign out",variant=ActionVariant.DESTRUCTIVE){logout(close)}
                        }
                    }) {Copy("$pending attendance edits are still pending.")}
            }
        }
    }
}

object OnlineDesktopLauncher {
    @JvmStatic fun main(args:Array<String>) {onlineDesktop()}
}

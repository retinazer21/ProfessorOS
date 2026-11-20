package com.professoros.compose

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.professoros.service.*
import com.professoros.model.DomainValidationException
import kotlinx.coroutines.*
import java.util.UUID
import java.util.logging.Logger

private data class PublicationReview(
    val pending: GradePublication,
    val published: PortalPublishingClient.PublishedGrade?
)

@Composable internal fun GradePublishingPanel(c:WorkspaceController, editor:GradebookEditor, close:()->Unit) {
    var enrollment by remember { mutableStateOf("") }
    var offering by remember { mutableStateOf("") }
    var student by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val existingSession = c.services.cloudClient
    var signedIn by remember { mutableStateOf(existingSession != null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<PublicationReview?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember {
        lazy { existingSession ?: DesktopConnection.client() }
    }
    fun signOut() {
        if (existingSession == null && client.isInitialized()) {
            try { client.value.signOut() }
            catch (failure: Exception) {
                Logger.getLogger("PortalPublishing").warning("Session revocation could not be confirmed: " + failure.javaClass.simpleName)
            }
        }
    }
    DisposableEffect(client) {
        onDispose { if (client.isInitialized()) Thread.ofVirtual().start { signOut() } }
    }
    fun run(message: String, operation: suspend () -> Unit) {
        busy = true
        status = message
        scope.launch {
            try { operation() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                review = null
                Logger.getLogger("PortalPublishing").warning("Publication operation failed: " + failure.javaClass.simpleName)
                status = if (failure is DomainValidationException) failure.message.orEmpty()
                    else "Publication could not be confirmed. Your grade is still pending. Load the current publication before trying again."
            } finally { busy = false }
        }
    }
    fun loadSaved(selected: Long, targetOffering: UUID, targetStudent: UUID): GradePublication =
        GradePublication.fromSaved(c.services.academic.grades().load(editor.book.offeringId()), selected, targetOffering, targetStudent)

    val ready = !busy && !c.busy && !editor.dirty && editor.summary().results[enrollment.toLongOrNull()]?.complete() == true
    Panel(Modifier.fillMaxWidth()) {
        Copy("Publish one saved final grade",18)
        Copy(if (existingSession != null) "Review the current publication for this student's linked portal account."
            else "Enter the portal IDs provided by your administrator. Review the current publication before confirming a replacement.",12,Ink.muted)
        Select("Local student",enrollment,editor.book.students().map {Choice(it.enrollment().id().toString(),it.student().studentNumber()+" · "+it.student().fullName())},
            { if (!busy) { enrollment=it; review=null; status="" } })
        editor.summary().results[enrollment.toLongOrNull()]?.let {Copy("Saved result: ${it.weightedPoints()}% · ${it.letter()} · ${it.outcome()}",13)}
        if (existingSession == null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Input("Portal course UUID",offering,{if(!busy){offering=it; review=null; status=""}},Modifier.weight(1f))
            Input("Portal student UUID",student,{if(!busy){student=it; review=null; status=""}},Modifier.weight(1f))
        }
        if (!signedIn) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Input("Professor email",email,{if(!busy)email=it},Modifier.weight(1f))
            Input("Password",password,{if(!busy)password=it},Modifier.weight(1f),secret=true)
            Input("Authenticator code",code,{if(!busy)code=it},Modifier.weight(1f),secret=true)
        }
        review?.let {
            Copy(it.published?.let { grade -> "Currently published: ${grade.score()}% · ${grade.letter()}" }
                ?: "No grade is currently published for this student.",13)
            Copy("Pending publication: ${it.pending.score()}% · ${it.pending.letter()}",13)
        }
        if (status.isNotEmpty()) Copy(status,12,Ink.violet)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action("Load current publication",enabled=ready) {
                val selected = requireNotNull(enrollment.toLongOrNull())
                val targetOffering = offering.trim()
                val targetStudent = student.trim()
                val login = email
                val secret = password
                val otp = code
                password = ""; code = ""; review = null
                run("Loading current publication…") {
                    var authenticated = false
                    try {
                        val needsSignIn = !signedIn
                        val loaded = withContext(Dispatchers.IO) {
                            if (needsSignIn) client.value.signIn(login,secret,otp)
                            authenticated = true
                            val pending = if (existingSession != null) {
                                val target = existingSession.publicationTarget(editor.book.offeringId(),selected)
                                loadSaved(selected,target.offering(),target.student())
                            } else try { loadSaved(selected,UUID.fromString(targetOffering),UUID.fromString(targetStudent)) }
                                catch (failure: IllegalArgumentException) { throw DomainValidationException("Enter valid portal course and student UUIDs.") }
                            PublicationReview(pending,client.value.currentPublication(pending).orElse(null))
                        }
                        signedIn = true
                        review = loaded
                        status = "Review the pending grade and confirm to publish."
                    } catch (failure: Exception) {
                        if (existingSession == null && (!authenticated || failure !is CancellationException)) {
                            withContext(NonCancellable + Dispatchers.IO) { signOut() }
                            signedIn = false
                        }
                        throw failure
                    }
                }
            }
            Action("Confirm and publish",enabled=ready && review!=null) {
                val reviewed = requireNotNull(review)
                val selected = requireNotNull(enrollment.toLongOrNull())
                run("Publishing…") {
                    withContext(Dispatchers.IO) {
                        if (existingSession != null) {
                            val target = existingSession.publicationTarget(editor.book.offeringId(),selected)
                            if (target.offering()!=reviewed.pending.offering() || target.student()!=reviewed.pending.student())
                                throw DomainValidationException("The portal account mapping changed. Reload and review the publication.")
                        }
                        val current = loadSaved(selected,reviewed.pending.offering(),reviewed.pending.student())
                        if (current != reviewed.pending) throw DomainValidationException("The saved grade changed. Load the current publication and review the updated grade.")
                        client.value.publish(reviewed.pending,reviewed.published?.revision())
                    }
                    review = null
                    status = "Published successfully."
                }
            }
            Action("Close",enabled=!busy,onClick=close)
        }
    }
}

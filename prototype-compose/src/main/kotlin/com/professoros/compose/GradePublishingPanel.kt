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

/** Optional, explicit online action; existing local save operations remain unchanged. */
@Composable internal fun GradePublishingPanel(c:WorkspaceController, editor:GradebookEditor, close:()->Unit) {
    var enrollment by remember {mutableStateOf("")}
    var offering by remember {mutableStateOf("")}
    var student by remember {mutableStateOf("")}
    var email by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    // Credentials are ephemeral; the blocking HTTP operation revokes its session in finally.
    val scope = rememberCoroutineScope()
    Panel(Modifier.fillMaxWidth()) {
        Copy("Publish one saved final grade",18)
        Copy("Use administrator-provided portal IDs for this course and student. Publishing replaces that student's published snapshot; local edits remain private until you publish again.",12,Ink.muted)
        Select("Local student",enrollment,editor.book.students().map {Choice(it.enrollment().id().toString(),it.student().studentNumber()+" · "+it.student().fullName())},{if(!busy)enrollment=it})
        editor.summary().results[enrollment.toLongOrNull()]?.let {Copy("Saved result: ${it.weightedPoints()}% · ${it.letter()} · ${it.outcome()}",13)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Input("Portal course UUID",offering,{if(!busy)offering=it},Modifier.weight(1f))
            Input("Portal student UUID",student,{if(!busy)student=it},Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Input("Professor email",email,{if(!busy)email=it},Modifier.weight(1f))
            Input("Password",password,{if(!busy)password=it},Modifier.weight(1f),secret=true)
            Input("Authenticator code",code,{if(!busy)code=it},Modifier.weight(1f),secret=true)
        }
        Copy(status,12,Ink.violet)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Action("Confirm and publish",enabled=!busy&&!editor.dirty&&!c.busy&&editor.summary().results[enrollment.toLongOrNull()]?.complete()==true) {
                busy=true; status="Publishing…"
                val selected=enrollment.toLongOrNull(); val remoteOffering=offering; val remoteStudent=student
                val login=email; val secret=password; val otp=code
                password="";code=""
                scope.launch {
                    try {
                        val message=withContext(Dispatchers.IO) {
                            val saved=c.services.academic.grades().load(editor.book.offeringId())
                            val publication=GradePublication.fromSaved(saved,selected?:throw DomainValidationException("Choose a student."),UUID.fromString(remoteOffering.trim()),UUID.fromString(remoteStudent.trim()))
                            val client=PortalPublishingClient(System.getenv("PROFESSOR_OS_SUPABASE_URL"),System.getenv("PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY"))
                            var published=false
                            try {client.signIn(login,secret,otp);client.publish(publication);published=true}
                            finally {
                                try {client.signOut()} catch(f:Exception) {
                                    Logger.getLogger("PortalPublishing").warning("Session revocation could not be confirmed: "+f.javaClass.simpleName)
                                    if(published) throw DomainValidationException("Grade published, but sign-out could not be confirmed. Revoke this session in Supabase.")
                                }
                            }
                            "Published successfully. Repeating the same course/student mapping will not create a duplicate."
                        }
                        status=message
                    } catch(f:Exception) {
                        if(f is CancellationException) throw f
                        Logger.getLogger("PortalPublishing").warning("Publishing failed: "+f.javaClass.simpleName)
                        status=if(f is DomainValidationException)f.message.orEmpty() else "Publication was not confirmed. Check UUID mappings and connectivity; retrying the same mapping is safe."
                    } finally {busy=false}
                }
            }
            Action("Close",enabled=!busy,onClick=close)
        }
    }
}

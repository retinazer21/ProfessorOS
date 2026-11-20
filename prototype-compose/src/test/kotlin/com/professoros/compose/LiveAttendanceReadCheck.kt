@file:OptIn(androidx.compose.ui.test.InternalTestApi::class,androidx.compose.ui.test.ExperimentalTestApi::class)
package com.professoros.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.professoros.service.PortalPublishingClient
import javax.swing.*

/** Opt-in hosted acceptance. Uses normal Auth and read endpoints. Never sends a mark or writes credentials. */
object LiveAttendanceReadCheck {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1) {"Supply the fictional professor email."}
        val client=PortalPublishingClient(System.getenv("PROFESSOR_OS_SUPABASE_URL"),System.getenv("PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY"))
        val password=checkNotNull(System.console()) {"Use a private interactive console."}.readPassword("Professor fixture password (hidden): ")
        try {
            var code=CharArray(0)
            SwingUtilities.invokeAndWait {
                val field=JPasswordField(8)
                val owner=JFrame("Professor OS verification").apply {isAlwaysOnTop=true}
                try {
                    val choice=JOptionPane.showConfirmDialog(owner,arrayOf("Enter the professor’s current authenticator code.",field),
                        "Professor OS · Read-only attendance check",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)
                    check(choice==JOptionPane.OK_OPTION) {"Verification cancelled."}
                    code=field.password
                    field.text=""
                } finally {owner.dispose()}
            }
            try {client.signIn(args[0],String(password),String(code))} finally {code.fill('\u0000');password.fill('\u0000')}
            val offerings=client.attendanceOfferings()
            check(offerings.isNotEmpty()) {"No assigned imported offerings."}
            for(offering in offerings) {
                val sheet=client.attendanceSheet(offering.id())
                check(sheet.lectures().isNotEmpty() && sheet.students().isNotEmpty())
                val editor=AttendanceSheetEditor(sheet)
                runSkikoComposeUiTest(size=Size(1100f,800f)) {
                    setContent {Column(Modifier.fillMaxSize().background(Ink.background).padding(16.dp)) {
                        AttendanceRegister(editor,false){error("The read-only check must never save.")}
                    }}
                    val student=sheet.students().first()
                    onNodeWithTag("sheet-search").performTextInput(student.name())
                    onNodeWithTag("mark-${student.id()}-${sheet.lectures().first().id()}").assertExists()
                    onNodeWithText("Matrix view").performClick()
                    onNodeWithTag("mark-${student.id()}-${sheet.lectures().last().id()}").assertExists()
                    onNodeWithTag("sheet-save").assertIsNotEnabled()
                }
                println("PASS ${offering.code()} — ${sheet.students().size} students, ${sheet.lectures().size} lectures, ${sheet.marks().size} marks; both views, zero pending edits.")
            }
        } finally {
            password.fill('\u0000')
            client.signOut()
        }
    }
}

@file:OptIn(androidx.compose.ui.test.InternalTestApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
package com.professoros.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.professoros.model.AttendanceStatus.*
import com.professoros.service.PortalPublishingClient.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AttendanceSheetUiTest {
    private val lectures=(1..12).map { RegisterLecture(localId(it.toLong()),"Lecture $it","2026-09-"+it.toString().padStart(2,'0'),true) }
    private val students=listOf("أحمد محمد قاسم","سارة حسن","مريم علي","Omar Ahmed","Lina Hassan").mapIndexed {i,name->
        RegisterStudent(localId(20L+i),name,"CS-"+(101+i),true)
    }
    private val key=RegisterKey(students.first().id(),lectures.first().id())
    private val sheet=AttendanceSheet(students,lectures,listOf(RegisterCell(key.student,key.lecture,"ABSENT")),true)

    @Test fun bulkAndRefreshPreserveOriginalMarksAndPendingClears() {
        val editor=AttendanceSheetEditor(sheet)
        editor.change(key,null)
        editor.fillBlanks(students.take(2),key.lecture,PRESENT)
        assertNull(editor.value(key))
        assertEquals(2,editor.edits.size)
        editor.refresh(AttendanceSheet(students,lectures,listOf(RegisterCell(key.student,key.lecture,"LATE")),true))
        assertTrue(editor.conflict(key))
        assertEquals(ABSENT,editor.edits[key]?.expected)
        editor.change(key,EXCUSED)
        assertEquals(LATE,editor.edits[key]?.expected)
        val submitted=editor.edits.toMap()
        editor.confirmed(submitted)
        assertTrue(editor.edits.isEmpty())
        assertEquals(EXCUSED,editor.value(key))
    }

    @Test fun lectureAndMatrixShareFilteredEditsUntilSave()=runSkikoComposeUiTest(size=Size(1040f,760f)) {
        val editor=AttendanceSheetEditor(sheet)
        var count=0
        var compact by mutableStateOf(false)
        setContent {CompositionLocalProvider(LocalPalette provides if(compact) LightPalette else DarkPalette) {
            Column(Modifier.width(if(compact) 590.dp else 1040.dp).fillMaxHeight().background(Ink.background).padding(16.dp)) {
                AttendanceRegister(editor,false){count++;editor.confirmed(editor.edits.toMap())}
            }
        }}
        fun capture(name:String) {
            waitForIdle()
            val file=File(System.getProperty("compose.test.output"),"$name.png")
            file.parentFile.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",file)
        }
        onNodeWithTag("sheet-search").performTextInput("أحمد")
        val cell=onNodeWithTag("mark-${key.student}-${key.lecture}")
        cell.performClick()
        onNodeWithText("Late",substring=false).performClick()
        assertEquals(LATE,editor.value(key))
        onNodeWithTag("sheet-search").performTextReplacement("")
        capture("online-attendance-lecture")
        runOnIdle {compact=true}
        capture("online-attendance-lecture-compact")
        runOnIdle {compact=false}
        onNodeWithText("Matrix view").performClick()
        assertEquals("Unsaved",cell.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        capture("online-attendance-matrix")
        runOnIdle {compact=true}
        capture("online-attendance-matrix-compact")
        onNodeWithTag("sheet-save").performClick()
        assertEquals(1,count)
        assertTrue(editor.edits.isEmpty())
    }

    @Test fun keyboardCrossesOffscreenRowsAndColumnsInsideDashboard()=runSkikoComposeUiTest(size=Size(1440f,900f)) {
        val roster=(1..36).map { RegisterStudent(localId(100L+it),"Student $it",it.toString().padStart(2,'0'),true) }
        val editor=AttendanceSheetEditor(AttendanceSheet(roster,lectures,emptyList(),true))
        var width by mutableStateOf(900.dp)
        var theme by mutableStateOf(com.professoros.model.ThemeMode.DARK)
        var saves=0
        setContent {
            CompositionLocalProvider(LocalPalette provides workspacePalette(theme,false,"")) {
            Box(Modifier.width(width).fillMaxHeight()) {
                ProfessorDashboard(null,false,null,"Attendance",{},{},{},embeddedAttendance=true) {
                    AttendanceRegister(editor,false){saves++}
                }
            }
            }
        }
        fun cell(row:Int,col:Int)=onNodeWithTag("mark-${roster[row].id()}-${lectures[col].id()}")
        fun captureDashboard(name:String) {
            val output=File(System.getProperty("compose.test.output"),"$name.png")
            output.parentFile.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",output)
        }
        cell(0,0).performSemanticsAction(SemanticsActions.RequestFocus){it()}
        captureDashboard("dashboard-attendance-lecture-compact")
        for(row in 0..20) {
            cell(row,0).performKeyInput {pressKey(Key.DirectionDown)}
            cell(row+1,0).assertIsFocused()
        }
        cell(21,0).performKeyInput {pressKey(Key.A);pressKey(Key.Delete);pressKey(Key.L)}
        assertEquals(LATE,editor.value(RegisterKey(roster[21].id(),lectures[0].id())))
        onNodeWithTag("sheet-search").performTextInput("Student")
        onNodeWithTag("sheet-search").performKeyInput {keyDown(Key.CtrlLeft);pressKey(Key.S);keyUp(Key.CtrlLeft)}
        assertEquals(0,saves)
        onNodeWithText("Matrix view").performClick()
        cell(21,0).performSemanticsAction(SemanticsActions.RequestFocus){it()}
        for(col in 0..10) {
            cell(21,col).performKeyInput {pressKey(Key.DirectionRight)}
            cell(21,col+1).assertIsFocused()
        }
        cell(21,11).performKeyInput {pressKey(Key.Tab)}
        cell(22,0).assertIsFocused().performKeyInput {keyDown(Key.ShiftLeft);pressKey(Key.Tab);keyUp(Key.ShiftLeft)}
        cell(21,11).assertIsFocused().performKeyInput {pressKey(Key.Spacebar)}
        onNodeWithText("Excused",substring=false).performClick()
        assertEquals(EXCUSED,editor.value(RegisterKey(roster[21].id(),lectures[11].id())))
        runOnIdle {width=1440.dp}
        cell(21,11).assertIsFocused().performKeyInput {keyDown(Key.CtrlLeft);pressKey(Key.S);keyUp(Key.CtrlLeft)}
        assertEquals(1,saves)
        captureDashboard("dashboard-attendance-matrix-wide")
        for(mode in com.professoros.model.ThemeMode.entries.filter { it!=com.professoros.model.ThemeMode.SYSTEM }) {
            runOnIdle { theme=mode }
            onNodeWithTag("sheet-save").assertIsDisplayed()
        }
    }
}

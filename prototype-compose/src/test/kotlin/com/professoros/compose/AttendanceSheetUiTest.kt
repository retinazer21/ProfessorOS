@file:OptIn(androidx.compose.ui.test.InternalTestApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
package com.professoros.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
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
        onNodeWithText("Matrix view").performClick()
        assertEquals("Unsaved",cell.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        runOnIdle {compact=true}
        capture("online-attendance-matrix-compact")
        onNodeWithTag("sheet-save").performClick()
        assertEquals(1,count)
        assertTrue(editor.edits.isEmpty())
    }
}

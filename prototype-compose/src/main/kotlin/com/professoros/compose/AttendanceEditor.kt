package com.professoros.compose

import androidx.compose.runtime.*
import com.professoros.model.*
import com.professoros.service.PortalPublishingClient.*
import java.util.UUID
import com.professoros.service.AttendanceCalculator

/** Observable UI adapter; the existing Java draft owns marks and null/unmarked semantics. */
class AttendanceEditor(val book:AttendanceBook,previousLecture:Long?=null) {
    var lectureId by mutableStateOf(book.lectures().find {it.id()==previousLecture}?.id()?:book.lectures().firstOrNull()?.id());private set
    internal val sheet=AttendanceSheetEditor(AttendanceSheet(
        book.students().map { RegisterStudent(localId(it.enrollment().id()),it.student().fullName(),it.student().studentNumber(),it.enrollment().status()!=EnrollmentStatus.WITHDRAWN) },
        book.lectures().map { RegisterLecture(localId(it.id()),it.title(),it.plannedDate().toString(),it.status().acceptsAttendance()) },
        book.records().map { RegisterCell(localId(it.enrollmentId()),localId(it.lectureId()),it.status().name) },true))
    val lecture get()=book.lectures().find {it.id()==lectureId}
    val readOnly get()=lecture?.status()?.acceptsAttendance()!=true
    val dirty get()=sheet.edits.isNotEmpty()
    fun value(enrollment:Long)=lectureId?.let {sheet.value(RegisterKey(localId(enrollment),localId(it)))}
    fun set(enrollment:Long,status:AttendanceStatus?) {lectureId?.let { sheet.change(RegisterKey(localId(enrollment),localId(it)),status) }}
    fun changes()=sheet.edits.map {(key,edit)->AttendanceCellEdit(key.student.leastSignificantBits,key.lecture.leastSignificantBits,edit.expected,edit.desired)}
    fun discard(){sheet.edits.clear()}
    fun select(id:Long) {lectureId=book.lectures().first {it.id()==id}.id()}
    fun history(row:EnrolledStudent):String = buildString {
        append(AttendanceCalculator.student(book,row.enrollment().id()).display())
        append("\n\nSaved records only. Cancelled lectures are excluded from percentages.\n\n")
        book.lectures().forEach {lecture->
            append("${lecture.plannedDate()} · ${lecture.lectureNumber()}. ${lecture.title()} · ${attendanceLabel(book.status(row.enrollment().id(),lecture.id()))}")
            if(lecture.status()==LectureStatus.CANCELLED)append(" (cancelled)")
            append('\n')
        }
    }
}
internal fun localId(value:Long)=UUID(0,value)
internal fun attendanceLabel(status:AttendanceStatus?)=status?.name?.lowercase()?.replaceFirstChar {it.uppercase()}?:"Unmarked"
internal val attendanceChoices=listOf<AttendanceStatus?>(AttendanceStatus.PRESENT,AttendanceStatus.ABSENT,AttendanceStatus.LATE,AttendanceStatus.EXCUSED,null)

internal fun WorkspaceController.markAttendanceRoster(status:AttendanceStatus,visible:List<EnrolledStudent>) {
    val editor=attendance?:return
    if(busy||editor.readOnly)return
    val ids=visible.map {localId(it.enrollment().id())}.toSet()
    val students=editor.sheet.sheet.students().filter {it.id() in ids}
    val lecture=localId(editor.lectureId ?: return)
    confirm("Fill visible blanks?","Stage ${attendanceLabel(status)} for visible unmarked students in this lecture. Existing marks and pending edits are preserved.") {
        editor.sheet.fillBlanks(students,lecture,status)
    }
}

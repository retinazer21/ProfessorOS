package com.professoros.compose

import androidx.compose.runtime.*
import com.professoros.model.AttendanceStatus
import com.professoros.service.PortalPublishingClient.*
import java.util.UUID

internal data class RegisterKey(val student: UUID, val lecture: UUID)
internal data class PendingMark(val expected: AttendanceStatus?, val desired: AttendanceStatus?)

/** One editor per offering. The original value travels with each edit, including an explicit clear. */
internal class AttendanceSheetEditor(sheet: AttendanceSheet) {
    var sheet by mutableStateOf(sheet)
        private set
    private var saved by mutableStateOf(savedCells(sheet))
    val edits = mutableStateMapOf<RegisterKey, PendingMark>()
    var needsRefresh by mutableStateOf(false)
        private set

    fun batchRejected() { needsRefresh = true }

    fun value(key: RegisterKey) = if (edits.containsKey(key)) edits[key]?.desired else saved[key]
    fun conflict(key: RegisterKey) = edits[key]?.let { saved[key] != it.expected } ?: false
    fun editable(key: RegisterKey) = sheet.lectures().any { it.id() == key.lecture && it.editable() } &&
        sheet.students().any { it.id() == key.student && it.active() } && (sheet.complete() || saved.containsKey(key))
    fun change(key: RegisterKey, next: AttendanceStatus?) {
        if (!editable(key) || (!sheet.complete() && next == null)) return
        if (saved[key] == next) edits.remove(key)
        else edits[key] = PendingMark(saved[key], next)
    }
    fun fillBlanks(students: List<RegisterStudent>, lecture: UUID, status: AttendanceStatus) {
        for (student in students) {
            val key = RegisterKey(student.id(), lecture)
            if (!edits.containsKey(key) && saved[key] == null) change(key, status)
        }
    }
    fun refresh(current: AttendanceSheet) {
        sheet = current
        saved = savedCells(current)
        needsRefresh = false
        // A fresh server read can confirm a previously ambiguous write.
        edits.keys.toList().filter { editable(it) && saved[it] == edits[it]?.desired }.forEach(edits::remove)
    }
    fun confirmed(changes: Map<RegisterKey, PendingMark>) {
        saved = saved.toMutableMap().apply {
            for ((key, edit) in changes) if (edit.desired == null) remove(key) else put(key, edit.desired)
        }
        changes.forEach { (key, edit) -> if (edits[key] == edit) edits.remove(key) }
    }
    private fun savedCells(data: AttendanceSheet) = data.marks().associate {
        RegisterKey(it.enrollment(), it.lecture()) to AttendanceStatus.valueOf(it.status())
    }
}

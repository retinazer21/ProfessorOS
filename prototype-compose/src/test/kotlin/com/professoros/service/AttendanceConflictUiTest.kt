@file:OptIn(androidx.compose.ui.test.InternalTestApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
package com.professoros.service

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.google.gson.Gson
import com.professoros.compose.OnlineAttendance
import com.professoros.compose.PendingMark
import com.professoros.compose.RegisterKey
import com.professoros.model.AttendanceStatus.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class AttendanceConflictUiTest {
    @Test fun staleBatchKeepsBothDraftsUntilTheProfessorReviewsTheRefresh() {
        val offer = UUID.randomUUID()
        val student = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val attempts = AtomicInteger()
        val json = Gson()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            request.use {
                var status = 200
                val body: Any = when (it.requestURI.path.substringAfterLast('/')) {
                    "token", "verify" -> mapOf("access_token" to "fixture")
                    "user" -> mapOf("id" to student, "factors" to listOf(mapOf("id" to "factor", "status" to "verified", "factor_type" to "totp")))
                    "challenge" -> mapOf("id" to "challenge")
                    "portal_access" -> "professor"
                    "portal_course_catalog" -> listOf(PortalPublishingClient.Offering(offer,"Course","CS201","Fall","A"))
                    "desktop_attendance_sheet" -> PortalPublishingClient.AttendanceSheet(
                        listOf(PortalPublishingClient.RegisterStudent(student,"Student A","001",true)),
                        listOf(first,second).mapIndexed { i, id -> PortalPublishingClient.RegisterLecture(id,"Lecture ${i+1}","2026-10-12",true) },
                        listOf(PortalPublishingClient.RegisterCell(student,first,"PRESENT"),
                            PortalPublishingClient.RegisterCell(student,second,if(attempts.get()==0) "ABSENT" else "LATE")), true)
                    "desktop_save_attendance_sheet" -> {
                        attempts.incrementAndGet()
                        status = 409
                        mapOf("code" to "PT409")
                    }
                    else -> { status = 404; emptyMap<String,String>() }
                }
                val response = json.toJson(body).toByteArray()
                it.sendResponseHeaders(status,response.size.toLong())
                it.responseBody.write(response)
            }
        }
        server.start()
        try {
            val client = PortalPublishingClient(URI.create("http://127.0.0.1:${server.address.port}"),"fixture",HttpClient.newHttpClient())
            client.signIn("professor@example.invalid","fixture","123456")
            val originals = mapOf(RegisterKey(student,first) to PendingMark(PRESENT,EXCUSED),
                RegisterKey(student,second) to PendingMark(ABSENT,EXCUSED))
            val drafts = mutableStateMapOf(offer to originals)
            runSkikoComposeUiTest(size=Size(1280f,900f)) {
                setContent { OnlineAttendance(client,drafts,{},{error("Unexpected sign-out")}) }
                waitUntil(timeoutMillis=10000) { onAllNodesWithTag("sheet-save").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("sheet-save").assertIsEnabled().performClick()
                waitUntil(timeoutMillis=10000) { onAllNodesWithText("No edits in this batch were saved",substring=true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(originals,drafts[offer])
                onNodeWithTag("sheet-save").assertIsNotEnabled()
                onNodeWithText("Attendance saved.").assertDoesNotExist()
                val screenshot = File(System.getProperty("compose.test.output"), "online-attendance-conflict.png")
                screenshot.parentFile.mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",screenshot)
                onNodeWithText("Refresh").performClick()
                waitUntil(timeoutMillis=10000) { onAllNodesWithText("1 changed or unavailable",substring=true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("sheet-save").assertIsNotEnabled()
                assertEquals(originals,drafts[offer])
                assertEquals(1,attempts.get())
            }
        } finally { server.stop(0) }
    }
}

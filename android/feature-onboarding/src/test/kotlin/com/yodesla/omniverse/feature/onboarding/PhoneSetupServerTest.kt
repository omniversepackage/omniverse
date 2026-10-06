package com.yodesla.omniverse.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneSetupServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var received: PhoneSetupFields? = null
    private val server = PhoneSetupServer(onSubmit = { received = it }, port = 0)
    private val port = server.start(scope)!!

    @AfterTest fun tearDown() { server.stop(); scope.cancel() }

    private fun post(vararg kv: Pair<String, String>): Pair<Int, String> {
        val body = kv.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL("http://127.0.0.1:$port/submit").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test
    fun servesTheForm() {
        val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertTrue(c.inputStream.bufferedReader().readText().contains("name=\"password\""))
    }

    @Test
    fun wrongCodeIsRejectedAndNothingIsDelivered() {
        val wrong = if (server.code == "0000") "1111" else "0000"
        val (status, _) = post("code" to wrong, "server" to "http://h", "username" to "u", "password" to "p")
        assertEquals(403, status)
        assertNull(received)
    }

    @Test
    fun rightCodeDeliversTheFieldsWithoutEchoingThem() {
        val (status, text) = post("code" to server.code, "server" to " http://h:8080 ", "username" to "u s", "password" to "p&ss=w0rd", "name" to "Home")
        assertEquals(200, status)
        Thread.sleep(50)
        assertEquals(PhoneSetupFields("http://h:8080", "u s", "p&ss=w0rd", "", "Home"), received)
        assertTrue("p&ss=w0rd" !in text, "the response never echoes the password")
    }

    @Test
    fun fiveWrongCodesShutTheServerDown() {
        val wrong = if (server.code == "0000") "1111" else "0000"
        repeat(5) { post("code" to wrong) }
        Thread.sleep(50)
        val ok = runCatching { (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).responseCode }.isSuccess
        assertTrue(!ok, "server is closed after 5 wrong codes")
    }
}

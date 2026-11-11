package com.glaude.iaxphone.iax

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Проверяет клиент против тестового сервера tools/iax_test_server.py.
 * Пропускается, если Python недоступен.
 */
class IaxClientIntegrationTest {

    private var server: Process? = null
    private var port = 0
    private val serverLog = CopyOnWriteArrayList<String>()

    @Before
    fun startServer() {
        val script = listOf(File("../tools/iax_test_server.py"), File("tools/iax_test_server.py")).firstOrNull { it.exists() }
        assumeTrue("нет tools/iax_test_server.py", script != null)
        port = DatagramSocket(0).use { it.localPort }
        val pb = ProcessBuilder("python", "-u", script!!.path, "--host", "127.0.0.1", "--port", "$port",
            "--user", "100:secret", "--user", "200:other").redirectErrorStream(true)
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val p = try { pb.start() } catch (e: Exception) { null }
        assumeTrue("python недоступен", p != null)
        server = p
        val ready = LinkedBlockingQueue<Boolean>()
        Thread {
            p!!.inputStream.bufferedReader(Charsets.UTF_8).forEachLine {
                serverLog += it
                println("[server] $it")
                if ("слушает" in it) ready.offer(true)
            }
        }.apply { isDaemon = true }.start()
        assertTrue("сервер не стартовал", ready.poll(10, TimeUnit.SECONDS) == true)
    }

    @After
    fun stopServer() {
        server?.destroy()
    }

    private class Recorder : IaxListener {
        val reg = LinkedBlockingQueue<RegState>()
        val states = LinkedBlockingQueue<Pair<IaxCall, CallState>>()
        val incoming = LinkedBlockingQueue<IaxCall>()
        val media = LinkedBlockingQueue<IaxCall>()
        @Volatile var voiceFrames = 0
        @Volatile var lastVoice: ByteArray? = null
        override fun onRegistration(state: RegState, detail: String) { reg.offer(state) }
        override fun onCallState(call: IaxCall) { states.offer(call to call.state) }
        override fun onIncomingCall(call: IaxCall) { incoming.offer(call) }
        override fun onMediaStart(call: IaxCall) { media.offer(call) }
        override fun onVoice(call: IaxCall, timestamp: Long, format: Long, payload: ByteArray) { voiceFrames++; lastVoice = payload }
        override fun onLog(line: String) = println("[client] $line")

        fun awaitReg(target: RegState, sec: Long = 10): Boolean {
            val end = System.currentTimeMillis() + sec * 1000
            while (System.currentTimeMillis() < end) {
                val s = reg.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return false
                if (s == target) return true
            }
            return false
        }

        fun awaitState(call: IaxCall, target: CallState, sec: Long = 10): Boolean {
            val end = System.currentTimeMillis() + sec * 1000
            while (System.currentTimeMillis() < end) {
                if (call.state == target) return true
                states.poll(100, TimeUnit.MILLISECONDS)
            }
            return call.state == target
        }
    }

    private fun client(rec: Recorder, user: String = "100", pass: String = "secret") =
        IaxClient(IaxConfig(host = "127.0.0.1", port = port, username = user, password = pass), rec).also { it.start() }

    private fun sendVoiceFor(c: IaxClient, call: IaxCall, ms: Int) {
        val pcm = ShortArray(160) { (8000 * kotlin.math.sin(it * 2 * Math.PI * 300 / 8000)).toInt().toShort() }
        val frame = G711.encode(call.format, pcm)
        repeat(ms / 20) { c.sendVoice(call, frame); Thread.sleep(20) }
    }

    @Test
    fun registerEchoBusyIncomingAndUnregister() {
        val rec = Recorder()
        val c = client(rec)
        assertTrue("регистрация", rec.awaitReg(RegState.REGISTERED))

        // Эхо: голос должен вернуться обратно
        val echo = c.call("600")
        assertTrue("эхо ответило", rec.awaitState(echo, CallState.ACTIVE))
        assertEquals(Codec.ULAW, echo.format)
        sendVoiceFor(c, echo, 600)
        Thread.sleep(300)
        assertTrue("эхо вернуло голос: ${rec.voiceFrames}", rec.voiceFrames >= 20)
        c.sendDtmf(echo, '5')
        c.hangup(echo)
        assertTrue(rec.awaitState(echo, CallState.ENDED))
        Thread.sleep(300)

        // Занято
        val busy = c.call("486")
        assertTrue(rec.awaitState(busy, CallState.ENDED))
        assertEquals("Занято", busy.endReason)

        // Несуществующий номер
        val bad = c.call("999")
        assertTrue(rec.awaitState(bad, CallState.ENDED))
        assertTrue(bad.endReason!!.contains("No such extension"))

        // Входящий: 800 перезванивает
        val cb = c.call("800")
        assertTrue(rec.awaitState(cb, CallState.ENDED))
        val inc = rec.incoming.poll(10, TimeUnit.SECONDS)
        assertTrue("пришёл входящий", inc != null)
        assertEquals("800", inc!!.number)
        assertEquals("Test server", inc.remoteName)
        val before = rec.voiceFrames
        c.answer(inc)
        assertTrue(rec.awaitState(inc, CallState.ACTIVE))
        sendVoiceFor(c, inc, 400)
        Thread.sleep(300)
        assertTrue("эхо на входящем", rec.voiceFrames - before >= 10)
        c.hangup(inc)
        assertTrue(rec.awaitState(inc, CallState.ENDED))
        Thread.sleep(500)

        c.stop()
        Thread.sleep(1500)
        assertTrue("сервер снял регистрацию", serverLog.any { "снял регистрацию" in it })
        assertTrue("сервер получил DTMF", serverLog.any { "DTMF '5'" in it })
    }

    @Test
    fun wrongPasswordFails() {
        val rec = Recorder()
        val c = client(rec, pass = "wrong")
        assertTrue(rec.awaitReg(RegState.FAILED))
        c.stop()
    }

    @Test
    fun tonePlaysAfterAnswer() {
        val rec = Recorder()
        val c = client(rec)
        assertTrue(rec.awaitReg(RegState.REGISTERED))
        val call = c.call("700")
        assertTrue(rec.awaitState(call, CallState.ACTIVE))
        Thread.sleep(1000)
        assertTrue("тон: ${rec.voiceFrames}", rec.voiceFrames >= 30)
        val pcm = G711.decode(call.format, rec.lastVoice!!)
        assertTrue("тон не тишина", pcm.any { kotlin.math.abs(it.toInt()) > 4000 })
        c.hangup(call)
        assertTrue(rec.awaitState(call, CallState.ENDED))
        c.stop()
    }
}

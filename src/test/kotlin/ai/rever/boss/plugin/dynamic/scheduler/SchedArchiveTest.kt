package ai.rever.boss.plugin.dynamic.scheduler

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchedChecksumsTest {

    private val a = "a".repeat(64)
    private val b = "B".repeat(64)

    @Test
    fun `parses sha256sum output`() {
        val sums = SchedChecksums.parse(
            """
            $a  boss-sched-0.1.0-aarch64-apple-darwin.tar.gz
            $b *boss-sched-0.1.0-x86_64-unknown-linux-gnu.tar.gz
            # comment
            not a line
            ${"c".repeat(63)}  too-short.tar.gz

            ${"d".repeat(64)}  ./with-dot.tar.gz
            """.trimIndent(),
        )
        assertEquals(a, sums["boss-sched-0.1.0-aarch64-apple-darwin.tar.gz"])
        assertEquals(b.lowercase(), sums["boss-sched-0.1.0-x86_64-unknown-linux-gnu.tar.gz"])
        assertEquals("d".repeat(64), sums["with-dot.tar.gz"])
        assertEquals(3, sums.size)
    }

    @Test
    fun `hashes like shasum`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SchedChecksums.sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }
}

class SchedTarTest {

    @Test
    fun `extracts the named entry past pax headers, AppleDouble and directories`() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        val tar = TarFixture.build(
            listOf(
                TarFixture.Entry("PaxHeader/boss-sched", "30 mtime=1700000000.123456789\n".toByteArray(), 'x'),
                TarFixture.Entry("./", ByteArray(0), '5'),
                TarFixture.Entry("./._boss-sched", ByteArray(82) { 1 }),
                TarFixture.Entry("./boss-sched", payload),
            ),
        )
        val out = ByteArrayOutputStream()
        assertEquals(1500L, SchedTar.extract(ByteArrayInputStream(tar), "boss-sched", out))
        assertContentEquals(payload, out.toByteArray())
    }

    @Test
    fun `missing entry and truncation are errors`() {
        val tar = TarFixture.build(listOf(TarFixture.Entry("other", ByteArray(10))))
        assertFailsWith<SchedTar.ArchiveException> {
            SchedTar.extract(ByteArrayInputStream(tar), "boss-sched", ByteArrayOutputStream())
        }
        val full = TarFixture.build(listOf(TarFixture.Entry("boss-sched", ByteArray(4000))))
        assertFailsWith<SchedTar.ArchiveException> {
            SchedTar.extract(ByteArrayInputStream(full.copyOf(1200)), "boss-sched", ByteArrayOutputStream())
        }
    }

    @Test
    fun `reads an archive made by the system tar like the release workflow`() {
        val tarBin = listOf("/usr/bin/tar", "/bin/tar").map(::File).firstOrNull { it.canExecute() } ?: return
        val dir = Files.createTempDirectory("sched-tar").toFile()
        try {
            val bin = File(dir, "boss-sched").apply { writeText("#!/bin/sh\necho hi\n") }
            bin.setExecutable(true)
            val archive = File(dir, "a.tar.gz")
            val p = ProcessBuilder(tarBin.path, "-C", dir.path, "-czf", archive.path, "boss-sched").start()
            assertEquals(0, p.waitFor())
            val out = ByteArrayOutputStream()
            archive.inputStream().use { SchedTar.extractGz(it, "boss-sched", out) }
            assertTrue(out.toString(Charsets.UTF_8).contains("echo hi"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Minimal ustar writer for fixtures. */
object TarFixture {
    data class Entry(val name: String, val data: ByteArray, val type: Char = '0', val mode: Int = 0b111_101_101)

    fun build(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            put(0, e.name)
            put(100, "%07o".format(e.mode) + "\u0000")
            put(108, "0000000\u0000")
            put(116, "0000000\u0000")
            put(124, "%011o".format(e.data.size) + "\u0000")
            put(136, "%011o".format(1_700_000_000L) + "\u0000")
            put(148, "        ")
            h[156] = e.type.code.toByte()
            put(257, "ustar\u0000")
            put(263, "00")
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, "%06o".format(sum) + "\u0000 ")
            out.write(h)
            out.write(e.data)
            val pad = (512 - e.data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}

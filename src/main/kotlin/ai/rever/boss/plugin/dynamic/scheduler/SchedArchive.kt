package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** `SHA256SUMS` parsing and hashing. */
object SchedChecksums {

    /**
     * Parse `sha256sum` output: `<64 hex>  <file name>` per line. Also accepts
     * the binary-mode marker (`<hex> *<name>`) and a leading `./`. Hashes are
     * lowercased. Lines that are not a 64-hex hash followed by a name are
     * skipped rather than failing the whole file.
     */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val m = LINE.matchEntire(line) ?: continue
            val name = m.groupValues[2].trim().removePrefix("./")
            if (name.isNotEmpty()) out[name] = m.groupValues[1].lowercase()
        }
        return out
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")
}

/**
 * Just enough tar to pull one regular file out of a release archive, so the
 * install does not depend on a `tar` binary being on the host's PATH.
 *
 * Handles ustar names with a prefix, skips pax (`x`/`g`) and GNU long-name
 * (`L`/`K`) headers, directories, and macOS AppleDouble `._` entries that
 * bsdtar can add.
 */
object SchedTar {

    class ArchiveException(message: String) : Exception(message)

    /**
     * Copy the regular file whose base name is [wanted] from the gzipped tar
     * [input] into [out]. Returns the number of bytes written. Throws
     * [ArchiveException] when the archive has no such entry or is malformed.
     */
    fun extractGz(input: InputStream, wanted: String, out: OutputStream, maxBytes: Long = MAX_ENTRY_BYTES): Long =
        GZIPInputStream(input).use { extract(it, wanted, out, maxBytes) }

    fun extract(input: InputStream, wanted: String, out: OutputStream, maxBytes: Long = MAX_ENTRY_BYTES): Long {
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(input, header)) throw ArchiveException("archive ended before $wanted was found")
            if (header.all { it == 0.toByte() }) throw ArchiveException("$wanted is not in the archive")

            val name = fullName(header)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK

            val base = name.trimEnd('/').substringAfterLast('/')
            val regular = type == '0' || type == '\u0000' || type == '7'
            if (regular && base == wanted) {
                if (size > maxBytes) throw ArchiveException("$wanted is $size bytes, over the $maxBytes limit")
                copyExactly(input, out, size)
                return size
            }
            skipExactly(input, padded)
        }
    }

    private fun fullName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    internal fun octal(h: ByteArray, off: Int, len: Int): Long {
        // Base-256 (GNU) for sizes over 8 GiB: high bit of the first byte set.
        if (h[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        val s = cString(h, off, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: throw ArchiveException("bad tar size field '$s'")
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw ArchiveException("truncated tar header")
            }
            read += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw ArchiveException("truncated tar entry")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipExactly(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(BLOCK * 16)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw ArchiveException("truncated tar archive")
            left -= n
        }
    }

    private const val BLOCK = 512
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
}

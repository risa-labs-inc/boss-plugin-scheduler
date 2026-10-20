package ai.rever.boss.plugin.dynamic.scheduler

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake BossScheduler release served over real HTTP from 127.0.0.1, laid out
 * exactly like the GitHub contract: `/v<ver>/boss-sched-<ver>-<target>.tar.gz`
 * and `/v<ver>/SHA256SUMS`. The `boss-sched` inside is a shell script
 * ([fakeBossSched]) so the downloaded binary can actually be run.
 */
class ReleaseFixture(
    val version: String,
    val target: String,
    binary: ByteArray,
    /** Override the archive bytes served (e.g. to break the checksum). */
    tamper: ((ByteArray) -> ByteArray)? = null,
    sumsOverride: String? = null,
) : AutoCloseable {

    val archiveName = SchedRelease.archiveName(version, target)
    private val archive = TarFixture.gzip(TarFixture.build(listOf(TarFixture.Entry("boss-sched", binary))))
    private val sums = sumsOverride
        ?: "${"0".repeat(64)}  boss-sched-$version-other-target.tar.gz\n" +
        "${SchedChecksums.sha256(archive.inputStream())}  $archiveName\n"
    private val served = tamper?.invoke(archive) ?: archive

    val requests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests.incrementAndGet()
            val body = when (ex.requestURI.path) {
                "/v$version/SHA256SUMS" -> sums.toByteArray()
                "/v$version/$archiveName" -> served
                else -> null
            }
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        start()
    }

    val baseUrl = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    companion object {
        /**
         * A stand-in `boss-sched`: answers `version`, `status` (running only
         * when `<state>/running` exists, otherwise exit 3 like the real one),
         * `list`, `history`, and records `install-agent` and its PATH.
         */
        fun fakeBossSched(version: String, state: File): ByteArray = """
            #!/bin/sh
            S='${state.absolutePath}'
            echo "${'$'}*" >> "${'$'}S/calls"
            for a in "${'$'}@"; do
              case "${'$'}a" in
                version) echo '{"version":"$version"}'; exit 0 ;;
                status)
                  if [ -f "${'$'}S/running" ]; then
                    echo '{"pid":4242,"version":"$version","home":"/tmp/x","started_at":"2026-10-20T07:11:00Z","jobs":0,"running":0}'
                    exit 0
                  fi
                  echo "not running (daemon not reachable)"; exit 3 ;;
                list) echo '[]'; exit 0 ;;
                history) echo '[]'; exit 0 ;;
                install-agent) printf '%s' "${'$'}PATH" > "${'$'}S/agent-path"; echo "wrote agent"; exit 0 ;;
              esac
            done
            echo "boss-sched: unknown" >&2
            exit 1
        """.trimIndent().plus("\n").toByteArray()
    }
}

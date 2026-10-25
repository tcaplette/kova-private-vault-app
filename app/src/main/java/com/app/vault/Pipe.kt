package com.app.vault

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Joins a producer that writes to a stream with a consumer that reads one — P4-3.
 *
 * **It exists so that no plaintext file has to.** `ShareContainer.openWithCode`
 * produces to an `OutputStream` and `BlobStore.write` consumes an `InputStream`,
 * and the obvious way to join them is a temporary file — which is exactly the
 * plaintext on disk I3 forbids. A pipe joins them with a buffer instead.
 *
 * ponytail: one thread and `PipedInputStream`, not a coroutine channel or an
 * `okio` dependency. The producer thread exists for the length of one share and
 * its failure is rethrown on the caller's thread, so a failure here is
 * indistinguishable from a failure in a direct call — which is what the callers'
 * one-failure rule needs.
 */
internal object Pipe {

    /**
     * Runs [produce] on its own thread writing into a pipe, and [consume] on the
     * caller's thread reading from it. Returns what [consume] returned.
     *
     * Both ends are closed on every path. A failure in [produce] surfaces here,
     * after [consume] has been unblocked by the pipe closing — otherwise a
     * producer that threw on its first chunk would leave the reader waiting for
     * bytes that are never coming.
     */
    fun <T> between(
        produce: (OutputStream) -> Unit,
        consume: (InputStream) -> T,
    ): T {
        val source = PipedInputStream(BUFFER)
        val sink = PipedOutputStream(source)

        var thrown: Throwable? = null
        val producer = thread(name = "vault-pipe") {
            try {
                sink.use { produce(it) }
            } catch (e: Throwable) {
                thrown = e
                // Closing is what releases the reader; without it a producer that
                // failed early deadlocks the consumer against an open pipe.
                runCatching { sink.close() }
            }
        }

        val result = try {
            source.use { consume(it) }
        } catch (e: Throwable) {
            producer.join()
            throw thrown ?: e
        }

        producer.join()
        thrown?.let { throw it }
        return result
    }

    /** One blob chunk. Large enough that the two ends are not trading a byte at a time. */
    private const val BUFFER = BlobStore.CHUNK
}

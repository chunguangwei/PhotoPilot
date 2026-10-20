package dev.photopilot.scaffold.data.imports

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class NasNetworkBlockedException : IOException("NET_METERED_NOT_ALLOWED")

object NasNetworkPolicy {
    fun allows(connected: Boolean, unmetered: Boolean, wifiOnly: Boolean): Boolean =
        connected && (!wifiOnly || unmetered)

    fun guardedStream(input: InputStream, allowed: () -> Boolean): InputStream =
        object : FilterInputStream(input) {
            private fun check() {
                if (!allowed()) throw NasNetworkBlockedException()
            }
            override fun read(): Int {
                check()
                return try { `in`.read().also { check() } } catch (failure: IOException) {
                    check()
                    throw failure
                }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check()
                return try { `in`.read(buffer, offset, length).also { check() } } catch (failure: IOException) {
                    check()
                    throw failure
                }
            }
            override fun skip(count: Long): Long {
                check()
                return `in`.skip(count).also { check() }
            }
        }
}

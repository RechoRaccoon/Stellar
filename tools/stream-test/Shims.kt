// Stand-ins so the plain-Kotlin RTMP code (shared/src/iosMain/…/stream)
// compiles and runs on a desktop JVM with just kotlinc.
@file:Suppress("unused")

package kotlinx.atomicfu.locks

typealias ReentrantLock = java.util.concurrent.locks.ReentrantLock

fun reentrantLock(): ReentrantLock = ReentrantLock()

inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try { return block() } finally { unlock() }
}

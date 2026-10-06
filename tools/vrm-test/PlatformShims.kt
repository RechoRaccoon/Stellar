package com.mediaviewer.platform

object Log {
    fun e(tag: String?, msg: String, tr: Throwable? = null): Int { println("E/$tag: $msg ${tr ?: ""}"); return 0 }
    fun w(tag: String?, msg: String): Int { println("W/$tag: $msg"); return 0 }
    fun d(tag: String?, msg: String): Int = 0
}

fun nanoTime(): Long = System.nanoTime()
fun currentTimeMillis(): Long = System.currentTimeMillis()

package com.echomute

import java.util.logging.Level
import java.util.logging.Logger

/** Thin logging wrapper that works in both Android and local JVM unit tests. */
internal object AppLog {
    private val logger = Logger.getLogger("Unsplice")

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) logger.log(Level.WARNING, "$tag: $message", error)
        else logger.warning("$tag: $message")
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) logger.log(Level.SEVERE, "$tag: $message", error)
        else logger.severe("$tag: $message")
    }
}

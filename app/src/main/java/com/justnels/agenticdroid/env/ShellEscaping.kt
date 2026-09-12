package com.justnels.agenticdroid.env

/** POSIX shell quoting so argument values can never be interpreted as shell syntax. */
object ShellEscaping {
    /** Wraps [value] in single quotes, escaping any embedded single quotes. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

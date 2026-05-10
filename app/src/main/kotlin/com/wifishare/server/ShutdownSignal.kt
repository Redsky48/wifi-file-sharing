package com.wifishare.server

/**
 * Cooperative flag — set briefly while the server is in its shutdown
 * grace window so any in-flight client requests get a 503 with a clear
 * "shutting down" hint. Companion apps treat that as a signal to give
 * up immediately instead of waiting for their poll timeout.
 */
object ShutdownSignal {
    @Volatile
    var armed: Boolean = false
}

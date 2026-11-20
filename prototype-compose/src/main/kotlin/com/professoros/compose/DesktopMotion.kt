package com.professoros.compose

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Read the Windows accessibility preference on activation, outside animation frames. */
internal fun systemReducesMotion(): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows")) return false
    return try {
        Arena.ofConfined().use { arena ->
            val dll = Path.of(System.getenv("SystemRoot"), "System32", "user32.dll")
            val symbol = SymbolLookup.libraryLookup(dll, arena).find("SystemParametersInfoW").orElseThrow()
            val signature = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
            val query = Linker.nativeLinker().downcallHandle(symbol, signature)
            val enabled = arena.allocate(JAVA_INT)
            // SPI_GETCLIENTAREAANIMATION returns the user's setting in a Win32 BOOL.
            check(query.invoke(0x1042, 0, enabled, 0) as Int != 0)
            enabled.get(JAVA_INT, 0) == 0
        }
    } catch (error: RuntimeException) {
        Logger.getLogger("DesktopMotion").log(Level.WARNING, "Cannot read system animation preference", error)
        true
    }
}

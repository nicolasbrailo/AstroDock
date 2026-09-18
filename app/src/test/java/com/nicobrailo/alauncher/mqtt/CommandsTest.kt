package com.nicobrailo.alauncher.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    private val prefix = "portalgo/"

    @Test
    fun recognisesTheCommandsWeSupport() {
        assertEquals(CommandKind.NEXT, Commands.kind("${prefix}cmd/ambience/next", prefix))
        assertEquals(CommandKind.PREVIOUS, Commands.kind("${prefix}cmd/ambience/prev", prefix))
        assertEquals(CommandKind.FORCE_ON, Commands.kind("${prefix}cmd/presence/force_on", prefix))
        assertEquals(CommandKind.FORCE_OFF, Commands.kind("${prefix}cmd/presence/force_off", prefix))
        assertEquals(
            CommandKind.TRANSITION_SECONDS,
            Commands.kind("${prefix}cmd/ambience/set_transition_time_secs", prefix)
        )
        assertEquals(CommandKind.ANNOUNCE, Commands.kind("${prefix}cmd/ambience/announce", prefix))
    }

    @Test
    fun ignoresTheRest() {
        // The homeboard's own renderer commands
        assertNull(Commands.kind("${prefix}cmd/ambience/set_svg_overlay", prefix))
        assertNull(Commands.kind("${prefix}cmd/photo_provider/set_target_size", prefix))
        assertNull(Commands.kind("${prefix}cmd/nonsense", prefix))
        // Another device's topics, which we shouldn't act on
        assertNull(Commands.kind("homeboard_patio/cmd/ambience/next", prefix))
        // Our own state topics
        assertNull(Commands.kind("${prefix}state/occupancy", prefix))
    }
}

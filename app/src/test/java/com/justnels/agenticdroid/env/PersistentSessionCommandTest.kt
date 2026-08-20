package com.justnels.agenticdroid.env

import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSessionCommandTest {
    @Test
    fun triesTmuxThenScreenThenFallsBackToAVisibleLoginShell() {
        val command = SSHExecutionEnvironment.persistentSessionCommand("/home/user/project")

        assertTrue(command.contains("tmux new-session -A -s agenticdroid"))
        assertTrue(command.contains("screen -xR agenticdroid"))
        assertTrue(command.contains("exec \"\$SHELL\" -l"))
        // The fallback is not silent - a user reattaching should be able to tell from the
        // terminal transcript itself that persistence didn't actually engage.
        assertTrue(command.contains("persistent session unavailable"))
        // Order matters: tmux is tried first, screen second, and the plain shell is only
        // reached if neither tool exists on the remote.
        val tmuxIndex = command.indexOf("tmux")
        val screenIndex = command.indexOf("screen")
        val shellIndex = command.indexOf("\$SHELL")
        assertTrue(tmuxIndex in 0 until screenIndex)
        assertTrue(screenIndex in 0 until shellIndex)
    }

    @Test
    fun usesTheSameSessionNameForBothTools() {
        val command = SSHExecutionEnvironment.persistentSessionCommand("/home/user/project")
        val sessionNames = Regex("-s (\\S+)|-xR (\\S+)").findAll(command)
            .map { it.groupValues.drop(1).first { name -> name.isNotEmpty() } }
            .toList()

        assertTrue(sessionNames.size == 2 && sessionNames.toSet().size == 1)
    }

    @Test
    fun passesWorkingDirectoryToTmuxCreationOnlyNotAsASeparateCdCommand() {
        val command = SSHExecutionEnvironment.persistentSessionCommand("/home/user/project")

        // -c belongs to the tmux invocation (a no-op on an actual reattach thanks to -A),
        // not sent as a trailing `cd` - that would type literally into whatever's already
        // running in a session being reattached to (e.g. an agent CLI's TUI).
        assertTrue(command.contains("tmux new-session -A -s agenticdroid -c \"/home/user/project\""))
        assertTrue(!command.contains("&& cd"))
        assertTrue(!command.trim().startsWith("cd "))
    }

    @Test
    fun omitsDirArgumentForTheDefaultWorkingDirectory() {
        val command = SSHExecutionEnvironment.persistentSessionCommand(".")
        assertTrue(command.contains("tmux new-session -A -s agenticdroid 2>/dev/null"))
    }
}

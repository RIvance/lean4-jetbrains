package plugin.lean

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.UiInterceptors
import com.intellij.ui.awt.RelativePoint
import java.nio.file.Files
import java.nio.file.Path

/** A host with a project pin, a linked toolchain, and an installable version. */
internal fun createTestElan(directory: Path, defaultName: String = "release"): Path {
    val pin = Files.writeString(directory.resolve("lean-toolchain"), defaultName)
    val names = listOf("release", "linked", "custom-channel")
    for ((inventory, installed) in mapOf("before" to names.take(2), "after" to names)) {
        for (selection in names + "automatic") {
            val name = if (selection == "automatic") defaultName else selection
            val reason = if (selection == "automatic") mapOf("ToolchainFile" to pin.toString()) else "Environment"
            val toolchains = mapOf(
                "installed" to installed.map { mapOf("resolved_name" to it, "path" to directory.toString()) },
                "resolved_active" to mapOf("live" to mapOf("Ok" to name)),
                "active_override" to mapOf("reason" to reason)
            )
            val state = mapOf("elan_version" to mapOf("current" to "4.2.1"), "toolchains" to toolchains)
            Files.writeString(directory.resolve("$inventory-$selection.json"), Gson().toJson(state))
        }
    }
    val executable = if (SystemInfo.isWindows) {
        Files.writeString(directory.resolve("elan.cmd"), """
            @echo off
            if "%1 %2 %3"=="toolchain install --" goto install
            if not "%1"=="dump-state" (echo %* & exit /b)
            set "selection=automatic"
            for %%T in (release linked custom-channel) do if "%ELAN_TOOLCHAIN%"=="%%T" set "selection=%%T"
            set "inventory=before"
            if exist "%~dp0installed" set "inventory=after"
            type "%~dp0%inventory%-%selection%.json"
            exit /b
            :install
            >"%~dp0install-started" echo %4
            :wait
            if exist "%~dp0pause-install" (ping -n 2 127.0.0.1 >nul & goto wait)
            if exist "%~dp0refuse-install" (echo Download failed & exit /b 1)
            >"%~dp0installed" echo %4
        """.trimIndent().replace("\n", "\r\n"))
    } else {
        Files.writeString(directory.resolve("elan"), """
            #!/bin/sh
            dir="${'$'}{0%/*}"
            if [ "$1 $2 $3" = 'toolchain install --' ]; then
                echo "$4" > "${'$'}dir/install-started"
                while [ -f "${'$'}dir/pause-install" ]; do sleep 1; done
                if [ -f "${'$'}dir/refuse-install" ]; then echo 'Download failed' >&2; exit 1; fi
                echo "$4" > "${'$'}dir/installed"
            elif [ "$1" = dump-state ]; then
                inventory=before
                if [ -f "${'$'}dir/installed" ]; then inventory=after; fi
                case "${'$'}ELAN_TOOLCHAIN" in
                    release|linked|custom-channel) selection="${'$'}ELAN_TOOLCHAIN" ;;
                    *) selection=automatic ;;
                esac
                cat "${'$'}dir/${'$'}inventory-${'$'}selection.json"
            else
                echo "$*"
            fi
        """.trimIndent())
    }
    check(SystemInfo.isWindows || executable.toFile().setExecutable(true))
    return executable
}

internal fun interceptToolchainInstaller(parent: Disposable, respond: (DialogWrapper) -> Unit) {
    UiInterceptors.registerPersistent(parent,
        object : UiInterceptors.PersistentUiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
            override fun shouldIntercept(component: DialogWrapper) = component.title == "Install Lean Toolchain"
            override fun doIntercept(component: DialogWrapper, owner: RelativePoint?) = respond(component)
        }
    )
}

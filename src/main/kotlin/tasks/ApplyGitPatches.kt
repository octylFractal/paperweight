/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.Command
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.file
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.property

open class ApplyGitPatches : DefaultTask() {

    @Input
    val branch: Property<String> = project.objects.property()
    @Input
    val upstreamBranch: Property<String> = project.objects.property()
    @InputDirectory
    val upstream: DirectoryProperty = project.objects.directoryProperty()
    @InputDirectory
    val patchDir: DirectoryProperty = project.objects.directoryProperty()

    @OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()
    @Console
    val printOutput: Property<Boolean> = project.objects.property<Boolean>().convention(false)

    @TaskAction
    fun run() {
        Git(upstream.file).let { git ->
            git("fetch").setupOut().run()
            git("branch", "-f", upstreamBranch.get(), branch.get()).runSilently()
        }

        if (!outputDir.file.exists() || !outputDir.file.resolve(".git").exists()) {
            outputDir.file.deleteRecursively()
            Git(outputDir.file.parentFile)("clone", upstream.file.absolutePath, outputDir.file.name).setupOut().run()
        }

        val target = outputDir.file.name

        if (printOutput.get()) {
            println("   Resetting $target to ${upstream.file.name}...")
        }
        Git(outputDir.file).let { git ->
            git("remote", "rm", "upstream").runSilently(silenceErr = true)
            git("remote", "add", "upstream", upstream.file.absolutePath).runSilently(silenceErr = true)
            if (git("checkout", "master").setupOut(showError = false).run() != 0) {
                git("checkout", "-b", "master").setupOut(mergeOutput = true).run()
            }
            git("fetch", "upstream").runSilently(silenceErr = true)
            git("reset", "--hard", "upstream/${upstreamBranch.get()}").setupOut().run()

            if (printOutput.get()) {
                println("   Applying patches to $target...")
            }

            val statusFile = outputDir.file.resolve(".git/patch-apply-failed")
            if (statusFile.exists()) {
                statusFile.delete()
            }
            git("am", "--abort").runSilently(silenceErr = true)

            val patches = patchDir.file.listFiles { _, name -> name.endsWith(".patch") } ?: run {
                if (printOutput.get()) {
                    println("No patches found")
                }
                return
            }

            patches.sort()

            if (git("am", "--3way", "--ignore-whitespace", *patches.map { it.absolutePath }.toTypedArray()).showErrors().run() != 0) {
                Thread.sleep(100) // Wait for git
                statusFile.writeText("1")
                logger.error("***   Something did not apply cleanly to $target.")
                logger.error("***   Please review above details and finish the apply then")
                logger.error("***   save the changes with ./gradlew `rebuildPatches`")

                if (OperatingSystem.current().isWindows) {
                    logger.error("")
                    logger.error("***   Because you're on Windows you'll need to finish the AM,")
                    logger.error("***   rebuild all patches, and then re-run the patch apply again.")
                    logger.error("***   Consider using the scripts with Windows Subsystem for Linux.")
                }

                throw PaperweightException("Failed to apply patches")
            } else {
                statusFile.delete()
                if (printOutput.get()) {
                    println("   Patches applied cleanly to $target")
                }
            }
        }
    }

    private fun Command.setupOut(mergeOutput: Boolean = false, showError: Boolean = true) = apply {
        if (printOutput.get()) {
            val err = if (showError) {
                if (mergeOutput) {
                    System.out
                } else {
                    System.err
                }
            } else {
                null
            }
            setup(System.out, err)
        } else {
            setup(null, null)
        }
    }

    private fun Command.showErrors() = apply {
        if (printOutput.get()) {
            setup(System.out, System.err)
        } else {
            setup(null, System.err)
        }
    }
}

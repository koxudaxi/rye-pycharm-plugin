// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.koxudaxi.rye



import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.packaging.*
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.associatedModuleDir


/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyRyePackageManager(sdk: Sdk) : PyPackageManager(sdk) {
    private val installedLines = listOf("Already installed", "Skipping", "Updating")

    @Volatile
    private var packages: List<PyPackage>? = null

    private var requirements: List<PyRequirement>? = null

    private var outdatedPackages: Map<String, RyeOutdatedVersion> = emptyMap()

    override fun installManagement() {}

    override fun hasManagement() = true

    override fun install(requirementString: String) {
        install(parseRequirements(requirementString), emptyList())
    }

    override fun install(requirements: List<PyRequirement>?, extraArgs: List<String>) {
        val args = if (requirements.isNullOrEmpty()) {
            listOfNotNull(listOf("install"),
                extraArgs)
                .flatten()
        }
        else {
            listOfNotNull(listOf("add"),
                requirements.map { it.name },
                extraArgs)
                .flatten()
        }

        try {
            runRye(sdk, *args.toTypedArray())
        }
        finally {
            sdk.associatedModuleDir?.refresh(true, false)
            refreshAndGetPackages(true)
        }
    }

    override fun uninstall(packages: List<PyPackage>) {
        val args = listOf("remove") +
                packages.map { it.name }
        try {
            runRye(sdk, *args.toTypedArray())
        }
        finally {
            sdk.associatedModuleDir?.refresh(true, false)
            refreshAndGetPackages(true)
        }
    }

    override fun refresh() {
        with(ApplicationManager.getApplication()) {
            invokeLater {
                runWriteAction {
                    val files = sdk.rootProvider.getFiles(OrderRootType.CLASSES)
                    VfsUtil.markDirtyAndRefresh(true, true, true, *files)
                }
                PythonSdkType.getInstance().setupSdkPaths(sdk)
            }
        }
    }

    override fun createVirtualEnv(destinationDir: String, useGlobalSite: Boolean): String {
        throw ExecutionException(
            "Creating virtual environments based on Rye environments is not supported")
    }

    override fun getPackages() = packages

    fun getOutdatedPackages() = outdatedPackages

    override fun refreshAndGetPackages(alwaysRefresh: Boolean): List<PyPackage> {
        if (alwaysRefresh || packages == null) {
            packages = null
            val outputInstallDryRun = try {
                runRye(sdk, "install", "--dry-run", "--no-root")
            }
            catch (e: ExecutionException) {
                packages = emptyList()
                return packages ?: emptyList()
            }
            val allPackage = parseRyeInstallDryRun(outputInstallDryRun)
            packages = allPackage.first
            requirements = allPackage.second

            val outputOutdatedPackages = try {
                runRye(sdk, "show", "--outdated")
            }
            catch (e: ExecutionException) {
                outdatedPackages = emptyMap()
            }
            if (outputOutdatedPackages is String) {
                outdatedPackages = parseRyeShowOutdated(outputOutdatedPackages)
            }
            ApplicationManager.getApplication().messageBus.syncPublisher(PACKAGE_MANAGER_TOPIC).packagesRefreshed(sdk)
        }
        return packages ?: emptyList()
    }

    override fun getRequirements(module: Module): List<PyRequirement>? {
        return requirements
    }

    override fun parseRequirements(text: String): List<PyRequirement> =
        PyRequirementParser.fromText(text)

    override fun parseRequirement(line: String): PyRequirement? =
        PyRequirementParser.fromLine(line)

    override fun parseRequirements(file: VirtualFile): List<PyRequirement> =
        PyRequirementParser.fromFile(file)

    override fun getDependents(pkg: PyPackage): Set<PyPackage> {
        // TODO: Parse the dependency information from `pipenv graph`
        return emptySet()
    }

    private fun getVersion(version: String): String {
        return if (Regex("^[0-9]").containsMatchIn(version)) "==$version" else version
    }

    /**
     * Parses the output of `rye install --dry-run ` into a list of packages.
     */
    private fun parseRyeInstallDryRun(input: String): Pair<List<PyPackage>, List<PyRequirement>> {
        fun getNameAndVersion(line: String): Triple<String, String, String> {
            return line.split(" ").let {
                val installedVersion = it[5].replace(Regex("[():]"), "")
                val requiredVersion = when {
                    it.size > 7 && it[6] == "->" -> it[7].replace(Regex("[():]"), "")
                    else -> installedVersion
                }
                Triple(it[4], installedVersion, requiredVersion)
            }
        }

        val pyPackages = mutableListOf<PyPackage>()
        val pyRequirements = mutableListOf<PyRequirement>()
        input
            .lineSequence()
            .filter { listOf(")", "Already installed").any { lastWords -> it.endsWith(lastWords) } }
            .forEach { line ->
                getNameAndVersion(line).also {
                    if (installedLines.any { installedLine -> line.contains(installedLine) }) {
                        pyPackages.add(PyPackage(it.first, it.second, null, emptyList()))
                        this.parseRequirement(it.first + getVersion(it.third))?.let { pyRequirement -> pyRequirements.add(pyRequirement) }
                    }
                    else if (line.contains("Installing")) {
                        this.parseRequirement(it.first + getVersion(it.third))?.let { pyRequirement -> pyRequirements.add(pyRequirement) }
                    }
                }
            }
        return Pair(pyPackages.distinct().toList(), pyRequirements.distinct().toList())
    }
}
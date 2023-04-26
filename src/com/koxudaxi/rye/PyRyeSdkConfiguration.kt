// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.koxudaxi.rye

import com.intellij.codeInspection.util.IntentionName
import com.intellij.execution.ExecutionException
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jetbrains.python.sdk.*
import com.jetbrains.python.sdk.configuration.PyProjectSdkConfigurationExtension
import java.awt.BorderLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

/**
 *  This source code is created by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyRyeSdkConfiguration : PyProjectSdkConfigurationExtension {

    private val LOGGER = Logger.getInstance(PyRyeSdkConfiguration::class.java)

    override fun createAndAddSdkForConfigurator(module: Module): Sdk? = createAndAddSDk(module, false)

    override fun getIntention(module: Module): @IntentionName String? =
        module.pyProjectToml?.let { "Create a rye environment using ${it.name}" }

    override fun createAndAddSdkForInspection(module: Module): Sdk? = createAndAddSDk(module, true)

    private fun createAndAddSDk(module: Module, inspection: Boolean): Sdk? {
        val ryeEnvExecutable = askForEnvData(module, inspection) ?: return null
        PropertiesComponent.getInstance().ryePath = ryeEnvExecutable.ryePath
        return createRye(module)
    }

    private fun askForEnvData(module: Module, inspection: Boolean): PyAddNewRyeFromFilePanel.Data? {
        val ryeExecutable = getRyeExecutable()?.absolutePath

        if (inspection && validateRyeExecutable(ryeExecutable) == null) {
            return PyAddNewRyeFromFilePanel.Data(ryeExecutable!!)
        }

        var permitted = false
        var envData: PyAddNewRyeFromFilePanel.Data? = null

        ApplicationManager.getApplication().invokeAndWait {
            val dialog = Dialog(module)

            permitted = dialog.showAndGet()
            envData = dialog.envData

            LOGGER.debug("Dialog exit code: ${dialog.exitCode}, $permitted")
        }

        return if (permitted) envData else null
    }

    private fun createRye(module: Module): Sdk? {
        ProgressManager.progress("Setting up rye environment")
        LOGGER.debug("Creating rye environment")

        val basePath = module.basePath ?: return null
        val rye = try {
            val init = StandardFileSystems.local().findFileByPath(basePath)?.findChild(PY_PROJECT_TOML)?.let {
                getPyProjectTomlForRye(it)
            } == null
            setupRye(FileUtil.toSystemDependentName(basePath), null, true, init)
        }
        catch (e: ExecutionException) {
            LOGGER.warn("Exception during creating rye environment", e)
            showSdkExecutionException(null, e,
                "Failed To Create Rye Environment")
            return null
        }

        val path = PythonSdkUtil.getPythonExecutable(basePath).also {
            if (it == null) {
                LOGGER.warn("Python executable is not found: $rye")
            }
        } ?: return null

        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path).also {
            if (it == null) {
                LOGGER.warn("Python executable file is not found: $path")
            }
        } ?: return null

        LOGGER.debug("Setting up associated rye environment: $path, $basePath")
        val sdk = SdkConfigurationUtil.setupSdk(
            ProjectJdkTable.getInstance().allJdks,
            file,
            PythonSdkType.getInstance(),
            false,
            null,
            suggestedSdkName(basePath)
        ) ?: return null

        ApplicationManager.getApplication().invokeAndWait {
            LOGGER.debug("Adding associated rye environment: $path, $basePath")
            SdkConfigurationUtil.addSdk(sdk)
            sdk.isRye = true
            sdk.associateWithModule(module, null)
        }

        return sdk
    }

    private class Dialog(module: Module) : DialogWrapper(module.project, false, IdeModalityType.PROJECT) {

        private val panel = PyAddNewRyeFromFilePanel(module)

        val envData
            get() = panel.envData

        init {
            title = "Setting Up Rye Environment"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply {
                val border = IdeBorderFactory.createEmptyBorder(Insets(4, 0, 6, 0))
                val message = "File pyproject.toml contains project dependencies. Would you like to set up a rye environment?"

                add(
                    JBUI.Panels.simplePanel(JBLabel(message)).withBorder(border),
                    BorderLayout.NORTH
                )

                add(panel, BorderLayout.CENTER)
            }
        }

        override fun postponeValidation(): Boolean = false

        override fun doValidateAll(): List<ValidationInfo> = panel.validateAll()
    }
}
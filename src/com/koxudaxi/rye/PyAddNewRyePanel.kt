// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package  com.koxudaxi.rye

import com.intellij.application.options.ModuleListCellRenderer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.PlatformUtils
import com.intellij.util.text.nullize
import com.intellij.util.ui.FormBuilder
import com.jetbrains.python.PyBundle
import com.jetbrains.python.PySdkBundle
import com.jetbrains.python.sdk.*
import com.jetbrains.python.sdk.add.PyAddNewEnvPanel
import com.jetbrains.python.sdk.add.PySdkPathChoosingComboBox
import com.jetbrains.python.sdk.add.addInterpretersAsync
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.event.DocumentEvent

/**
 * The UI panel for adding the rye interpreter for the project.
 *
 */

/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyAddNewRyePanel(private val project: Project?,
                          private val module: Module?,
                          private val existingSdks: List<Sdk>,
                          override var newProjectPath: String?,
                          context: UserDataHolder) : PyAddNewEnvPanel() {
    override val envName = "Rye"
    override val panelName: String get() = "Rye Environment"

    // TODO: Need a extension point
    override val icon: Icon = RYE_ICON

    private val moduleField: JComboBox<Module>

    private val baseSdkField = PySdkPathChoosingComboBox()

    init {
        addInterpretersAsync(baseSdkField) {
            val sdks = findBaseSdks(existingSdks, module, context).takeIf { it.isNotEmpty() }
                ?: detectSystemWideSdks(module, existingSdks, context)
            sdks.filterNot { !it.sdkSeemsValid || it.isRye }
        }
    }


    private val installPackagesCheckBox = JBCheckBox("Install packages from pyproject.toml").apply {
        isVisible = projectPath?.let {
            StandardFileSystems.local().findFileByPath(it)?.findChild(PY_PROJECT_TOML)?.let { file -> getPyProjectTomlForRye(file) }
        } != null
        isSelected = isVisible
    }

    private val ryePathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, null, null, FileChooserDescriptorFactory.createSingleFileDescriptor())
        val field = textField as? JBTextField ?: return@apply
        detectRyeExecutable()?.let {
            field.emptyText.text = "Auto-detected: ${it.absolutePath}"
        }
        PropertiesComponent.getInstance().ryePath?.let {
            field.text = it
        }
    }

    init {
        layout = BorderLayout()

        val modules = allModules(project)

        moduleField = ComboBox(modules.toTypedArray()).apply {
            renderer = ModuleListCellRenderer()
            preferredSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addItemListener {
                if (it.stateChange == ItemEvent.SELECTED) {
                    update()
                }
            }
        }

        ryePathField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                update()
            }
        })

        val builder = FormBuilder.createFormBuilder().apply {
            if (module == null && modules.size > 1) {
                val associatedObjectLabel = if (PlatformUtils.isPyCharm()) {
                    "Associated module:"
                }
                else {
                    "Associated project:"
                }
                addLabeledComponent(associatedObjectLabel, moduleField)
            }
            addLabeledComponent(PySdkBundle.message("python.venv.base.label"), baseSdkField)
            addComponent(installPackagesCheckBox)
            addLabeledComponent("Rye executable:", ryePathField)
        }
        add(builder.panel, BorderLayout.NORTH)
        update()
    }

    override fun getOrCreateSdk(): Sdk? {
        PropertiesComponent.getInstance().ryePath = ryePathField.text.nullize()
        return setupRyeSdkUnderProgress(project, selectedModule, existingSdks, newProjectPath,
            baseSdkField.selectedSdk?.homePath, installPackagesCheckBox.isSelected)?.apply {
            PySdkSettings.instance.preferredVirtualEnvBaseSdk = baseSdkField.selectedSdk?.homePath
        }
    }

    override fun validateAll(): List<ValidationInfo> =
        listOfNotNull(validateRyeExecutable(), validateRyeIsNotAdded())

    override fun addChangeListener(listener: Runnable) {
        ryePathField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                listener.run()
            }
        })
        super.addChangeListener(listener)
    }

    /**
     * Updates the view according to the current state of UI controls.
     */
    private fun update() {
        selectedModule?.let {
            installPackagesCheckBox.isEnabled = it.pyProjectToml != null
        }
    }

    /**
     * The effective module for which we add a new environment.
     */
    private val selectedModule: Module?
        get() = module ?: try {
            moduleField.selectedItem
        }
        catch (e: NullPointerException) {
            null
        } as? Module

    /**
     * Checks if `rye` is available on `$PATH`.
     */
    private fun validateRyeExecutable(): ValidationInfo? {
        val executable = ryePathField.text.nullize()?.let { File(it) }
            ?: detectRyeExecutable()
            ?: return ValidationInfo("Rye executable is not found")
        return when {
            !executable.exists() -> ValidationInfo(PyBundle.message("python.sdk.file.not.found", executable.absolutePath))
            !Files.isExecutable(executable.toPath()) || !executable.isFile -> ValidationInfo(
                PyBundle.message("python.sdk.cannot.execute", executable.absolutePath))
            else -> null
        }
    }

    private val isRye by lazy { existingSdks.filter { it.isRye }.associateBy { it.associatedModulePath } }
    private val homePath by lazy { existingSdks.associateBy { it.homePath } }
    private val pythonExecutable = ConcurrentHashMap<String, String>()
    private val venvInProject = ConcurrentHashMap<String, Boolean?>()

    private fun computePythonExecutable(homePath: String): String? {
        return pythonExecutable.getOrPut(homePath) { getPythonExecutable(homePath) }
    }

    private fun isVenvInProject(path: String): Boolean? {
        return venvInProject[path]
    }

    /**
     * Checks if the rye for the project hasn't been already added.
     */
    private fun validateRyeIsNotAdded(): ValidationInfo? {
        val path = projectPath ?: return null
        val addedRye = isRye[path] ?: return null
        if (addedRye.homeDirectory == null) return null
        // TODO: check existing envs
        val inProjectEnvExecutable = inProjectEnvPath?.let { computePythonExecutable(it) } ?: return null
        val inProjectEnv = homePath[inProjectEnvExecutable] ?: return null
        return ValidationInfo("Rye interpreter has been already added, select ''${inProjectEnv.name}''")
    }


    /**
     * The effective project path for the new project or for the existing project.
     */
    private val projectPath: String?
        get() = newProjectPath ?: selectedModule?.basePath ?: project?.basePath

    private val inProjectEnvDir = ".venv"
    private val inProjectEnvPath: String?
        get() = projectPath?.let { it + File.separator + inProjectEnvDir }
}
// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.koxudaxi.rye

import com.google.gson.annotations.SerializedName
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.execution.ExecutionException
import com.intellij.execution.RunCanceledByUserException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessNotCreatedException
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.target.readableFs.PathInfo
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.PathUtil
import com.intellij.util.PlatformUtils
import com.jetbrains.python.PythonModuleTypeBase
import com.jetbrains.python.inspections.PyPackageRequirementsInspection
import com.jetbrains.python.packaging.IndicatedProcessOutputListener
import com.jetbrains.python.packaging.PyExecutionException
import com.jetbrains.python.packaging.PyPackageManager
import com.jetbrains.python.packaging.PyPackageManagerUI
import com.jetbrains.python.sdk.*
import com.jetbrains.python.sdk.add.PyAddSdkGroupPanel
import com.jetbrains.python.sdk.add.PyAddSdkPanel
import com.jetbrains.python.sdk.add.target.ValidationRequest
import com.jetbrains.python.sdk.add.target.validateExecutableFile
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor
import com.jetbrains.python.statistics.modules
import icons.PythonIcons
import org.apache.tuweni.toml.Toml
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.SystemDependent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import java.util.regex.Pattern

const val PY_PROJECT_TOML: String = "pyproject.toml"
const val RYE_LOCK: String = "requirements.lock"
const val RYE_DEV_LOCK: String = "requirements-dev.lock"
const val RYE_DEFAULT_SOURCE_URL: String = "https://pypi.org/simple"
const val RYE_PATH_SETTING: String = "PyCharm.Rye.Path"

val LOCK_FILES = listOf(RYE_LOCK, RYE_DEV_LOCK)
// TODO: Provide a special icon for rye
val RYE_ICON = PythonIcons.Python.Virtualenv

/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

fun getPyProjectTomlForRye(virtualFile: VirtualFile): Pair<Long, VirtualFile?> {
    return Pair(virtualFile.modificationStamp, try {
        ReadAction.compute<VirtualFile, Throwable> {
            Toml.parse(virtualFile.inputStream).getTable("tool.rye")?.let { virtualFile }
        }
    }
    catch (e: Throwable) {
        null
    })
}

/**
 * The PyProject.toml found in the main content root of the module.
 */
val pyProjectTomlCache = mutableMapOf<String, Pair<Long, VirtualFile?>>()
val Module.pyProjectToml: VirtualFile?
    get() =
        baseDir?.findChild(PY_PROJECT_TOML)?.let { virtualFile ->
            (this.name + virtualFile.path).let { key ->
                pyProjectTomlCache.getOrPut(key) { getPyProjectTomlForRye(virtualFile) }.let { pair ->
                    when (virtualFile.modificationStamp) {
                        pair.first -> pair.second
                        else -> pyProjectTomlCache.put(key, getPyProjectTomlForRye(virtualFile))?.second
                    }
                }
            }
        }

/**
 * Tells if the SDK was added as a rye.
 */

/**
 * The user-set persisted path to the rye executable.
 */
var PropertiesComponent.ryePath: @SystemDependent String?
    get() = getValue(RYE_PATH_SETTING)
    set(value) {
        setValue(RYE_PATH_SETTING, value)
    }

/**
 * Detects the rye executable in `$PATH`.
 */
fun detectRyeExecutable(): File? {
    val name = when {
        SystemInfo.isWindows -> "rye"
        else -> "rye"
    }
    return PathEnvironmentVariableUtil.findInPath(name) ?: System.getProperty("user.home")?.let { homePath ->
        File(homePath + File.separator + ".cargo" + File.separator + "bin" + File.separator + name).takeIf { it.exists() }
    }
}

/**
 * Returns the configured rye executable or detects it automatically.
 */
fun getRyeExecutable(): File? =
    PropertiesComponent.getInstance().ryePath?.let { File(it) }?.takeIf { it.exists() } ?: detectRyeExecutable()

fun validateRyeExecutable(ryeExecutable: @SystemDependent String?): ValidationInfo? =
    validateExecutableFile(ValidationRequest(
        path = ryeExecutable,
        fieldIsEmpty = "Rye executable is not found",
        pathInfoProvider = PathInfo.localPathInfoProvider // TODO: pass real converter from targets when we support rye @ targets

    ))

fun suggestedSdkName(basePath: @NlsSafe String): @NlsSafe String = "Rye (${PathUtil.getFileName(basePath)})"


/**
 * Sets up the rye environment under the modal progress window.
 *
 * The rye is associated with the first valid object from this list:
 *
 * 1. New project specified by [newProjectPath]
 * 2. Existing module specified by [module]
 * 3. Existing project specified by [project]
 *
 * @return the SDK for rye, not stored in the SDK table yet.
 */
fun setupRyeSdkUnderProgress(project: Project?,
                                module: Module?,
                                existingSdks: List<Sdk>,
                                newProjectPath: String?,
                                python: String?,
                                installPackages: Boolean,
                                ryePath: String? = null): Sdk? {
    val projectPath = newProjectPath ?: module?.basePath ?: project?.basePath ?: return null
    val task = object : Task.WithResult<String, ExecutionException>(
        project, "Setting Up Rye Environment", true) {

        override fun compute(indicator: ProgressIndicator): String {
            indicator.isIndeterminate = true
            val init = StandardFileSystems.local().findFileByPath(projectPath)?.findChild(PY_PROJECT_TOML)?.let {
                getPyProjectTomlForRye(it)
            } == null
            setupRye(FileUtil.toSystemDependentName(projectPath), python, installPackages, init)
            return getPythonExecutable(projectPath)
        }
    }

    return createSdkByGenerateTask(task, existingSdks, null, projectPath, suggestedSdkName(projectPath))?.apply {
        isRye = true
        associateWithModule(module ?: project?.modules?.firstOrNull(), newProjectPath)
        //        project?.let { project ->
        //            existingSdks.find {
        //                it.associatedModulePath == projectPath && isRye(project, it) && it.homePath == homePath
        //            }?.run {
        //                 re-use existing invalid sdk
        //                return null
        //            }
        //            RyeConfigService.getInstance(project).ryeVirtualenvPaths.add(homePath!!)
        //        }
    }
}

/**
 * Sets up the rye environment for the specified project path.
 *
 * @return the path to the rye environment.
 */
fun setupRye(projectPath: @SystemDependent String, python: String?, installPackages: Boolean, init: Boolean) {
    when {
        init -> {
            runRye(projectPath, *listOf("init").toTypedArray())
            runRye(projectPath, *listOf("add", "--dev", "setuptools").toTypedArray())
            runRye(projectPath, *listOf("sync").toTypedArray())
        }
        installPackages -> {
            runRye(projectPath, *listOf("sync").toTypedArray())
        }
    }
}


var Sdk.isRye: Boolean
    get() = sdkAdditionalData is PyRyeSdkAdditionalData
    set(value) {
        val oldData = sdkAdditionalData
        val newData = if (value) {
            when (oldData) {
                is PythonSdkAdditionalData -> PyRyeSdkAdditionalData(oldData)
                else -> PyRyeSdkAdditionalData()
            }
        }
        else {
            when (oldData) {
                is PyRyeSdkAdditionalData -> PythonSdkAdditionalData(PythonSdkFlavor.getFlavor(this))
                else -> oldData
            }
        }
        val modificator = sdkModificator
        modificator.sdkAdditionalData = newData
        ApplicationManager.getApplication().runWriteAction { modificator.commitChanges() }
    }

/**
 * Runs the configured rye for the specified Rye SDK with the associated project path.
 */
fun runRye(sdk: Sdk, vararg args: String): String {
    val projectPath = sdk.associatedModulePath
        ?: throw PyExecutionException("Cannot find the project associated with this Rye environment",
            "Rye", emptyList(), ProcessOutput())
    return runRye(projectPath, *args)
}

/**
 * Runs the configured rye for the specified project path.
 */
fun runRye(projectPath: @SystemDependent String?, vararg args: String): String {
    val executable = getRyeExecutable()?.path
        ?: throw PyExecutionException("Cannot find Rye", "rye",
            emptyList(), ProcessOutput())

    val command = listOf(executable) + args
    val commandLine = GeneralCommandLine(command).withWorkDirectory(projectPath)
    val handler = CapturingProcessHandler(commandLine)
    val indicator = ProgressManager.getInstance().progressIndicator
    val result = with(handler) {
        when {
            indicator != null -> {
                addProcessListener(IndicatedProcessOutputListener(indicator))
                runProcessWithProgressIndicator(indicator)
            }
            else ->
                runProcess()
        }
    }
    return with(result) {
        @Suppress("DialogTitleCapitalization")
        when {
            isCancelled ->
                throw RunCanceledByUserException()
            exitCode != 0 ->
                throw PyExecutionException("Error Running Rye", executable,
                    args.asList(),
                    stdout, stderr, exitCode, emptyList())
            else -> stdout.trim()
        }
    }
}

fun runCommand(projectPath: @SystemDependent String, command: String, vararg args: String): String {
    val commandLine = GeneralCommandLine(listOf(command) + args).withWorkDirectory(projectPath)
    val handler = CapturingProcessHandler(commandLine)

    val result = with(handler) {
        runProcess()
    }
    return with(result) {
        @Suppress("DialogTitleCapitalization")
        when {
            isCancelled ->
                throw RunCanceledByUserException()
            exitCode != 0 ->
                throw PyExecutionException("Error Running Rye", command,
                    args.asList(),
                    stdout, stderr, exitCode, emptyList())
            else -> stdout
        }
    }
}

/**
 * The URLs of package sources configured in the Pipfile.lock of the module associated with this SDK.
 */
val Sdk.ryeSources: List<String>
    // TODO parse pyproject.toml for tool.rye.source.url
    get() = listOf(RYE_DEFAULT_SOURCE_URL)


/**
 * A quick-fix for setting up the rye for the module of the current PSI element.
 */
class UseRyeQuickFix(sdk: Sdk?, module: Module) : LocalQuickFix {
    private val quickFixName = when {
        sdk != null && sdk.isAssociatedWithAnotherModule(module) -> "Fix Rye interpreter"
        else -> "Use Rye interpreter"
    }

    companion object {
        fun isApplicable(module: Module): Boolean = module.pyProjectToml != null

        fun setUpRye(project: Project, module: Module) {
            val sdksModel = ProjectSdksModel().apply {
                reset(project)
            }
            val existingSdks = sdksModel.sdks.filter { it.sdkType is PythonSdkType }
            // XXX: Should we show an error message on exceptions and on null?
            val newSdk = setupRyeSdkUnderProgress(project, module, existingSdks, null, null, false)
                ?: return
            val existingSdk = existingSdks.find { it.isRye && it.homePath == newSdk.homePath }
            val sdk = existingSdk ?: newSdk
            if (sdk == newSdk) {
                SdkConfigurationUtil.addSdk(newSdk)
            }
            else {
                sdk.associateWithModule(module, null)
            }
            project.pythonSdk = sdk
            module.pythonSdk = sdk
            RyeConfigService.getInstance(project).ryeVirtualenvPaths.add(sdk.homePath!!)

        }
    }

    override fun getFamilyName() = quickFixName

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return
        // Invoke the setup later to escape the write action of the quick fix in order to show the modal progress dialog
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || module.isDisposed) return@invokeLater
            setUpRye(project, module)
        }
    }
}

/**
 * A quick-fix for installing packages specified in Pipfile.lock.
 */
class RyeInstallQuickFix : LocalQuickFix {
    companion object {
        fun ryeInstall(project: Project, module: Module) {
            val sdk = module.pythonSdk ?: return
            if (!sdk.isRye) return
            // TODO: create UI
            val listener = PyPackageRequirementsInspection.RunningPackagingTasksListener(module)
            val ui = PyPackageManagerUI(project, sdk, listener)
            ui.install(null, listOf())
        }
    }

    override fun getFamilyName() = "Install requirements from lock file"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return
        ryeInstall(project, module)
    }
}

/**
 * Watches for edits in PyProjectToml inside modules with a rye SDK set.
 */
class PyProjectTomlWatcher : EditorFactoryListener {
    private val changeListenerKey = Key.create<DocumentListener>("PyProjectToml.change.listener")
    private val notificationActive = Key.create<Boolean>("PyProjectToml.notification.active")
    private val content: @Nls String = if (ryeVersion?.let { it < "1.1.1" } == true) {
        "Run <a href='#lock'>rye lock</a> or <a href='#update'>rye update</a>"
    }
    else {
        "Run <a href='#lock'>rye lock</a>, <a href='#noupdate'>rye lock --no-update</a> or <a href='#update'>rye update</a>"
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project
        if (project == null || !isPyProjectTomlEditor(event.editor)) return
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                try {
                    val document = event.document
                    val module = document.virtualFile?.getModule(project) ?: return
                    // TODO: Should we remove listener when a sdk is changed to non-rye sdk?
                    //                    if (!isRye(module.project)) {
                    //                        with(document) {
                    //                            putUserData(notificationActive, null)
                    //                            val listener = getUserData(changeListenerKey) ?: return
                    //                            removeDocumentListener(listener)
                    //                            putUserData(changeListenerKey, null)
                    //                            return
                    //                        }
                    //                    }
                    if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                        notifyPyProjectTomlChanged(module)
                    }
                }
                catch (e: AlreadyDisposedException) {
                }
            }
        }
        with(event.editor.document) {
            addDocumentListener(listener)
            putUserData(changeListenerKey, listener)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val listener = event.editor.getUserData(changeListenerKey) ?: return
        event.editor.document.removeDocumentListener(listener)
    }

    private fun notifyPyProjectTomlChanged(module: Module) {
        if (module.getUserData(notificationActive) == true) return
        @Suppress("DialogTitleCapitalization") val title = when (module.ryeLock) {
            null -> "lock file is not found"
            else -> "lock file is out of date"
        }
        val notification = LOCK_NOTIFICATION_GROUP.createNotification(title, content, NotificationType.INFORMATION).setListener(
            NotificationListener { notification, event ->
                FileDocumentManager.getInstance().saveAllDocuments()
                when (event.description) {
                    "#lock" ->
                        runRyeInBackground(module, listOf("lock"), "Locking lock file")
                    "#update" ->
                        runRyeInBackground(module, listOf("--update-all"), "Updating Rye environment")
                }
                notification.expire()
                module.putUserData(notificationActive, null)
            })
        module.putUserData(notificationActive, true)
        notification.whenExpired {
            module.putUserData(notificationActive, null)
        }
        notification.notify(module.project)
    }

    private fun isPyProjectTomlEditor(editor: Editor): Boolean {
        val file = editor.document.virtualFile ?: return false
        if (file.name != PY_PROJECT_TOML) return false
        val project = editor.project ?: return false
        val module = file.getModule(project) ?: return false
        val sdk = module.pythonSdk ?: return false
        if (!sdk.isRye) return false
        return module.pyProjectToml == file
    }
}

private val Document.virtualFile: VirtualFile?
    get() = FileDocumentManager.getInstance().getFile(this)

private fun VirtualFile.getModule(project: Project): Module? =
    ModuleUtil.findModuleForFile(this, project)

private val LOCK_NOTIFICATION_GROUP by lazy { NotificationGroupManager.getInstance().getNotificationGroup("pyproject.toml Watcher") }

private val Module.ryeLock: VirtualFile?
    get() = baseDir?.findChild(RYE_LOCK)

private val Module.ryeDevLock: VirtualFile?
    get() = baseDir?.findChild(RYE_DEV_LOCK)

fun runRyeInBackground(module: Module, args: List<String>, @NlsSafe description: String) {
    val task = object : Task.Backgroundable(module.project, StringUtil.toTitleCase(description), true) {
        override fun run(indicator: ProgressIndicator) {
            val sdk = module.pythonSdk ?: return
            indicator.text = "$description..."
            try {
                runRye(sdk, *args.toTypedArray())
            }
            catch (e: RunCanceledByUserException) {
            }
            catch (e: ExecutionException) {
                showSdkExecutionException(sdk, e, "Error Running Rye")
            }
            finally {
                PythonSdkUtil.getSitePackagesDirectory(sdk)?.refresh(true, true)
                sdk.associatedModuleDir?.refresh(true, false)
                PyPackageManager.getInstance(sdk).refreshAndGetPackages(true)
            }
        }
    }
    ProgressManager.getInstance().run(task)
}

private fun allowCreatingNewEnvironments(project: Project?) =
    project != null || !PlatformUtils.isPyCharm() || PlatformUtils.isPyCharmEducational()

fun createRyePanel(project: Project?,
                      module: Module?,
                      existingSdks: List<Sdk>,
                      newProjectPath: String?,
                      context: UserDataHolder
): PyAddSdkPanel {
    val newRyePanel = when {
        allowCreatingNewEnvironments(project) -> PyAddNewRyePanel(project, module, existingSdks, null, context)
        else -> null
    }
    val existingRyePanel = PyAddExistingRyeEnvPanel(project, module, existingSdks, null, context)
    val panels = listOfNotNull(newRyePanel, existingRyePanel)
    val existingSdkPaths = sdkHomes(existingSdks)
    val defaultPanel = when {
        detectRyeEnvs(module, existingSdkPaths, project?.basePath
            ?: newProjectPath).any { it.isAssociatedWithModule(module) } -> existingRyePanel
        newRyePanel != null -> newRyePanel
        else -> existingRyePanel
    }
    return PyAddSdkGroupPanel(Supplier { "Rye environment" },
        RYE_ICON, panels, defaultPanel)
}


fun allModules(project: Project?): List<Module> {
    return project?.let {
        ModuleUtil.getModulesOfType(it, PythonModuleTypeBase.getInstance())
    }?.sortedBy { it.name } ?: emptyList()
}

fun sdkHomes(sdks: List<Sdk>): Set<String> = sdks.mapNotNull { it.homePath }.toSet()

fun detectRyeEnvs(module: Module?, existingSdkPaths: Set<String>, projectPath: String?): List<PyDetectedSdk> {
    val path = module?.basePath ?: projectPath ?: return emptyList()
    return try {
        listOf(PyDetectedSdk(getPythonExecutable(path)))
    }
    catch (e: Throwable) {
        emptyList()
    }
}



val ryeVersion: String?
    get() = syncRunRye(null, "--version", defaultResult = "") {
        it.split(' ').lastOrNull()
    }

inline fun <reified T> syncRunRye(projectPath: @SystemDependent String?,
                                     vararg args: String,
                                     defaultResult: T,
                                     crossinline callback: (String) -> T): T {
    return try {
        ApplicationManager.getApplication().executeOnPooledThread<T> {
            try {
                val result = runRye(projectPath, *args)
                callback(result)
            }
            catch (e: PyExecutionException) {
                defaultResult
            }
            catch (e: ProcessNotCreatedException) {
                defaultResult
            }
        }.get(30, TimeUnit.SECONDS)
    }
    catch (e: TimeoutException) {
        defaultResult
    }
}

fun getPythonExecutable(projectPath: String): String = FileUtil.join(projectPath, ".venv", "bin", "python")
//    PythonSdkUtil.getPythonExecutable(homePath) ?: FileUtil.join(homePath, "bin", "python")

/**
 * Parses the output of `rye show --outdated` into a list of packages.
 */
fun parseRyeShowOutdated(input: String): Map<String, RyeOutdatedVersion> {
    return input
        .lines()
        .mapNotNull { line ->
            line.split(Pattern.compile(" +"))
                .takeIf { it.size > 3 }?.let { it[0] to RyeOutdatedVersion(it[1], it[2]) }
        }.toMap()
}
data class ToolChain(
    val name: String,
    val downloadable: Boolean = false,
)
fun getToolChains(): List<ToolChain>? =
    syncRunRye(null, "toolchain", "list", "--include-downloadable", defaultResult = null) { result ->
         result
             .lines()
             .mapNotNull { line ->
                 line.split(" ").let {
                     when (it.size) {
                         1 -> ToolChain(it[0])
                         2 -> ToolChain(it[0], it[1] == "(downloadable)")
                         else -> null
                     }
                 }
     }
    }

data class RyeOutdatedVersion(
    @SerializedName("currentVersion") var currentVersion: String,
    @SerializedName("latestVersion") var latestVersion: String)

package com.koxudaxi.rye

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.util.UserDataHolder
import com.jetbrains.python.packaging.ui.PyPackageManagementService
import com.jetbrains.python.sdk.PyInterpreterInspectionQuickFixData
import com.jetbrains.python.sdk.PySdkProvider
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.add.PyAddNewEnvPanel
import org.jdom.Element
import javax.swing.Icon

/**
 *  This source code is created by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class RyeSdkProvider : PySdkProvider {
    override fun createEnvironmentAssociationFix(module: Module,
                                                 sdk: Sdk,
                                                 isPyCharm: Boolean,
                                                 associatedModulePath: String?): PyInterpreterInspectionQuickFixData? {
        if (sdk.isRye) {
            val projectUnit = if (isPyCharm) "project" else "module"
            val message = when {
                associatedModulePath != null ->
                    "Rye interpreter is associated with another ${projectUnit}: ${associatedModulePath}"
                else -> "Rye interpreter is not associated with any ${projectUnit}"
            }
            return PyInterpreterInspectionQuickFixData(UseRyeQuickFix(sdk, module), message)
        }
        return null
    }

    override fun createInstallPackagesQuickFix(module: Module): LocalQuickFix? {
        val sdk = PythonSdkUtil.findPythonSdk(module) ?: return null
        return if (sdk.isRye) RyeInstallQuickFix() else null
    }

    override fun createNewEnvironmentPanel(project: Project?,
                                           module: Module?,
                                           existingSdks: List<Sdk>,
                                           newProjectPath: String?,
                                           context: UserDataHolder): PyAddNewEnvPanel {
        return PyAddNewRyePanel(null, null, existingSdks, newProjectPath, context)
    }

    override fun getSdkAdditionalText(sdk: Sdk): String? = if (sdk.isRye) sdk.versionString else null

    override fun getSdkIcon(sdk: Sdk): Icon? {
        return if (sdk.isRye) RYE_ICON else null
    }

    override fun loadAdditionalDataForSdk(element: Element): SdkAdditionalData? {
        return PyRyeSdkAdditionalData.load(element)
    }

    override fun tryCreatePackageManagementServiceForSdk(project: Project, sdk: Sdk): PyPackageManagementService? {
        return if (sdk.isRye) PyRyePackageManagementService(project, sdk) else null
    }
}
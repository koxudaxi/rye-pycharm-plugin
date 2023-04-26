package com.koxudaxi.rye

import com.intellij.openapi.projectRoots.Sdk
import com.jetbrains.python.packaging.PyPackageManager
import com.jetbrains.python.packaging.PyPackageManagerProvider

/**
 *  This source code is created by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyRyePackageManagerProvider : PyPackageManagerProvider {
    override fun tryCreateForSdk(sdk: Sdk): PyPackageManager? = if (sdk.isRye) PyRyePackageManager(sdk) else null
}
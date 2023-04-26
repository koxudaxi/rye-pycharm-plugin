// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.koxudaxi.rye

import com.jetbrains.python.sdk.flavors.PythonFlavorProvider


/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyRyeSdkFlavorProvider : PythonFlavorProvider {
    override fun getFlavor(platformIndependent: Boolean) = PyRyeSdkFlavor
}
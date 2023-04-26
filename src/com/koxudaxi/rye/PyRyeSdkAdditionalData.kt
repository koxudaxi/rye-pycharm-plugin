// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.koxudaxi.rye

import com.jetbrains.python.sdk.PythonSdkAdditionalData
import org.jdom.Element

/**
 * Additional Rye data associated with an SDK.
 *
 */

/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

class PyRyeSdkAdditionalData : PythonSdkAdditionalData {
    constructor() : super(PyRyeSdkFlavor)
    constructor(data: PythonSdkAdditionalData) : super(data)

    override fun save(element: Element) {
        super.save(element)
        // We use this flag to create an instance of the correct additional data class. The flag itself is not used after that
        element.setAttribute(IS_RYE, "true")
    }

    companion object {
        private const val IS_RYE = "IS_RYE"

        /**
         * Loads serialized data from an XML element.
         */
        @JvmStatic
        fun load(element: Element): PyRyeSdkAdditionalData? =
            when {
                element.getAttributeValue(IS_RYE) == "true" -> {
                    PyRyeSdkAdditionalData().apply {
                        load(element)
                    }
                }
                else -> null
            }

        /**
         * Creates a new instance of data with copied fields.
         */
        @JvmStatic
        fun copy(data: PythonSdkAdditionalData): PyRyeSdkAdditionalData =
            PyRyeSdkAdditionalData(data)
    }
}
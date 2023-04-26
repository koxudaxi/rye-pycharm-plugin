package com.koxudaxi.rye

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 *  This source code is edited by @koxudaxi Koudai Aono <koxudaxi@gmail.com>
 */

@State(name = "RyeConfigService", storages = [Storage("rye.xml")])
class RyeConfigService : PersistentStateComponent<RyeConfigService> {
    var ryeVirtualenvPaths = mutableSetOf<String>()

    override fun getState(): RyeConfigService {
        return this
    }

    override fun loadState(config: RyeConfigService) {
        XmlSerializerUtil.copyBean(config, this)
    }

    companion object {
        fun getInstance(project: Project): RyeConfigService {
            return ServiceManager.getService(project, RyeConfigService::class.java)
        }
    }

}
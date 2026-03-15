package com.forcepushdev.preflight.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Properties

@Service(Service.Level.PROJECT)
class ConfigStore(private val project: Project) {

    fun load(): PreflightConfig {
        val file = configFile() ?: return PreflightConfig()
        if (!file.exists()) return PreflightConfig()
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            PreflightConfig(branchFilter = props.getProperty("branchFilter")?.toBoolean() ?: false)
        } catch (e: Exception) {
            PreflightConfig()
        }
    }

    private fun configFile(): File? =
        project.basePath?.let { File("$it/.preflight/config.properties") }
}

package com.forcepushdev.preflight.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ConfigStoreTest : BasePlatformTestCase() {

    private lateinit var store: ConfigStore

    override fun setUp() {
        super.setUp()
        store = ConfigStore(project)
    }

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    fun testLoad_noFile_returnsBranchFilterFalse() {
        val config = store.load()

        assertFalse(config.branchFilter)
    }

    fun testLoad_branchFilterTrue_returnsTrue() {
        project.basePath?.let {
            val dir = File("$it/.preflight").also { d -> d.mkdirs() }
            File(dir, "config.properties").writeText("branchFilter=true")
        }

        val config = store.load()

        assertTrue(config.branchFilter)
    }

    fun testLoad_invalidFile_returnsDefault() {
        project.basePath?.let {
            val dir = File("$it/.preflight").also { d -> d.mkdirs() }
            File(dir, "config.properties").writeText("not=valid\u0000")
        }

        val config = store.load()

        assertFalse(config.branchFilter)
    }
}

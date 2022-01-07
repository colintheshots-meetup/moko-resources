/*
 * Copyright 2022 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.gradle.generator.js

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import dev.icerock.gradle.generator.FilesGenerator
import dev.icerock.gradle.generator.jvm.JvmFilesGenerator
import org.gradle.api.file.FileTree
import java.io.File

class JsFilesGenerator(
    inputFileTree: FileTree
) : FilesGenerator(inputFileTree) {

    override fun getClassModifiers(): Array<KModifier> = arrayOf(KModifier.ACTUAL)

    override fun getPropertyModifiers(): Array<KModifier> = arrayOf(KModifier.ACTUAL)

    override fun getPropertyInitializer(fileSpec: FileSpec): CodeBlock {
        return CodeBlock.of("FileResource(fileUrl = js(\"require(\\\"$FILES_DIR/${fileSpec.file.name}\\\")\") as String)")
    }

    override fun extendObjectBodyAtStart(classBuilder: TypeSpec.Builder) = Unit

    override fun extendObjectBodyAtEnd(classBuilder: TypeSpec.Builder) = Unit

    override fun generateResources(resourcesGenerationDir: File, files: List<FileSpec>) {
        val fileResDir = File(resourcesGenerationDir, FILES_DIR).apply { mkdirs() }
        files.forEach { (_, file) ->
            file.copyTo(File(fileResDir, file.name))
        }
    }

    companion object {
        const val FILES_DIR = "files"
    }
}
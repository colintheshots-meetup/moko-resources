/*
 * Copyright 2020 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.gradle.tasks

import dev.icerock.gradle.MRVisibility
import dev.icerock.gradle.configuration.getAndroidRClassPackage
import dev.icerock.gradle.rework.PlatformContainerGenerator
import dev.icerock.gradle.rework.PlatformResourceGenerator
import dev.icerock.gradle.rework.ResourceTypeGenerator
import dev.icerock.gradle.rework.ResourcesFiles
import dev.icerock.gradle.rework.ResourcesGenerator
import dev.icerock.gradle.rework.container.AppleContainerGenerator
import dev.icerock.gradle.rework.container.JvmContainerGenerator
import dev.icerock.gradle.rework.container.NOPContainerGenerator
import dev.icerock.gradle.rework.metadata.container.ContainerMetadata
import dev.icerock.gradle.rework.metadata.container.ObjectMetadata
import dev.icerock.gradle.rework.metadata.container.ResourceType
import dev.icerock.gradle.rework.metadata.resource.StringMetadata
import dev.icerock.gradle.rework.string.AndroidStringResourceGenerator
import dev.icerock.gradle.rework.string.AppleStringResourceGenerator
import dev.icerock.gradle.rework.string.JsStringResourceGenerator
import dev.icerock.gradle.rework.string.JvmStringResourceGenerator
import dev.icerock.gradle.rework.string.NOPStringResourceGenerator
import dev.icerock.gradle.rework.string.StringResourceGenerator
import dev.icerock.gradle.toModifier
import dev.icerock.gradle.utils.flatName
import dev.icerock.gradle.utils.isStrictLineBreaks
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

@CacheableTask
abstract class GenerateMultiplatformResourcesTask : DefaultTask() {

    @get:Input
    abstract val sourceSetName: Property<String>

    @get:InputFiles
    @get:Classpath
    abstract val ownResources: ConfigurableFileCollection

    @get:Input
    abstract val upperSourceSets: MapProperty<String, FileCollection>

    @get:Optional
    @get:Input
    abstract val platformType: Property<String>

    @get:Optional
    @get:Input
    abstract val konanTarget: Property<String>

    @get:Input
    abstract val resourcesPackageName: Property<String>

    @get:Input
    abstract val resourcesClassName: Property<String>

    @get:Optional
    @get:Input
    abstract val androidSourceSetName: Property<String>

    @get:Input
    abstract val iosBaseLocalizationRegion: Property<String>

    @get:Input
    abstract val resourcesVisibility: Property<MRVisibility>

    @get:OutputFile
    abstract val outputMetadataFile: RegularFileProperty

    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:InputFiles
    abstract val inputMetadataFiles: ConfigurableFileCollection

    //TODO Realise
//    @get:OutputFile
//    abstract val generationReport: RegularFileProperty

    @get:OutputDirectory
    abstract val outputResourcesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputSourcesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputAssetsDir: DirectoryProperty

    private val kotlinPlatformType: KotlinPlatformType
        get() = KotlinPlatformType.valueOf(platformType.get())

    private val kotlinKonanTarget: KonanTarget
        get() {
            val name: String = konanTarget.get()
            return KonanTarget.predefinedTargets[name] ?: error("can't find $name in KonanTarget")
        }

    init {
        group = "moko-resources"
    }

    @OptIn(InternalSerializationApi::class)
    @TaskAction
    fun generate() {
        val json = Json {
            prettyPrint = true
        }
        val generator: ResourcesGenerator = createGenerator()

        val files = ResourcesFiles(
            ownSourceSet = ResourcesFiles.SourceSetResources(
                sourceSetName = sourceSetName.get(),
                fileTree = ownResources.asFileTree
            ),
            upperSourceSets = upperSourceSets.get().map { item ->
                ResourcesFiles.SourceSetResources(
                    sourceSetName = item.key,
                    fileTree = item.value.asFileTree
                )
            }
        )
        val serializer: KSerializer<List<ContainerMetadata>> =
            ListSerializer(ContainerMetadata.serializer())
        val inputMetadata: List<ContainerMetadata> = inputMetadataFiles.files.flatMap { file ->
            json.decodeFromString(serializer, file.readText())
        }

        val outputMetadata: List<ContainerMetadata> =
            if (kotlinPlatformType == KotlinPlatformType.common) {
                generator.generateCommonKotlin(files, inputMetadata)
            } else {
                generator.generateTargetKotlin(files, inputMetadata).also { containers ->
                    generator.generateResources(containers.mapNotNull { it as? ObjectMetadata })
                }
            }

        outputMetadataFile.get().asFile.writeText(json.encodeToString(serializer, outputMetadata))
    }

    private fun createGenerator(): ResourcesGenerator {
        return ResourcesGenerator(
            containerGenerator = createPlatformContainerGenerator(),
            typesGenerators = listOf(
                createStringGenerator()
            ),
            resourcesPackageName = resourcesPackageName.get(),
            resourcesClassName = resourcesClassName.get(),
            sourceSetName = sourceSetName.get(),
            visibilityModifier = resourcesVisibility.get().toModifier(),
            sourcesGenerationDir = outputSourcesDir.get().asFile
        )
    }

    private fun createPlatformContainerGenerator(): PlatformContainerGenerator {
        return createByPlatform(
            createCommon = { NOPContainerGenerator() },
            createAndroid = { NOPContainerGenerator() },
            createJs = { NOPContainerGenerator() },
            createApple = {
                AppleContainerGenerator(
                    bundleIdentifier = "${resourcesPackageName.get()}.MR"
                )
            },
            createJvm = {
                JvmContainerGenerator(
                    resourcesClassName = resourcesClassName.get(),
                    flattenClassPackage = resourcesPackageName.get().flatName
                )
            }
        )
    }

    private fun createStringGenerator(): ResourceTypeGenerator<StringMetadata> {
        return ResourceTypeGenerator(
            generationPackage = resourcesPackageName.get(),
            resourceClass = StringResourceGenerator.className,
            resourceType = ResourceType.STRINGS,
            visibilityModifier = resourcesVisibility.get().toModifier(),
            generator = StringResourceGenerator(
                strictLineBreaks = project.isStrictLineBreaks
            ),
            platformResourceGenerator = createPlatformStringGenerator(),
            filter = { include("**/strings*.xml") }
        )
    }

    private fun getAndroidR(): String = project.getAndroidRClassPackage().get()

    private fun createPlatformStringGenerator(): PlatformResourceGenerator<StringMetadata> {
        val resourcesGenerationDir: File = outputResourcesDir.get().asFile
        return createByPlatform(
            // TODO find way to remove this NOP
            createCommon = { NOPStringResourceGenerator() },
            createAndroid = {
                AndroidStringResourceGenerator(
                    androidRClassPackage = getAndroidR(),
                    resourcesGenerationDir = resourcesGenerationDir
                )
            },
            createApple = {
                AppleStringResourceGenerator(
                    baseLocalizationRegion = iosBaseLocalizationRegion.get(),
                    resourcesGenerationDir = resourcesGenerationDir
                )
            },
            createJvm = {
                JvmStringResourceGenerator(
                    flattenClassPackage = resourcesPackageName.get().flatName,
                    resourcesGenerationDir = resourcesGenerationDir
                )
            },
            createJs = {
                JsStringResourceGenerator(
                    resourcesPackageName = resourcesPackageName.get(),
                    resourcesGenerationDir = resourcesGenerationDir
                )
            }
        )
    }

    private fun <T> createByPlatform(
        createCommon: () -> T,
        createAndroid: () -> T,
        createApple: () -> T,
        createJvm: () -> T,
        createJs: () -> T,
    ): T {
        return when (kotlinPlatformType) {
            KotlinPlatformType.common -> createCommon()
            KotlinPlatformType.jvm -> createJvm()
            KotlinPlatformType.androidJvm -> createAndroid()
            KotlinPlatformType.js -> createJs()
            KotlinPlatformType.native -> when (kotlinKonanTarget) {
                KonanTarget.IOS_ARM32,
                KonanTarget.IOS_ARM64,
                KonanTarget.IOS_SIMULATOR_ARM64,
                KonanTarget.IOS_X64,

                KonanTarget.MACOS_ARM64,
                KonanTarget.MACOS_X64,

                KonanTarget.TVOS_ARM64,
                KonanTarget.TVOS_SIMULATOR_ARM64,
                KonanTarget.TVOS_X64,

                KonanTarget.WATCHOS_ARM32,
                KonanTarget.WATCHOS_ARM64,
                KonanTarget.WATCHOS_DEVICE_ARM64,
                KonanTarget.WATCHOS_SIMULATOR_ARM64,
                KonanTarget.WATCHOS_X64,
                KonanTarget.WATCHOS_X86 -> createApple()

                KonanTarget.ANDROID_ARM32,
                KonanTarget.ANDROID_ARM64,
                KonanTarget.ANDROID_X64,
                KonanTarget.ANDROID_X86,

                KonanTarget.LINUX_ARM32_HFP,
                KonanTarget.LINUX_ARM64,
                KonanTarget.LINUX_MIPS32,
                KonanTarget.LINUX_MIPSEL32,
                KonanTarget.LINUX_X64,

                KonanTarget.MINGW_X64,
                KonanTarget.MINGW_X86,

                KonanTarget.WASM32,

                is KonanTarget.ZEPHYR -> error("$kotlinKonanTarget not supported by moko-resources now")
            }

            KotlinPlatformType.wasm -> error("$kotlinPlatformType not supported by moko-resources now")
        }
    }
}

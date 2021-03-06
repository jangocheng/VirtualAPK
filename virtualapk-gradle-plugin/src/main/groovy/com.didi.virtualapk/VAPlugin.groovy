package com.didi.virtualapk

import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.didi.virtualapk.hooker.DxTaskHooker
import com.didi.virtualapk.hooker.MergeAssetsHooker
import com.didi.virtualapk.hooker.MergeJniLibsHooker
import com.didi.virtualapk.hooker.MergeManifestsHooker
import com.didi.virtualapk.hooker.PrepareDependenciesHooker
import com.didi.virtualapk.hooker.ProcessResourcesHooker
import com.didi.virtualapk.hooker.ProguardHooker
import com.didi.virtualapk.hooker.TaskHookerManager
import com.didi.virtualapk.transform.StripClassAndResTransform
import com.didi.virtualapk.utils.FileBinaryCategory
import com.didi.virtualapk.utils.Log
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.internal.reflect.Instantiator
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import javax.inject.Inject

/**
 * VirtualAPK gradle plugin for plugin project
 *
 * @author zhengtao
 */

class VAPlugin extends BasePlugin {

    //Files be retained after host apk build
    //private def hostFileNames = ['versions', 'R.txt', 'mapping.txt', 'versions.txt', 'Host_R.txt'] as Set

    /**
     * Stores files generated by the host side and is used when building plugin apk
     */
    private def hostDir

    protected boolean isBuildingPlugin = false

    /**
     * TaskHooker manager, registers hookers when apply invoked
     */
    private TaskHookerManager taskHookerManager

    private StripClassAndResTransform stripClassAndResTransform

    @Inject
    public VAPlugin(Instantiator instantiator, ToolingModelBuilderRegistry registry) {
        super(instantiator, registry)
    }

    @Override
    protected void beforeCreateAndroidTasks(boolean isBuildingPlugin) {
        this.isBuildingPlugin = isBuildingPlugin
        if (!isBuildingPlugin) {
            Log.i 'Plugin', "Skipped all VirtualApk's configurations!"
            return
        }
        stripClassAndResTransform = new StripClassAndResTransform(project)
        android.registerTransform(stripClassAndResTransform)

        android.defaultConfig.buildConfigField("int", "PACKAGE_ID", "0x" + Integer.toHexString(virtualApk.packageId))
    }

    File getJarPath() {
        URL url = this.class.getResource("")
        int index = url.path.indexOf('!')
        if (index < 0) {
            index = url.path.length()
        }
        return project.file(url.path.substring(0, index))
    }

    @Override
    void apply(final Project project) {
        super.apply(project)

        hostDir = new File(project.rootDir, "host")
        if (!hostDir.exists()) {
            hostDir.mkdirs()
        }

        project.afterEvaluate {
            if (!isBuildingPlugin) {
                return
            }

            stripClassAndResTransform.onProjectAfterEvaluate()
            taskHookerManager = new VATaskHookerManager(project, instantiator)
            taskHookerManager.registerTaskHookers()

            if (android.dataBinding.enabled) {
                project.dependencies.add('annotationProcessor', project.files(jarPath.absolutePath))
            }

            android.applicationVariants.each { ApplicationVariantImpl variant ->

                checkConfig()

                virtualApk.with {
                    packageName = variant.applicationId
                    packagePath = packageName.replace('.'.charAt(0), File.separatorChar)
                    hostSymbolFile = new File(hostDir, "Host_R.txt")
                    hostDependenceFile = new File(hostDir, "versions.txt")
                }
            }
        }
    }

    /**
     * Check the plugin apk related config infos
     */
    private void checkConfig() {
        int packageId = virtualApk.packageId
        if (packageId == 0) {
            def err = new StringBuilder('you should set the packageId in build.gradle,\n ')
            err.append('please declare it in application project build.gradle:\n')
            err.append('    virtualApk {\n')
            err.append('        packageId = 0xXX \n')
            err.append('    }\n')
            err.append('apply for the value of packageId.\n')
            throw new InvalidUserDataException(err.toString())
        }
        if (packageId >= 0x7f || packageId <= 0x01) {
            throw new IllegalArgumentException('the packageId must be in [0x02, 0x7E].')
        }

        String targetHost = virtualApk.targetHost
        if (!targetHost) {
            def err = new StringBuilder('\nyou should specify the targetHost in build.gradle, e.g.: \n')
            err.append('    virtualApk {\n')
            err.append('        //when target Host in local machine, value is host application directory\n')
            err.append('        targetHost = ../xxxProject/app \n')
            err.append('    }\n')
            throw new InvalidUserDataException(err.toString())
        }

        File hostLocalDir = new File(targetHost)
        if (!hostLocalDir.exists()) {
            def err = "The directory of host application doesn't exist! Dir: ${hostLocalDir.absolutePath}"
            throw new InvalidUserDataException(err)
        }

        File hostR = new File(hostLocalDir, "build/VAHost/Host_R.txt")
        if (hostR.exists()) {
            def dst = new File(hostDir, "Host_R.txt")
            use(FileBinaryCategory) {
                dst << hostR
            }
        } else {
            def err = new StringBuilder("Can't find ${hostR.absolutePath}, please check up your host application\n")
            err.append("  need apply com.didi.virtualapk.host in build.gradle of host application\n ")
            throw new InvalidUserDataException(err.toString())
        }

        File hostVersions = new File(hostLocalDir, "build/VAHost/versions.txt")
        if (hostVersions.exists()) {
            def dst = new File(hostDir, "versions.txt")
            use(FileBinaryCategory) {
                dst << hostVersions
            }
        } else {
            def err = new StringBuilder("Can't find ${hostVersions.absolutePath}, please check up your host application\n")
            err.append("  need apply com.didi.virtualapk.host in build.gradle of host application \n")
            throw new InvalidUserDataException(err.toString())
        }

        File hostMapping = new File(hostLocalDir, "build/VAHost/mapping.txt")
        if (hostMapping.exists()) {
            def dst = new File(hostDir, "mapping.txt")
            use(FileBinaryCategory) {
                dst << hostMapping
            }
        }
    }

    static class VATaskHookerManager extends TaskHookerManager {

        VATaskHookerManager(Project project, Instantiator instantiator) {
            super(project, instantiator)
        }

        @Override
        void registerTaskHookers() {
            android.applicationVariants.all { ApplicationVariantImpl appVariant ->
                if (!appVariant.buildType.name.equalsIgnoreCase("release")) {
                    return
                }

                registerTaskHooker(instantiator.newInstance(PrepareDependenciesHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(MergeAssetsHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(MergeManifestsHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(MergeJniLibsHooker, project, appVariant))
//                registerTaskHooker(instantiator.newInstance(ShrinkResourcesHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(ProcessResourcesHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(ProguardHooker, project, appVariant))
                registerTaskHooker(instantiator.newInstance(DxTaskHooker, project, appVariant))
            }
        }
    }
}

package io.github.twyora.douyinenhancer.bridge

import android.content.res.XModuleResources
import com.highcapable.yukihookapi.hook.param.PackageParam

class ModuleApp private constructor(val apkPath: String) {
    val resources: XModuleResources by lazy {
        XModuleResources.createInstance(apkPath, null)
    }

    companion object {
        @Volatile
        lateinit var instance: ModuleApp
            private set

        fun init(packageParam: PackageParam) {
            instance = ModuleApp(packageParam.moduleAppFilePath)
        }
    }
}

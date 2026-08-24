package com.eko

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class RNBackgroundDownloaderPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return if (name == RNBackgroundDownloaderModuleImpl.NAME) {
            RNBackgroundDownloaderModule(reactContext)
        } else {
            null
        }
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        return ReactModuleInfoProvider {
            val moduleInfos: MutableMap<String, ReactModuleInfo> = HashMap()
            moduleInfos[RNBackgroundDownloaderModuleImpl.NAME] = ReactModuleInfo(
                RNBackgroundDownloaderModuleImpl.NAME,
                RNBackgroundDownloaderModuleImpl.NAME,
                false,
                false,
                false,
                true
            )
            moduleInfos
        }
    }
}

package com.eko

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class RNBackgroundDownloaderPackage : TurboReactPackage() {
    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }

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
            val isTurboModule = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
            moduleInfos[RNBackgroundDownloaderModuleImpl.NAME] = ReactModuleInfo(
                RNBackgroundDownloaderModuleImpl.NAME,
                RNBackgroundDownloaderModuleImpl.NAME,
                false,  // canOverrideExistingModule
                false,  // needsEagerInit
                true,   // hasConstants
                false,  // isCxxModule
                isTurboModule
            )
            moduleInfos
        }
    }
}

package com.eko

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * BaseReactPackage for New Architecture
 */
class RNBackgroundDownloaderTurboPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return if (name == RNBackgroundDownloaderModuleImpl.NAME) {
            RNBackgroundDownloaderModule(reactContext)
        } else {
            null
        }
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        return ReactModuleInfoProvider {
            val moduleInfos = mutableMapOf<String, ReactModuleInfo>()
            val isTurboModule = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED

            moduleInfos[RNBackgroundDownloaderModuleImpl.NAME] = ReactModuleInfo(
                RNBackgroundDownloaderModuleImpl.NAME,
                RNBackgroundDownloaderModuleImpl.NAME,
                false, // canOverrideExistingModule
                false, // needsEagerInit
                true,  // hasConstants
                false, // isCxxModule
                isTurboModule // isTurboModule
            )
            moduleInfos
        }
    }
}

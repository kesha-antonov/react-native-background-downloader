package com.eko;

import androidx.annotation.Nullable;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.module.model.ReactModuleInfoProvider;
import com.facebook.react.module.model.ReactModuleInfo;
import com.facebook.react.TurboReactPackage;

import java.util.HashMap;
import java.util.Map;

/**
 * TurboReactPackage for New Architecture
 */
public class RNBackgroundDownloaderTurboPackage extends TurboReactPackage {

    @Nullable
    @Override
    public NativeModule getModule(String name, ReactApplicationContext reactContext) {
        if (name.equals(RNBackgroundDownloaderModuleImpl.NAME)) {
            return new RNBackgroundDownloaderModule(reactContext);
        } else {
            return null;
        }
    }

    @Override
    public ReactModuleInfoProvider getReactModuleInfoProvider() {
        return () -> {
            final Map<String, ReactModuleInfo> moduleInfos = new HashMap<>();
            boolean isTurboModule = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED;
            
            moduleInfos.put(
                    RNBackgroundDownloaderModuleImpl.NAME,
                    new ReactModuleInfo(
                            RNBackgroundDownloaderModuleImpl.NAME,
                            RNBackgroundDownloaderModuleImpl.NAME,
                            false, // canOverrideExistingModule
                            false, // needsEagerInit
                            true,  // hasConstants
                            false, // isCxxModule
                            isTurboModule // isTurboModule
                    ));
            return moduleInfos;
        };
    }
}
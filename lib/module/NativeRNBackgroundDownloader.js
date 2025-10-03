"use strict";

import { TurboModuleRegistry } from 'react-native';
const module = TurboModuleRegistry.getEnforcing('RNBackgroundDownloader');
export const Constants = module?.getConstants();
export default module;
//# sourceMappingURL=NativeRNBackgroundDownloader.js.map
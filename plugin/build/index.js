"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
const config_plugins_1 = require("@expo/config-plugins");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const withRNBackgroundDownloader = (config, options) => {
    const { mmkvVersion = '1.3.16', skipMmkvDependency = false } = options || {};
    // Auto-detect react-native-mmkv in dependencies
    const hasReactNativeMmkv = checkForReactNativeMmkv(config);
    const shouldSkipMmkv = skipMmkvDependency || hasReactNativeMmkv;
    // Handle iOS AppDelegate modifications
    config = (0, config_plugins_1.withAppDelegate)(config, (config) => {
        if (config.modResults.language === 'objc') {
            // For Objective-C AppDelegate.m (React Native < 0.77)
            config.modResults.contents = addObjCSupport(config.modResults.contents);
        }
        else {
            // For Swift AppDelegate.swift (React Native >= 0.77)
            config.modResults.contents = addSwiftSupport(config.modResults.contents);
            // For Swift projects, we need to ensure the bridging header includes our import
            const projectRoot = config.modRequest.projectRoot;
            const iosProjectRoot = config_plugins_1.IOSConfig.Paths.getSourceRoot(projectRoot);
            addToBridgingHeader(iosProjectRoot, config.modRequest.projectName || 'App');
        }
        return config;
    });
    // Handle Android MMKV dependency (skip if react-native-mmkv is present)
    if (!shouldSkipMmkv)
        config = (0, config_plugins_1.withAppBuildGradle)(config, (config) => {
            config.modResults.contents = addMmkvDependencyAndroid(config.modResults.contents, mmkvVersion);
            return config;
        });
    return config;
};
/**
 * Check if react-native-mmkv is present in the project dependencies.
 * react-native-mmkv uses io.github.zhongwuzw:mmkv which conflicts with com.tencent:mmkv-shared.
 */
function checkForReactNativeMmkv(config) {
    var _a, _b, _c, _d, _e, _f, _g;
    const dependencies = ((_b = (_a = config._internal) === null || _a === void 0 ? void 0 : _a.projectConfig) === null || _b === void 0 ? void 0 : _b.dependencies) || {};
    const devDependencies = ((_d = (_c = config._internal) === null || _c === void 0 ? void 0 : _c.projectConfig) === null || _d === void 0 ? void 0 : _d.devDependencies) || {};
    // Also check the expo config directly
    const allDeps = {
        ...dependencies,
        ...devDependencies,
    };
    // Check for react-native-mmkv in dependencies
    if ('react-native-mmkv' in allDeps)
        return true;
    // Try to detect from package.json if available
    try {
        const projectRoot = ((_e = config._internal) === null || _e === void 0 ? void 0 : _e.projectRoot) || process.cwd();
        const packageJsonPath = path.join(projectRoot, 'package.json');
        if (fs.existsSync(packageJsonPath)) {
            const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
            if (((_f = packageJson.dependencies) === null || _f === void 0 ? void 0 : _f['react-native-mmkv']) || ((_g = packageJson.devDependencies) === null || _g === void 0 ? void 0 : _g['react-native-mmkv']))
                return true;
        }
    }
    catch (_h) {
        // Ignore errors reading package.json
    }
    return false;
}
function addMmkvDependencyAndroid(buildGradleContents, mmkvVersion) {
    // Check if MMKV dependency is already present
    if (buildGradleContents.includes('com.tencent:mmkv') || buildGradleContents.includes('io.github.zhongwuzw:mmkv'))
        return buildGradleContents;
    // Find the dependencies block and add MMKV
    const dependenciesRegex = /dependencies\s*\{/;
    const match = buildGradleContents.match(dependenciesRegex);
    if (match) {
        const insertPosition = buildGradleContents.indexOf(match[0]) + match[0].length;
        const mmkvDependency = `\n    // MMKV is required by @kesha-antonov/react-native-background-downloader\n    // If you're using react-native-mmkv, remove this line to avoid duplicate class errors\n    implementation 'com.tencent:mmkv-shared:${mmkvVersion}'`;
        buildGradleContents = buildGradleContents.slice(0, insertPosition) +
            mmkvDependency +
            buildGradleContents.slice(insertPosition);
    }
    return buildGradleContents;
}
function addObjCSupport(appDelegateContents) {
    // Add import if not already present
    if (!appDelegateContents.includes('#import <RNBackgroundDownloader.h>')) {
        // Add import after existing React imports
        const reactImportRegex = /#import <React\/.*?\.h>/g;
        const matches = appDelegateContents.match(reactImportRegex);
        if (matches) {
            // Add after the last React import
            const lastReactImport = matches[matches.length - 1];
            const lastImportIndex = appDelegateContents.lastIndexOf(lastReactImport);
            const importPosition = lastImportIndex + lastReactImport.length;
            appDelegateContents = appDelegateContents.slice(0, importPosition) +
                '\n#import <RNBackgroundDownloader.h>' +
                appDelegateContents.slice(importPosition);
        }
        else {
            // If no React imports found, add after the first import
            const firstImportMatch = appDelegateContents.match(/#import .*?\.h/);
            if (firstImportMatch) {
                const importPosition = appDelegateContents.indexOf(firstImportMatch[0]) + firstImportMatch[0].length;
                appDelegateContents = appDelegateContents.slice(0, importPosition) +
                    '\n#import <RNBackgroundDownloader.h>' +
                    appDelegateContents.slice(importPosition);
            }
        }
    }
    // Add the handleEventsForBackgroundURLSession method if not already present
    if (!appDelegateContents.includes('handleEventsForBackgroundURLSession')) {
        const methodToAdd = `
- (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
{
  [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
}
`;
        // Find the end of the @implementation block but before @end
        const endMatch = appDelegateContents.match(/@end\s*$/);
        if (endMatch) {
            const endPosition = appDelegateContents.lastIndexOf(endMatch[0]);
            appDelegateContents = appDelegateContents.slice(0, endPosition) +
                methodToAdd + '\n' +
                appDelegateContents.slice(endPosition);
        }
    }
    return appDelegateContents;
}
function addSwiftSupport(appDelegateContents) {
    // For Swift, we need to add the method to the AppDelegate class
    // The import should be handled by the bridging header
    // Add the handleEventsForBackgroundURLSession method if not already present
    if (!appDelegateContents.includes('handleEventsForBackgroundURLSession')) {
        const methodToAdd = `
  func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    RNBackgroundDownloader.setCompletionHandlerWithIdentifier(identifier, completionHandler: completionHandler)
  }
`;
        // Find the end of the AppDelegate class but before the closing brace
        const classEndRegex = /^}$/gm;
        const matches = [...appDelegateContents.matchAll(classEndRegex)];
        if (matches.length > 0) {
            // Insert before the last closing brace (assuming it's the AppDelegate class)
            const lastMatch = matches[matches.length - 1];
            const insertPosition = lastMatch.index;
            appDelegateContents = appDelegateContents.slice(0, insertPosition) +
                methodToAdd + '\n' +
                appDelegateContents.slice(insertPosition);
        }
    }
    return appDelegateContents;
}
function addToBridgingHeader(iosProjectRoot, projectName) {
    // Common bridging header file paths
    const possibleBridgingHeaderPaths = [
        path.join(iosProjectRoot, `${projectName}-Bridging-Header.h`),
        path.join(iosProjectRoot, `${projectName}`, `${projectName}-Bridging-Header.h`),
        path.join(iosProjectRoot, 'Bridging-Header.h'),
    ];
    let bridgingHeaderPath = null;
    // Find existing bridging header
    for (const possiblePath of possibleBridgingHeaderPaths)
        if (fs.existsSync(possiblePath)) {
            bridgingHeaderPath = possiblePath;
            break;
        }
    // If no bridging header exists, create one
    if (!bridgingHeaderPath)
        bridgingHeaderPath = possibleBridgingHeaderPaths[0]; // Use the first possibility as default
    try {
        let bridgingHeaderContent = '';
        // Read existing content if file exists
        if (fs.existsSync(bridgingHeaderPath))
            bridgingHeaderContent = fs.readFileSync(bridgingHeaderPath, 'utf8');
        // Add our import if not already present
        const importStatement = '#import <RNBackgroundDownloader.h>';
        if (!bridgingHeaderContent.includes(importStatement)) {
            // Add import with proper formatting
            bridgingHeaderContent = bridgingHeaderContent.trim();
            if (bridgingHeaderContent.length > 0)
                bridgingHeaderContent += '\n';
            bridgingHeaderContent += importStatement + '\n';
            // Write the updated bridging header
            fs.writeFileSync(bridgingHeaderPath, bridgingHeaderContent, 'utf8');
        }
    }
    catch (error) {
        console.warn('Could not modify bridging header:', error);
    }
}
exports.default = withRNBackgroundDownloader;

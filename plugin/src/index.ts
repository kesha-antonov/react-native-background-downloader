import { type ConfigPlugin, withAppDelegate } from '@expo/config-plugins';

const withRNBackgroundDownloader: ConfigPlugin = (_config) => {
  // Handle AppDelegate modifications
  _config = withAppDelegate(_config, (config) => {
    if (config.modResults.language === 'objc') {
      // For Objective-C AppDelegate.m (React Native < 0.77)
      config.modResults.contents = addObjCSupport(config.modResults.contents);
    } else {
      // For Swift AppDelegate.swift (React Native >= 0.77)
      config.modResults.contents = addSwiftSupport(config.modResults.contents);
    }
    return config;
  });

  return _config;
};

function addObjCSupport(appDelegateContents: string): string {
  // Add import if not already present
  if (!appDelegateContents.includes('#import <RNBackgroundDownloader.h>')) {
    // Add import after existing React imports
    const reactImportRegex = /#import <React\/.*?\.h>/g;
    const matches = appDelegateContents.match(reactImportRegex);

    if (matches) {
      // Add after the last React import
      const lastReactImport = matches[matches.length - 1];
      if (lastReactImport) {
        const lastImportIndex =
          appDelegateContents.lastIndexOf(lastReactImport);
        const importPosition = lastImportIndex + lastReactImport.length;

        appDelegateContents =
          appDelegateContents.slice(0, importPosition) +
          '\n#import <RNBackgroundDownloader.h>' +
          appDelegateContents.slice(importPosition);
      }
    } else {
      // If no React imports found, add after the first import
      const firstImportMatch = appDelegateContents.match(/#import .*?\.h/);
      if (firstImportMatch) {
        const importPosition =
          appDelegateContents.indexOf(firstImportMatch[0]) +
          firstImportMatch[0].length;
        appDelegateContents =
          appDelegateContents.slice(0, importPosition) +
          '\n#import <RNBackgroundDownloader.h>' +
          appDelegateContents.slice(importPosition);
      }
    }
  }

  return appDelegateContents;
}

function addSwiftSupport(appDelegateContents: string): string {
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
      if (lastMatch) {
        const insertPosition = lastMatch.index!;

        appDelegateContents =
          appDelegateContents.slice(0, insertPosition) +
          methodToAdd +
          '\n' +
          appDelegateContents.slice(insertPosition);
      }
    }
  }

  return appDelegateContents;
}

export default withRNBackgroundDownloader;

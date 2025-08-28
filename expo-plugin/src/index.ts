import {
  ConfigPlugin,
  withAppDelegate,
  withXcodeProject,
  IOSConfig,
} from '@expo/config-plugins';

export interface BackgroundDownloaderPluginOptions {
  // Optional: specify if the project uses Swift (defaults to auto-detection)
  useSwift?: boolean;
}

const IMPORT_OBJC = '#import <RNBackgroundDownloader.h>';
const IMPORT_SWIFT = '#import <RNBackgroundDownloader.h>';

// Method for Objective-C AppDelegate
const BACKGROUND_SESSION_METHOD_OBJC = `
- (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
{
  [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
}`;

// Method for Swift AppDelegate  
const BACKGROUND_SESSION_METHOD_SWIFT = `
  func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    RNBackgroundDownloader.setCompletionHandlerWithIdentifier(identifier, completionHandler: completionHandler)
  }`;

const withRNBackgroundDownloaderIOS: ConfigPlugin<BackgroundDownloaderPluginOptions | undefined> = (
  config,
  options = {}
) => {
  return withAppDelegate(config, (config) => {
    if (config.modResults.language === 'objc') {
      // Handle Objective-C AppDelegate
      config.modResults.contents = addImportToAppDelegate(
        config.modResults.contents,
        IMPORT_OBJC
      );
      config.modResults.contents = addBackgroundSessionMethod(
        config.modResults.contents,
        BACKGROUND_SESSION_METHOD_OBJC,
        'objc'
      );
    } else if (config.modResults.language === 'swift') {
      // Handle Swift AppDelegate
      config.modResults.contents = addBackgroundSessionMethod(
        config.modResults.contents,
        BACKGROUND_SESSION_METHOD_SWIFT,
        'swift'
      );
    }
    
    return config;
  });
};

function addImportToAppDelegate(appDelegateContents: string, importStatement: string): string {
  // Check if import already exists
  if (appDelegateContents.includes(importStatement)) {
    return appDelegateContents;
  }

  // Add import after other imports
  const importPattern = /#import\s+["<][^">]+[">]/g;
  const matches = appDelegateContents.match(importPattern);
  
  if (matches && matches.length > 0) {
    const lastImport = matches[matches.length - 1];
    const lastImportIndex = appDelegateContents.lastIndexOf(lastImport);
    const insertIndex = lastImportIndex + lastImport.length;
    
    return (
      appDelegateContents.slice(0, insertIndex) +
      '\n' + importStatement +
      appDelegateContents.slice(insertIndex)
    );
  }

  // If no imports found, add at the top after the first line
  const lines = appDelegateContents.split('\n');
  lines.splice(1, 0, importStatement);
  return lines.join('\n');
}

function addBackgroundSessionMethod(
  appDelegateContents: string, 
  methodCode: string,
  language: 'objc' | 'swift'
): string {
  // Check if method already exists
  const methodSignature = language === 'objc' 
    ? 'handleEventsForBackgroundURLSession:(NSString *)identifier'
    : 'handleEventsForBackgroundURLSession identifier: String';
    
  if (appDelegateContents.includes(methodSignature)) {
    return appDelegateContents;
  }

  if (language === 'objc') {
    // For Objective-C, add before the final @end
    const endPattern = /\n@end\s*$/;
    if (endPattern.test(appDelegateContents)) {
      return appDelegateContents.replace(endPattern, '\n' + methodCode + '\n\n@end');
    }
  } else if (language === 'swift') {
    // For Swift, add before the final closing brace of the AppDelegate class
    // Find the AppDelegate class and add the method before its closing brace
    const classPattern = /class\s+AppDelegate[^{]*{/;
    const classMatch = appDelegateContents.match(classPattern);
    
    if (classMatch) {
      // Find the matching closing brace for the class
      const classStartIndex = appDelegateContents.indexOf(classMatch[0]) + classMatch[0].length;
      let braceCount = 1;
      let index = classStartIndex;
      
      while (index < appDelegateContents.length && braceCount > 0) {
        if (appDelegateContents[index] === '{') {
          braceCount++;
        } else if (appDelegateContents[index] === '}') {
          braceCount--;
        }
        index++;
      }
      
      if (braceCount === 0) {
        // Found the closing brace, insert method before it
        const insertIndex = index - 1;
        return (
          appDelegateContents.slice(0, insertIndex) +
          methodCode + '\n' +
          appDelegateContents.slice(insertIndex)
        );
      }
    }
  }

  return appDelegateContents;
}

/**
 * Expo config plugin for React Native Background Downloader.
 * Automatically adds the required iOS AppDelegate code to handle background URL sessions.
 */
const withRNBackgroundDownloader: ConfigPlugin<BackgroundDownloaderPluginOptions | undefined> = (
  config,
  options
) => {
  config = withRNBackgroundDownloaderIOS(config, options);
  return config;
};

export default withRNBackgroundDownloader;
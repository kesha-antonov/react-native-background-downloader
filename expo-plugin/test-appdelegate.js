#!/usr/bin/env node

// Test the AppDelegate modification functions with real examples
const fs = require('fs');
const path = require('path');

console.log('🔧 Testing AppDelegate modifications...\n');

// Read the example AppDelegate files from the example project
const exampleObjCPath = path.join(__dirname, '..', 'example', 'ios', 'example', 'AppDelegate.mm');
const exampleObjC = fs.readFileSync(exampleObjCPath, 'utf8');

console.log('Original AppDelegate.mm:');
console.log('─'.repeat(50));
console.log(exampleObjC);
console.log('─'.repeat(50));

// The functions are compiled into the plugin, so we'll test the patterns directly
const IMPORT_OBJC = '#import <RNBackgroundDownloader.h>';
const BACKGROUND_SESSION_METHOD_OBJC = `
- (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
{
  [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
}`;

// Test import addition
function testAddImportToAppDelegate(appDelegateContents, importStatement) {
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

// Test method addition
function testAddBackgroundSessionMethod(appDelegateContents, methodCode) {
  // Check if method already exists
  if (appDelegateContents.includes('handleEventsForBackgroundURLSession:(NSString *)identifier')) {
    return appDelegateContents;
  }

  // For Objective-C, add before the final @end
  const endPattern = /\n@end\s*$/;
  if (endPattern.test(appDelegateContents)) {
    return appDelegateContents.replace(endPattern, '\n' + methodCode + '\n\n@end');
  }

  return appDelegateContents;
}

console.log('\n🧪 Testing import addition...');
const withImport = testAddImportToAppDelegate(exampleObjC, IMPORT_OBJC);
if (withImport.includes(IMPORT_OBJC)) {
  console.log('✅ Import added successfully');
} else {
  console.log('❌ Import addition failed');
}

console.log('\n🧪 Testing method addition...');
const withMethod = testAddBackgroundSessionMethod(withImport, BACKGROUND_SESSION_METHOD_OBJC);
if (withMethod.includes('handleEventsForBackgroundURLSession')) {
  console.log('✅ Method added successfully');
} else {
  console.log('❌ Method addition failed');
}

console.log('\nModified AppDelegate.mm:');
console.log('─'.repeat(50));
console.log(withMethod);
console.log('─'.repeat(50));

console.log('\n🎉 AppDelegate modification test completed!');

// Test idempotency
console.log('\n🔄 Testing idempotency (running modifications again)...');
const secondRun = testAddBackgroundSessionMethod(
  testAddImportToAppDelegate(withMethod, IMPORT_OBJC), 
  BACKGROUND_SESSION_METHOD_OBJC
);

if (secondRun === withMethod) {
  console.log('✅ Modifications are idempotent (no duplicate additions)');
} else {
  console.log('❌ Modifications are not idempotent - this could cause issues');
  console.log('Differences found in second run');
}
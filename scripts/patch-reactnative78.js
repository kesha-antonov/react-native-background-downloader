#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

console.log('🔧 Applying RNBackgroundDownloader fix for React Native 0.78+...');

// Multiple possible paths for different package versions
const possiblePaths = [
  // @kesha-antonov/react-native-background-downloader
  path.join(__dirname, '..', 'node_modules', '@kesha-antonov', 'react-native-background-downloader', 'ios', 'RNBackgroundDownloader.m'),
  // react-native-background-downloader (if using different package)
  path.join(__dirname, '..', 'node_modules', 'react-native-background-downloader', 'ios', 'RNBackgroundDownloader.m'),
  // Alternative path structure
  path.join(__dirname, '..', 'node_modules', '@kesha-antonov', 'react-native-background-downloader', 'ios', 'RNBackgroundDownloader', 'RNBackgroundDownloader.m')
];

// Find the correct path
let rnBackgroundDownloaderPath = null;
for (const possiblePath of possiblePaths) {
  if (fs.existsSync(possiblePath)) {
    rnBackgroundDownloaderPath = possiblePath;
    break;
  }
}

// Check if the file exists
if (!rnBackgroundDownloaderPath) {
  console.log('❌ RNBackgroundDownloader.m file not found. Searched paths:');
  possiblePaths.forEach(p => console.log(`   - ${p}`));
  console.log('ℹ️ This is normal if the package is not installed yet. The fix will be applied on next yarn install.');
  process.exit(0);
}

console.log('📁 Found RNBackgroundDownloader.m at:', rnBackgroundDownloaderPath);

try {
  // Read the file
  let content = fs.readFileSync(rnBackgroundDownloaderPath, 'utf8');
  
  // Check if already patched
  if (content.includes('// PATCHED: Bridge check removed')) {
    console.log('✅ RNBackgroundDownloader is already patched');
    process.exit(0);
  }

  // More precise patterns to catch the exact bridge checks
  const bridgeCheckPatterns = [
    // Pattern 1: if (self.bridge && isJavascriptLoaded)
    /if\s*\(\s*self\.bridge\s*&&\s*isJavascriptLoaded\s*\)/g,
    // Pattern 2: if (self.bridge && self.bridge.isJavascriptLoaded)
    /if\s*\(\s*self\.bridge\s*&&\s*self\.bridge\.isJavascriptLoaded\s*\)/g,
    // Pattern 3: if (bridge && isJavascriptLoaded)
    /if\s*\(\s*bridge\s*&&\s*isJavascriptLoaded\s*\)/g,
    // Pattern 4: if (bridge && bridge.isJavascriptLoaded)
    /if\s*\(\s*bridge\s*&&\s*bridge\.isJavascriptLoaded\s*\)/g
  ];

  let patchCount = 0;
  
  // Apply patches more carefully
  bridgeCheckPatterns.forEach((pattern, index) => {
    const matches = content.match(pattern);
    if (matches) {
      console.log(`🔍 Found ${matches.length} bridge check(s) to patch (pattern ${index + 1})`);
      
      content = content.replace(pattern, (match) => {
        patchCount++;
        // Replace bridge check with a safe emitter check
        return match.replace(
          /self\.bridge\s*&&\s*isJavascriptLoaded|self\.bridge\s*&&\s*self\.bridge\.isJavascriptLoaded|bridge\s*&&\s*isJavascriptLoaded|bridge\s*&&\s*bridge\.isJavascriptLoaded/,
          'self != nil'
        );
     });
    }
  });

  if (patchCount > 0) {
    // Write the patched content back
    fs.writeFileSync(rnBackgroundDownloaderPath, content, 'utf8');
    console.log(`✅ Successfully patched ${patchCount} bridge check(s) in RNBackgroundDownloader.m`);
    console.log('📝 The fix removes problematic bridge checks that prevent callbacks from working in RN 0.78+');
    console.log('🔄 You may need to clean and rebuild your iOS project for the changes to take effect.');
  } else {
    console.log('ℹ️ No bridge checks found to patch. The file might already be compatible or have a different structure.');
    console.log('📋 This could mean:');
    console.log('   - The package version is already fixed');
    console.log('   - The file structure is different than expected');
    console.log('   - The bridge checks use a different pattern');
  }

} catch (error) {
  console.error('❌ Error applying RNBackgroundDownloader fix:', error.message);
  console.error('Stack trace:', error.stack);
  process.exit(1);
}

console.log('🎉 RNBackgroundDownloader fix process completed!');

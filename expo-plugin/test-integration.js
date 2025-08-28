#!/usr/bin/env node

// Simple integration test for the plugin functions
const fs = require('fs');
const path = require('path');

// Import the built plugin
const pluginPath = path.join(__dirname, '..', 'plugin.js');
const plugin = require(pluginPath);

console.log('🧪 Testing React Native Background Downloader Expo Plugin\n');

// Test 1: Basic plugin export
console.log('1. Testing plugin export...');
if (typeof plugin.default === 'function') {
  console.log('   ✅ Plugin exports a function');
} else {
  console.log('   ❌ Plugin should export a function');
  process.exit(1);
}

// Test 2: Plugin execution
console.log('\n2. Testing plugin execution...');
try {
  const result = plugin.default({}, {});
  if (typeof result === 'object') {
    console.log('   ✅ Plugin returns a config object');
  } else {
    console.log('   ❌ Plugin should return a config object');
    process.exit(1);
  }
} catch (error) {
  console.log('   ❌ Plugin execution failed:', error.message);
  process.exit(1);
}

// Test 3: String manipulation functions (by reading the built file)
console.log('\n3. Testing string manipulation functions...');
const builtPluginPath = path.join(__dirname, '..', 'expo-plugin', 'build', 'index.js');
const builtPlugin = fs.readFileSync(builtPluginPath, 'utf8');

const expectedFunctions = [
  'addImportToAppDelegate',
  'addBackgroundSessionMethod',
  'BACKGROUND_SESSION_METHOD_OBJC',
  'BACKGROUND_SESSION_METHOD_SWIFT'
];

let allFunctionsPresent = true;
expectedFunctions.forEach(funcName => {
  if (builtPlugin.includes(funcName)) {
    console.log(`   ✅ ${funcName} found`);
  } else {
    console.log(`   ❌ ${funcName} missing`);
    allFunctionsPresent = false;
  }
});

if (!allFunctionsPresent) {
  process.exit(1);
}

console.log('\n🎉 All tests passed! The plugin is ready to use.');
console.log('\n📚 Usage:');
console.log('   Add to your app.config.js:');
console.log('   plugins: ["@kesha-antonov/react-native-background-downloader"]');
console.log('\n   Then run: expo prebuild');
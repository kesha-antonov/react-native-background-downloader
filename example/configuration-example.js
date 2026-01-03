/**
 * Example: Using Configuration Options for Download Control
 * 
 * This example demonstrates how to use the new configuration options
 * to control parallel downloads and cellular data usage.
 */

import { setConfig, createDownloadTask, directories } from '@kesha-antonov/react-native-background-downloader'

// Example 1: Configure parallel downloads (iOS only)
// Set the maximum number of simultaneous downloads per host
setConfig({
  maxParallelDownloads: 8  // Default is 4
})

// Example 2: Restrict downloads to WiFi only
// Useful for large files or to respect user data preferences
setConfig({
  allowsCellularAccess: false  // Only download over WiFi
})

// Example 3: Configure multiple settings at once
setConfig({
  maxParallelDownloads: 6,
  allowsCellularAccess: false,
  isLogsEnabled: true,  // Enable debug logging
  progressInterval: 2000  // Report progress every 2 seconds
})

// Example 4: Download with global WiFi-only setting
const task1 = createDownloadTask({
  id: 'large-file',
  url: 'https://example.com/large-file.zip',
  destination: `${directories.documents}/large-file.zip`,
})

task1
  .begin(({ expectedBytes }) => {
    console.log(`Starting download of ${expectedBytes} bytes`)
  })
  .progress(({ bytesDownloaded, bytesTotal }) => {
    const percent = (bytesDownloaded / bytesTotal) * 100
    console.log(`Progress: ${percent.toFixed(2)}%`)
  })
  .done(({ location }) => {
    console.log(`Download complete: ${location}`)
  })
  .error(({ error, errorCode }) => {
    console.error(`Download failed: ${error} (${errorCode})`)
  })

task1.start()

// Example 5: Override global cellular setting for a specific download (Android only)
// Even if global allowsCellularAccess is false, this download can use cellular
const task2 = createDownloadTask({
  id: 'urgent-file',
  url: 'https://example.com/urgent-file.pdf',
  destination: `${directories.documents}/urgent-file.pdf`,
  isAllowedOverMetered: true  // Override global setting - allow cellular for this download
})

task2.start()

// Example 6: Reset to default settings
setConfig({
  maxParallelDownloads: 4,  // iOS default
  allowsCellularAccess: true  // Allow cellular data
})

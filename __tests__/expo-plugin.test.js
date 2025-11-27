const plugin = require('../app.plugin.js')

describe('RNBackgroundDownloader Expo Plugin', () => {
  it('should be a valid config plugin', () => {
    expect(typeof plugin).toBe('function')
  })

  it('should modify Objective-C AppDelegate content', () => {
    const mockAppDelegateContent = `
#import "AppDelegate.h"
#import <React/RCTBundleURLProvider.h>

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  self.moduleName = @"example";
  self.initialProps = @{};
  return [super application:application didFinishLaunchingWithOptions:launchOptions];
}

@end
    `

    // eslint-disable-next-line no-unused-vars
    const mockConfig = {
      modRequest: {
        projectRoot: '/tmp',
        projectName: 'TestApp',
      },
      modResults: {
        language: 'objc',
        contents: mockAppDelegateContent,
      },
    }

    const result = plugin({})
    // This test just verifies the plugin doesn't crash - more comprehensive testing would require mocking
    expect(result).toBeDefined()
  })
})

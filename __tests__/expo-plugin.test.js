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

  it('should modify Swift AppDelegate content', () => {
    const mockSwiftAppDelegateContent = `
import Expo
import React
import ReactAppDependencyProvider

@UIApplicationMain
public class AppDelegate: ExpoAppDelegate {
  var window: UIWindow?

  public override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
    `

    // eslint-disable-next-line no-unused-vars
    const mockConfig = {
      modRequest: {
        projectRoot: '/tmp',
        projectName: 'TestApp',
      },
      modResults: {
        language: 'swift',
        contents: mockSwiftAppDelegateContent,
      },
    }

    const result = plugin({})
    // This test just verifies the plugin doesn't crash - more comprehensive testing would require mocking
    expect(result).toBeDefined()
  })
})

describe('addSwiftSupport', () => {
  const { addSwiftSupport } = require('../plugin/build/index.js')

  // The AppDelegate.swift from Expo's SDK 54 template
  const expoAppDelegate = `import Expo
import React
import ReactAppDependencyProvider

@UIApplicationMain
public class AppDelegate: ExpoAppDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ExpoReactNativeFactoryDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  public override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}

class ReactNativeDelegate: ExpoReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    bridge.bundleURL ?? bundleURL()
  }
}
`

  const appDelegateClass = contents =>
    contents.slice(contents.indexOf('class AppDelegate'), contents.indexOf('class ReactNativeDelegate'))

  it('adds the method to AppDelegate as an override, not to ReactNativeDelegate', () => {
    const result = addSwiftSupport(expoAppDelegate)
    expect(appDelegateClass(result)).toContain('public override func application(\n    _ application: UIApplication,\n    handleEventsForBackgroundURLSession')
    expect(result.slice(result.indexOf('class ReactNativeDelegate'))).not.toContain('handleEventsForBackgroundURLSession')
  })

  it('moves a copy that an older version put in ReactNativeDelegate', () => {
    const misplaced = expoAppDelegate.replace(/}\n$/, `
  func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    RNBackgroundDownloader.setCompletionHandlerWithIdentifier(identifier, completionHandler: completionHandler)
  }

}
`)
    const result = addSwiftSupport(misplaced)
    expect(result.match(/handleEventsForBackgroundURLSession identifier/g)).toHaveLength(1)
    expect(appDelegateClass(result)).toContain('handleEventsForBackgroundURLSession')
  })

  it('is idempotent', () => {
    const once = addSwiftSupport(expoAppDelegate)
    expect(addSwiftSupport(once)).toBe(once)
  })

  it('leaves an implementation the app wrote itself alone', () => {
    const custom = expoAppDelegate.replace('  var window: UIWindow?\n', `  var window: UIWindow?

  public override func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    MyHandler.handle(identifier, completionHandler)
  }
`)
    expect(addSwiftSupport(custom)).toBe(custom)
  })

  it('uses a plain func when AppDelegate does not subclass ExpoAppDelegate', () => {
    const plain = 'class AppDelegate: UIResponder, UIApplicationDelegate {\n  var window: UIWindow?\n}\n'
    const result = addSwiftSupport(plain)
    expect(result).toContain('\n  func application(\n')
    expect(result).not.toContain('override')
  })

  it('leaves the contents unchanged when there is no AppDelegate class', () => {
    const contents = 'class Something {\n}\n'
    expect(addSwiftSupport(contents)).toBe(contents)
  })
})

import { ConfigPlugin } from '@expo/config-plugins';
interface PluginOptions {
    /**
     * Options for the MMKV dependency on Android.
     * Pass a string to specify the version, or an object with version property.
     * @default '1.3.16'
     * @example
     * // Use default version
     * ["@kesha-antonov/react-native-background-downloader"]
     * // Specify version
     * ["@kesha-antonov/react-native-background-downloader", { mmkvVersion: "1.3.16" }]
     */
    mmkvVersion?: string;
    /**
     * Skip adding MMKV dependency on Android.
     * Set to true if you're using react-native-mmkv or another library that provides MMKV.
     * This prevents duplicate class errors.
     * @default false
     * @example
     * ["@kesha-antonov/react-native-background-downloader", { skipMmkvDependency: true }]
     */
    skipMmkvDependency?: boolean;
}
declare const withRNBackgroundDownloader: ConfigPlugin<PluginOptions | void>;
export default withRNBackgroundDownloader;

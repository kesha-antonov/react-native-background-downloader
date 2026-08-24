let loggingEnabled = false

export const configureLogging = (enabled: boolean): void => {
  loggingEnabled = enabled
}

export const log = (...args: unknown[]): void => {
  if (loggingEnabled)
    console.log('[RNBackgroundDownloader]', ...args)
}

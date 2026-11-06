import React from 'react'
import BrowserOnly from '@docusaurus/BrowserOnly'
import { useColorMode } from '@docusaurus/theme-common'

// The animated overview on the intro page: a download that keeps going while
// the app is suspended and killed, and is found again after the relaunch.
function Themed() {
  const { colorMode } = useColorMode()
  const Demo = require('./Demo').default
  return <Demo theme={colorMode === 'dark' ? 'dark' : 'light'} />
}

export default function BackgroundDemo() {
  return (
    <figure
      className="background-demo"
      role="img"
      aria-label="A map download starts in the app, keeps going after the phone locks and iOS terminates the app, and is found again with getExistingDownloadTasks when the app reopens."
    >
      <BrowserOnly fallback={<div className="background-demo__placeholder" />}>{() => <Themed />}</BrowserOnly>
    </figure>
  )
}

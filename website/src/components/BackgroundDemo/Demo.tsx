import React from 'react'
import { ArrowDown, Check, Map as MapGlyph } from 'lucide-react'
import { Bar, Code, Count, LockScreen, Notification, Phone, Row, Stage, States, Window, WALLPAPER, type Act } from '../stageshow/kit'
import './bgd.css'

// react-native-background-downloader, what the system does: a map starts
// downloading in the app, the phone locks and iOS later kills the app, and
// the transfer keeps going without it. When it finishes iOS relaunches the
// app in the background, done() posts a notification, and the tap opens the
// app with the map already there, found again by getExistingDownloadTasks().
//
// Wide: a device pair. The editor holds the four calls and a device log; the
// phone straddles its right edge over empty editor margin. The highlighted
// line is the call that is acting. Compact: the log as a card over the phone.
//
// Real strings: the code, the log lines, Offline maps, Spain, Portugal and
// their sizes, the notification. Everything else is a bar.

type Row = 'idle' | 'running' | 'done'

type S = {
  /** The app is on screen; off, the lock screen shows */
  app: boolean
  row: Row
  /** Progress the app shows */
  pct: number
  /** Progress iOS reports in the log while the app is gone */
  os: number
  /** The highlighted code line, 1-based; 0 for none */
  line: number
  /** Log lines shown */
  log: number
  notif: boolean
}

const START: S = { app: true, row: 'idle', pct: 0, os: 0, line: 0, log: 0, notif: false }

const CODE = [
  'createDownloadTask({ url, destination })',
  '  .progress(p => setProgress(p))',
  '  .done(() => notify(\'Portugal is ready\'))',
  '  .start()',
  '',
  '// After a relaunch, even if iOS killed the app',
  'const tasks = await getExistingDownloadTasks()',
]

const LINE_H = 20

function Log ({ s }: { s: S }) {
  const lines: [string, React.ReactNode][] = [
    ['app', 'launched'],
    ['app', 'download started'],
    ['app', 'suspended'],
    [
      'iOS',
      <>
        portugal.map{' '}
        <span className="bgd-pct">
          <Count value={s.os} duration={500} />
        </span>
        %
      </>,
    ],
    ['app', 'terminated by iOS'],
    ['iOS', 'done, relaunching the app'],
    ['app', 'restored 1 task'],
  ]
  return (
    <div className="bgd-log">
      {lines.map(([who, text], i) => (
        <div key={i} className="bgd-log__line" data-on={i <= s.log || undefined} style={{ transitionDelay: i <= s.log ? '0ms' : `${(lines.length - 1 - i) * 60}ms` }}>
          <span className="bgd-log__who" data-os={who === 'iOS' || undefined}>
            {who}
          </span>
          <span className="bgd-log__text">{text}</span>
        </div>
      ))}
    </div>
  )
}

function Ring ({ pct }: { pct: number }) {
  const r = 13
  const c = 2 * Math.PI * r
  return (
    <svg className="bgd-ring" viewBox="0 0 32 32" width="32" height="32" aria-hidden>
      <circle cx="16" cy="16" r={r} className="bgd-ring__track" />
      <circle cx="16" cy="16" r={r} className="bgd-ring__arc" strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} />
      <rect x="12" y="12" width="8" height="8" rx="1.5" className="bgd-ring__stop" />
    </svg>
  )
}

function App ({ s }: { s: S }) {
  return (
    <div className="bgd-app" data-on={s.app || undefined}>
      <div className="bgd-app__title">Offline maps</div>
      <div className="bgd-app__sub">
        <Bar w={132} h={9} r={4} tone={2} />
      </div>
      <div className="bgd-list">
        <Row lead={<span className="bgd-thumb bgd-thumb--es" />} title="Spain" subtitle="1.2 GB" className="bgd-row">
          <span className="bgd-trail">
            <span className="bgd-saved">
              <Check size={18} strokeWidth={2.4} />
            </span>
          </span>
        </Row>
        <Row lead={<span className="bgd-thumb bgd-thumb--pt" />} title="Portugal" subtitle="1.8 GB" className="bgd-row">
          <span className="bgd-trail" data-target="download">
            <States
              value={s.row}
              states={{
                idle: (
                  <span className="bgd-get">
                    <ArrowDown size={18} strokeWidth={2.4} />
                  </span>
                ),
                running: <Ring pct={s.pct} />,
                done: (
                  <span className="bgd-saved">
                    <Check size={18} strokeWidth={2.4} />
                  </span>
                ),
              }}
            />
          </span>
        </Row>
        {[
          ['bgd-thumb--a', 74, 40],
          ['bgd-thumb--b', 58, 46],
          ['bgd-thumb--c', 88, 36],
          ['bgd-thumb--d', 66, 42],
        ].map(([tone, t, sub], i) => (
          <Row key={i} lead={<span className={`bgd-thumb ${tone}`} />} title={<Bar w={t as number} h={11} r={4} tone={3} />} subtitle={<Bar w={sub as number} h={9} r={4} tone={2} />} className="bgd-row" />
        ))}
      </div>
    </div>
  )
}

async function script (a: Act<S>) {
  await a.wait(1200)

  // Start: the button in the row, the start() line, the first log line
  a.mark('start')
  await a.click('download')
  await a.set({ row: 'running', line: 4, log: 1, pct: 2 })
  await a.wait(500)
  await a.set({ line: 2 })
  for (const pct of [9, 17, 24, 31, 38]) {
    await a.wait(380)
    a.put({ pct })
  }
  await a.wait(500)

  // The phone locks: the app is suspended, iOS keeps the transfer going
  a.mark('lock')
  await a.set({ app: false, line: 0, log: 2 })
  await a.wait(600)
  await a.set({ os: 38, log: 3 })
  await a.wait(400)
  a.put({ os: 51 })
  await a.wait(900)

  // iOS kills the app to free memory; the transfer doesn't notice
  a.mark('killed')
  await a.set({ log: 4, os: 63 })
  for (const os of [76, 88, 100]) {
    await a.wait(800)
    a.put({ os })
  }
  await a.wait(500)

  // Done: iOS relaunches the app in the background and done() fires
  a.mark('done')
  await a.set({ log: 5, line: 3, row: 'done', pct: 100 })
  await a.wait(500)
  await a.set({ notif: true })
  await a.wait(1600)

  // The tap opens the app, the map is there, the task found again
  a.mark('open')
  await a.click('notif')
  await a.set({ app: true, notif: false, line: 7, log: 6 })
  await a.wait(2400)

  // Leave the way the phone leaves: it locks, and the next session starts
  a.mark('reset')
  await a.set({ log: 0, line: 0 })
  await a.wait(500)
  await a.set({ app: false })
  await a.wait(700)
  await a.snap({ row: 'idle', pct: 0, os: 0 })
  await a.set({ app: true })
  await a.move({ x: a.layout === 'compact' ? 300 : 700, y: 330 })
  await a.wait(400)
}

export default function BackgroundDownload ({ theme = 'light' }: { theme?: 'light' | 'dark' }) {
  return (
    <Stage<S>
      width={800}
      height={460}
      compact={{ below: 640, width: 358, height: 600 }}
      initial={START}
      poster={{ ...START, app: false, row: 'done', pct: 100, os: 100, line: 3, log: 5, notif: true }}
      script={script}
      theme={theme}
      fill
      cursor={{ at: { wide: { x: 700, y: 330 }, compact: { x: 300, y: 330 } }, style: 'touch' }}
      className="bgd"
    >
      {(s, _rect, layout) => (
        <>
          {layout === 'wide' && (
            <Window title="Downloads.tsx" className="bgd-window">
              <div className="bgd-editor">
                <span className="bgd-hl" data-on={s.line > 0 || undefined} style={{ top: 14 + (Math.max(s.line, 1) - 1) * LINE_H }} />
                <Code lines={CODE} className="bgd-code" />
              </div>
              <div className="bgd-console">
                <div className="bgd-console__head">Device log</div>
                <Log s={s} />
              </div>
            </Window>
          )}

          {layout === 'compact' && (
            <div className="bgd-card">
              <div className="bgd-console__head">Device log</div>
              <Log s={s} />
            </div>
          )}

          <Phone width={layout === 'compact' ? 326 : 284} dark={!s.app} wallpaper={WALLPAPER} className="bgd-phone">
            <LockScreen size="satellite" stack="top" className="bgd-lock" data-away={s.app || undefined}>
              <div className="bgd-notif" data-on={s.notif || undefined} data-target="notif">
                <Notification icon={<span className="bgd-icon"><MapGlyph size={20} strokeWidth={2.2} /></span>} title="Portugal is ready" body="The map is downloaded and works offline." />
              </div>
            </LockScreen>
            <App s={s} />
          </Phone>
        </>
      )}
    </Stage>
  )
}

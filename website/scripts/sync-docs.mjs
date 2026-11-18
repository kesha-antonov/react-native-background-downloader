// Generates the Docusaurus docs from the markdown that already lives in the
// repository root, so README.md / docs/*.md / MIGRATION.md stay the single
// source of truth and the site can never drift away from them.
//
// Run via `yarn sync` (also runs automatically before `start` and `build`).

import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..', '..')
const OUT = join(HERE, '..', 'docs')

const REPO = 'https://github.com/kesha-antonov/react-native-background-downloader'

/**
 * Canonical key for a heading or a link anchor.
 *
 * GitHub and Docusaurus slugify headings slightly differently (emoji and
 * variation selectors are the painful part), so instead of reimplementing
 * either algorithm we reduce both sides to letters and digits only. That is
 * stable enough to match `#-features` against `## ✨ Features`, and we then
 * emit explicit `{#key}` anchors so the generated site uses these keys too.
 */
const key = (text) => text.toLowerCase().replace(/[^a-z0-9]/g, '')

/**
 * Pages, in sidebar order. `from` is either README sections or a whole file.
 *
 * `title` is the heading a reader sees. The browser and search title is
 * "title | site name" unless `searchTitle` replaces it, which keeps the long
 * ones under 60 characters and lets the landing page say what the library does.
 */
const PAGES = [
  {
    id: 'intro',
    slug: '/',
    title: 'React Native Background Downloader',
    searchTitle: 'React Native background download and upload of large files',
    sidebarLabel: 'Introduction',
    description:
      'Download and upload large files in React Native and Expo apps on iOS and Android, continuing while the app is backgrounded or terminated by the OS.',
    keywords: ['react native background download', 'expo background download', 'react native download large file'],
    sections: ['✨ Features', '💡 Why?', '💡 Use Cases'],
  },
  {
    id: 'comparison',
    title: 'Comparison with other libraries',
    searchTitle: 'React Native background download libraries compared',
    sidebarLabel: 'Comparison',
    description:
      'How background transfers in this library compare with expo-file-system, react-native-blob-util and react-native-fs, and which of them survive an app restart.',
    keywords: ['expo-file-system background download', 'react-native-blob-util vs', 'react native download in background'],
    sections: ['⚖️ Comparison'],
  },
  {
    id: 'installation',
    title: 'Installation',
    sidebarLabel: 'Installation',
    description:
      'Install the library in an Expo project with the config plugin, or in a bare React Native project on iOS and Android, including MMKV version selection.',
    keywords: ['expo config plugin download', 'react native background downloader install'],
    sections: ['📋 Requirements', '📦 Installation'],
  },
  {
    id: 'usage',
    title: 'Downloading files in the background',
    searchTitle: 'Usage: downloading files in the background in React Native',
    sidebarLabel: 'Usage',
    description:
      'Start a background download, track progress, handle completion and errors, and re-attach to downloads that were still running after your app restarted.',
    keywords: ['react native resume download after app killed', 'getExistingDownloadTasks', 'background download progress'],
    sections: ['🚀 Usage'],
  },
  {
    id: 'configuration',
    title: 'Advanced configuration',
    sidebarLabel: 'Configuration',
    description:
      'Configure parallel downloads, cellular and Wi-Fi restrictions, notifications, headers, progress intervals and other advanced options.',
    keywords: ['download only on wifi react native', 'max parallel downloads ios'],
    sections: ['⚙️ Advanced Configuration'],
  },
  {
    id: 'api',
    title: 'API reference',
    sidebarLabel: 'API reference',
    description:
      'Full API reference: download, upload, checkForExistingDownloads, task events, configuration options and TypeScript types.',
    keywords: ['react native background downloader api'],
    file: 'docs/API.md',
  },
  {
    id: 'platform-notes',
    title: 'Platform notes for iOS and Android',
    searchTitle: 'React Native background downloads: iOS and Android notes',
    sidebarLabel: 'Platform notes',
    description:
      'iOS and Android specifics: NSURLSession background sessions, DownloadManager, foreground services, Android 14 notification requirements and ProGuard rules.',
    keywords: ['nsurlsession background download', 'android downloadmanager react native', 'android 14 foreground service download'],
    file: 'docs/PLATFORM_NOTES.md',
  },
  {
    id: 'example-app',
    title: 'Example app',
    sidebarLabel: 'Example app',
    description: 'Run the bundled Expo example app to see background downloads and uploads working end to end.',
    keywords: ['react native background downloader example'],
    sections: ['🧪 Example App'],
  },
  {
    id: 'troubleshooting',
    title: 'Troubleshooting',
    sidebarLabel: 'Troubleshooting',
    description:
      'Fixes for the most common problems: downloads not resuming, empty task lists after a restart, build failures and missing native setup steps.',
    keywords: ['getExistingDownloadTasks returns empty', 'react native background download not working'],
    sections: ['❓ Troubleshooting'],
  },
  {
    id: 'migration',
    title: 'Migration guide',
    sidebarLabel: 'Migration guide',
    description: 'Upgrade between major versions, with the breaking changes and the code changes each one requires.',
    keywords: ['react native background downloader migration', 'upgrade v3 to v4'],
    file: 'MIGRATION.md',
  },
  {
    id: 'contributing',
    title: 'Contributing',
    sidebarLabel: 'Contributing',
    description: 'Set up the repository for local development, run the tests, and open a pull request.',
    keywords: ['contribute react native background downloader'],
    sections: ['🤝 Contributing', '👥 Authors', '📄 License'],
  },
]

const read = (relative) => readFileSync(join(ROOT, relative), 'utf8')

/** Pixel size of a PNG or GIF in the repository, read from the file header. */
function imageSize (relative) {
  const bytes = readFileSync(join(ROOT, relative))
  if (bytes.toString('latin1', 1, 4) === 'PNG') return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
  if (bytes.toString('latin1', 0, 3) === 'GIF') return { width: bytes.readUInt16LE(6), height: bytes.readUInt16LE(8) }
  throw new Error(`Cannot read the size of ${relative}. Update website/scripts/sync-docs.mjs.`)
}

/**
 * Gives repository images that only state a width the matching height, so the
 * browser reserves their space before they load.
 */
function addImageHeights (markdown) {
  return markdown.replace(/<img\b[^>]*>/g, (tag) => {
    if (/\sheight=/.test(tag)) return tag
    const src = /\ssrc="\.?\/?(docs\/images\/[^"]+)"/.exec(tag)
    const width = /\swidth="(\d+)"/.exec(tag)
    if (!src || !width) return tag
    const size = imageSize(src[1])
    const height = Math.round(Number(width[1]) * size.height / size.width)
    return tag.replace(width[0], `${width[0]} height="${height}"`)
  })
}

/**
 * Splits a markdown document into top-level (`##`) sections, ignoring headings
 * that appear inside fenced code blocks.
 */
function splitSections (markdown) {
  const lines = markdown.split('\n')
  const sections = new Map()
  let current = null
  let fenced = false

  for (const line of lines) {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced

    const heading = !fenced && /^## (.+?)\s*$/.exec(line)
    if (heading) {
      current = { title: heading[1], lines: [] }
      sections.set(current.title, current)
      continue
    }
    if (current) current.lines.push(line)
  }

  return sections
}

/** Collects every heading in a chunk of markdown, outside of code fences. */
function headingsIn (markdown) {
  const found = []
  let fenced = false
  for (const line of markdown.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced
    const m = !fenced && /^(#{1,6}) (.+?)\s*$/.exec(line)
    if (m) found.push(m[2])
  }
  return found
}

/** Drops a "## Table of Contents" section - Docusaurus renders its own. */
function stripTableOfContents (markdown) {
  return markdown.replace(/^##+ Table of Contents\s*$[\s\S]*?(?=^##? )/m, '')
}

/**
 * Renumbers headings so the page outline starts at h2 and never skips a level,
 * whatever levels the source used, and gives each an explicit `{#key}` anchor
 * so that generated links resolve to a slug we control rather than one
 * inferred from emoji.
 */
function normaliseHeadings (markdown) {
  let fenced = false
  // Source levels of the headings the current line is nested under.
  const open = []
  return markdown
    .split('\n')
    .map((line) => {
      if (/^\s*(```|~~~)/.test(line)) {
        fenced = !fenced
        return line
      }
      if (fenced) return line

      const m = /^(#{1,6}) (.+?)\s*$/.exec(line)
      if (!m) return line

      while (open.length && open[open.length - 1] >= m[1].length) open.pop()
      open.push(m[1].length)
      const level = Math.min(6, open.length + 1)
      if (/\{#[^}]+\}\s*$/.test(m[2])) return `${'#'.repeat(level)} ${m[2]}`

      return `${'#'.repeat(level)} ${m[2]} {#${key(m[2])}}`
    })
    .join('\n')
}

// Build the anchor -> page map before rendering, so cross-page links resolve.
const readme = read('README.md')
const readmeSections = splitSections(readme)
const anchorToPage = new Map()

for (const page of PAGES) {
  const bodies = page.sections
    ? page.sections.map((title) => {
      const section = readmeSections.get(title)
      if (!section) throw new Error(`README section not found: "${title}". Update website/scripts/sync-docs.mjs.`)
      return `## ${section.title}\n${section.lines.join('\n')}`
    })
    : [read(page.file)]

  page.raw = bodies.join('\n\n')

  // When a page is built from a single README section, that section's own
  // heading just repeats the page title, so it is dropped and its subheadings
  // are promoted one level. Links that pointed at it become links to the page.
  page.droppedHeading = page.sections?.length === 1 ? key(page.sections[0]) : null

  for (const heading of headingsIn(page.raw))
    if (!anchorToPage.has(key(heading))) anchorToPage.set(key(heading), page.id)
}

/** Anchors that no longer exist because their heading became the page title. */
const droppedAnchors = new Map(
  PAGES.filter((page) => page.droppedHeading).map((page) => [page.droppedHeading, page.id])
)

/**
 * Resolves a canonical anchor key to a markdown link, or null if it is unknown.
 * Anchors whose heading was dropped resolve to the page itself.
 */
function linkTo (k, pageId) {
  const droppedOn = droppedAnchors.get(k)
  if (droppedOn) return `](./${droppedOn}.md)`

  const target = anchorToPage.get(k)
  if (!target) return null
  return target === pageId ? `](#${k})` : `](./${target}.md#${k})`
}

/** Rewrites README/GitHub-relative links to links between generated pages. */
function rewriteLinks (markdown, pageId) {
  const fileRoutes = [
    [/\]\(\.?\/?docs\/API\.md(#[^)]*)?\)/g, 'api'],
    [/\]\(\.?\/?docs\/PLATFORM_NOTES\.md(#[^)]*)?\)/g, 'platform-notes'],
    [/\]\(\.?\.?\/?MIGRATION\.md(#[^)]*)?\)/g, 'migration'],
  ]

  let out = markdown

  for (const [pattern, target] of fileRoutes)
    out = out.replace(pattern, (_match, hash) => {
      const anchor = hash ? `#${key(hash.slice(1))}` : ''
      return `](./${target}.md${anchor})`
    })

  // Links back into README.md from the standalone docs files.
  out = out.replace(/\]\(\.\.?\/?README\.md(#[^)]*)?\)/g, (_match, hash) => {
    if (!hash) return '](./intro.md)'
    return linkTo(key(hash.slice(1)), pageId) ?? '](./intro.md)'
  })

  // Plain in-page anchors, which may now live on a different page.
  out = out.replace(/\]\(#([^)]+)\)/g, (match, anchor) => linkTo(key(anchor), pageId) ?? match)

  // Repository files that have no page of their own.
  out = out.replace(/\]\(\.?\/?(LICENSE|CHANGELOG\.md)\)/g, `](${REPO}/blob/main/$1)`)
  out = out.replace(/\]\(\.?\/?example\/?\)/g, `](${REPO}/tree/main/example)`)
  // Images live in the repository, not the site: point them at the raw files.
  out = out.replace(/src="\.?\/?(docs\/images\/[^"]+)"/g, 'src="https://raw.githubusercontent.com/kesha-antonov/react-native-background-downloader/main/$1"')

  return out
}

function frontmatter (page, index) {
  const fields = [
    `title: ${JSON.stringify(page.title)}`,
    `sidebar_label: ${JSON.stringify(page.sidebarLabel)}`,
    `sidebar_position: ${index + 1}`,
    `description: ${JSON.stringify(page.description)}`,
  ]
  // "Edit this page" opens the file the page is generated from.
  fields.push(`custom_edit_url: ${JSON.stringify(`${REPO}/edit/main/${page.file ?? 'README.md'}`)}`)
  // Read by src/theme/DocItem/Metadata, which puts it in the title element.
  if (page.searchTitle) fields.push(`search_title: ${JSON.stringify(page.searchTitle)}`)
  if (page.slug) fields.push(`slug: ${JSON.stringify(page.slug)}`)
  if (page.keywords?.length) fields.push(`keywords: [${page.keywords.map((k) => JSON.stringify(k)).join(', ')}]`)
  return `---\n${fields.join('\n')}\n---\n`
}

/**
 * Opening of the landing page: the one-line summary under the README title and
 * the install command. Both sit in parts of the README that no page copies.
 */
function landingLead () {
  const summary = /<h1[^>]*>[\s\S]*?<\/h1>\s*<p[^>]*>\s*([\s\S]*?)\s*<\/p>/.exec(readme)
  const install = /```bash\n([\s\S]*?)\n```/.exec(readmeSections.get('📦 Installation').lines.join('\n'))
  if (!summary || !install) throw new Error('README summary or install command not found. Update website/scripts/sync-docs.mjs.')

  return [
    summary[1],
    'Install the package:',
    `\`\`\`bash\n${install[1]}\n\`\`\``,
    'The Expo config plugin and the setup for bare React Native projects are covered in [Installation](./installation.md).',
  ].join('\n\n')
}

rmSync(OUT, { recursive: true, force: true })
mkdirSync(OUT, { recursive: true })

PAGES.forEach((page, index) => {
  let body = stripTableOfContents(page.raw)
  // Standalone files lead with an `# H1` that the frontmatter title replaces.
  body = body.replace(/^#\s+.+?\n/, '')

  // Same for a single README section: its `##` heading is the page title.
  if (page.droppedHeading) body = body.replace(/^##\s+.+?\n/, '')

  body = normaliseHeadings(body)
  body = addImageHeights(body)
  body = rewriteLinks(body, page.id)
  if (page.id === 'intro') body = `${landingLead()}\n\n${body}`

  const source = page.file ?? 'README.md'
  const note = `<!-- Generated from ${source} by website/scripts/sync-docs.mjs - edit that file, not this one. -->\n`

  writeFileSync(join(OUT, `${page.id}.md`), `${frontmatter(page, index)}\n${note}\n${body.trim()}\n`)
})

console.log(`Generated ${PAGES.length} pages into website/docs from README.md, docs/ and MIGRATION.md`)

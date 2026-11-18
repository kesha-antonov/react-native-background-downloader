// @ts-check
import { themes as prismThemes } from 'prism-react-renderer'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const REPO = 'https://github.com/kesha-antonov/react-native-background-downloader'
const NPM = 'https://www.npmjs.com/package/@kesha-antonov/react-native-background-downloader'
const SITE = 'https://kesha-antonov.com/react-native-background-downloader'
const TAGLINE = 'Download and upload large files on iOS and Android, even when your app is in the background or terminated'

// Describes the library to search engines on every page.
const structuredData = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareSourceCode',
  name: 'React Native Background Downloader',
  description: TAGLINE,
  url: SITE,
  codeRepository: REPO,
  programmingLanguage: 'TypeScript',
  runtimePlatform: 'React Native',
  license: 'https://spdx.org/licenses/Apache-2.0',
  author: { '@type': 'Person', name: 'Kesha Antonov', url: 'https://kesha-antonov.com/' },
}

// The docs pages are generated from the README and the docs folder at build
// time, so git has no date for the pages themselves. The sitemap takes the date
// of the last commit that touched their sources. Without git history (a tarball,
// a shallow clone with no match) the dates are left out.
function docsLastModified () {
  try {
    const date = execFileSync(
      'git',
      ['log', '-1', '--format=%cs', '--', '../README.md', '../docs', '../MIGRATION.md', '.'],
      { cwd: fileURLToPath(new URL('.', import.meta.url)), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
    ).trim()
    return /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : undefined
  } catch {
    return undefined
  }
}
const LASTMOD = docsLastModified()

// Every page gets that one date.
const sitemapWithDates = {
  createSitemapItems: async ({ defaultCreateSitemapItems, ...rest }) => {
    // The root is listed without its trailing slash (plugins/slashless-root.js)
    const items = (await defaultCreateSitemapItems(rest)).map(item => ({ ...item, url: item.url.replace(/\/$/, '') }))
    return LASTMOD ? items.map(item => ({ ...item, lastmod: LASTMOD })) : items
  },
}

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'React Native Background Downloader',
  tagline: TAGLINE,
  favicon: 'https://kesha-antonov.com/favicon.ico',
  headTags: [
    { tagName: 'link', attributes: { rel: 'icon', type: 'image/png', sizes: '192x192', href: 'https://kesha-antonov.com/favicon-192.png' } },
    { tagName: 'link', attributes: { rel: 'apple-touch-icon', href: 'https://kesha-antonov.com/apple-touch-icon.png' } },
    { tagName: 'script', attributes: { type: 'application/ld+json' }, innerHTML: JSON.stringify(structuredData) },
  ],

  url: 'https://kesha-antonov.com',
  baseUrl: '/react-native-background-downloader/',
  organizationName: 'kesha-antonov',
  projectName: 'react-native-background-downloader',
  trailingSlash: false,
  plugins: ['./plugins/slashless-root.js'],

  onBrokenLinks: 'throw',

  markdown: {
    // Keep .md files as CommonMark. The docs are generated from README.md and
    // friends, which contain raw HTML that MDX would reject.
    format: 'detect',
    hooks: { onBrokenMarkdownLinks: 'throw' },
  },

  i18n: { defaultLocale: 'en', locales: ['en'] },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          // No editUrl: the pages are generated, so scripts/sync-docs.mjs gives
          // each one the "Edit this page" link of the file it comes from.
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
        sitemap: { changefreq: 'weekly', priority: 0.5, ...sitemapWithDates },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/social-card.png',
      metadata: [
        {
          name: 'keywords',
          content:
            'react native background download, expo background download, react native background upload, react native download large file, nsurlsession, android downloadmanager, turbomodule',
        },
      ],
      colorMode: { respectPrefersColorScheme: true },
      navbar: {
        title: 'Background Downloader',
        items: [
          { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Docs' },
          { to: '/api', label: 'API', position: 'left' },
          { href: NPM, label: 'npm', position: 'right' },
          { href: REPO, label: 'GitHub', position: 'right' },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              { label: 'Installation', to: '/installation' },
              { label: 'Usage', to: '/usage' },
              { label: 'API reference', to: '/api' },
              { label: 'Troubleshooting', to: '/troubleshooting' },
            ],
          },
          {
            title: 'More',
            items: [
              { label: 'GitHub', href: REPO },
              { label: 'npm', href: NPM },
              { label: 'Changelog', href: `${REPO}/blob/main/CHANGELOG.md` },
            ],
          },
          {
            title: 'More libraries',
            items: [
              { label: 'React Native Chat', href: 'https://kesha-antonov.com/react-native-chat' },
              { label: 'React Native Zoom Reanimated', href: 'https://kesha-antonov.com/react-native-zoom-reanimated' },
              { label: 'React Native ActionCable', href: 'https://kesha-antonov.com/react-native-action-cable' },
              { label: 'React Native Cloud Sync', href: 'https://kesha-antonov.com/react-native-cloud-sync' },
            ],
          },
          {
            title: 'Built by',
            items: [
              { label: 'Kesha Antonov', href: 'https://kesha-antonov.com' },
              { label: 'Hire a React Native lead engineer', href: 'https://kesha-antonov.com/react-native-developer' },
              { label: 'GitHub profile', href: 'https://github.com/kesha-antonov' },
              { label: 'Sponsor', href: 'https://github.com/sponsors/kesha-antonov' },
            ],
          },
        ],
        copyright: 'Apache-2.0 licensed. Built by Kesha Antonov, originally by Eko labs.',
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['bash', 'json', 'java', 'kotlin', 'objectivec', 'ruby', 'groovy'],
      },
    }),
}

export default config

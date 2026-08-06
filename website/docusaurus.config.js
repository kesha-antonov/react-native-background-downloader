// @ts-check
import { themes as prismThemes } from 'prism-react-renderer'

const REPO = 'https://github.com/kesha-antonov/react-native-background-downloader'
const NPM = 'https://www.npmjs.com/package/@kesha-antonov/react-native-background-downloader'

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'React Native Background Downloader',
  tagline: 'Download and upload large files on iOS and Android, even when your app is in the background or terminated',
  favicon: 'img/favicon.ico',

  url: 'https://kesha-antonov.github.io',
  baseUrl: '/react-native-background-downloader/',
  organizationName: 'kesha-antonov',
  projectName: 'react-native-background-downloader',
  trailingSlash: false,

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
          editUrl: `${REPO}/edit/main/`,
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
        sitemap: { changefreq: 'weekly', priority: 0.5 },
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

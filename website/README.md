# Documentation site

The site published at
<https://kesha-antonov.github.io/react-native-background-downloader/>.

## How the content works

**Do not edit `website/docs/` - it is generated and git-ignored.**

Every page is produced by [`scripts/sync-docs.mjs`](./scripts/sync-docs.mjs)
from the markdown that already lives in the repository root, so there is exactly
one copy of the documentation:

| Site page | Source |
|---|---|
| Introduction, Comparison, Installation, Usage, Configuration, Troubleshooting, Example app, Contributing | sections of `../README.md` |
| API reference | `../docs/API.md` |
| Platform notes | `../docs/PLATFORM_NOTES.md` |
| Migration guide | `../MIGRATION.md` |

To change the docs, edit those files. The script slices the README by its `##`
headings, so if you rename one it will fail loudly with the heading it could not
find - update the `PAGES` table in the script to match.

The script also rewrites GitHub-relative links (`#anchors`, `./docs/API.md`,
`./MIGRATION.md`) into links between site pages, and gives every heading an
explicit anchor so those links keep working. `onBrokenLinks` is set to `throw`,
so a build failure means a link needs remapping.

## Local development

```bash
yarn install
yarn start   # regenerates the pages, then serves with hot reload
yarn build   # production build into website/build
```

`website/` is a separate Yarn project from the library, so run these commands
from this directory.

## Deployment

[`.github/workflows/docs.yml`](../.github/workflows/docs.yml) builds and
publishes to GitHub Pages on every push to `main` that touches the README, the
`docs/` directory, `MIGRATION.md`, or this folder. Pull requests build the site
without deploying it.

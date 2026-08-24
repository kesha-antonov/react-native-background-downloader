# Documentation site

The site is configured for
<https://anorak-games.github.io/react-native-background-downloader/>.

## How the content works

**Do not edit `website/docs/` - it is generated and git-ignored.**

Every page is produced by [`scripts/sync-docs.mjs`](./scripts/sync-docs.mjs)
from the markdown that already lives in the repository root, so there is exactly
one copy of the documentation:

| Site page | Source |
|---|---|
| Introduction and usage | `../README.md` |
| API reference | `../docs/API.md` |
| Platform notes | `../docs/PLATFORM_NOTES.md` |
| Migration guide | `../MIGRATION.md` |

To change the docs, edit those files. The generated page list is defined by the
`PAGES` table in the sync script.

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

The production build is created with `yarn build` from this directory.

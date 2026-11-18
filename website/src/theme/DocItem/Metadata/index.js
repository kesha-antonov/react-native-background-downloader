import React from 'react'
import Head from '@docusaurus/Head'
import Metadata from '@theme-original/DocItem/Metadata'
import { useDoc } from '@docusaurus/plugin-content-docs/client'

// The stock title is "page title | site name". A page whose front matter has
// search_title (written by scripts/sync-docs.mjs) uses that text instead, in
// the browser tab, in search results and in link previews, while its heading
// stays as it is.
export default function MetadataWrapper (props) {
  const { search_title: searchTitle } = useDoc().frontMatter
  return (
    <>
      <Metadata {...props} />
      {searchTitle && (
        <Head>
          <title>{searchTitle}</title>
          <meta property="og:title" content={searchTitle} />
        </Head>
      )}
    </>
  )
}

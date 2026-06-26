package com.eko.uidt

import androidx.core.content.FileProvider

/**
 * Library-owned [FileProvider] subclass.
 *
 * Using a dedicated subclass (rather than registering
 * `androidx.core.content.FileProvider` directly) gives the provider a unique
 * component class name. This avoids a manifest-merger collision with a host
 * app that also declares an `androidx.core.content.FileProvider`, while a
 * unique authority (`${applicationId}.rnbackgrounddownloader.fileprovider`)
 * keeps the authority from clashing too.
 *
 * Paths it can serve are declared in `res/xml/rnbgd_file_paths.xml`.
 */
class RNBGDFileProvider : FileProvider()

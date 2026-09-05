package com.twig.app

import com.twig.core.FileSystem

/**
 * libre flavor (F-Droid): ships **without** RAR support.
 *
 * junrar is under the UnRAR license — it carries the restriction "must not be used
 * to develop a RAR-compatible compressor", which does not satisfy DFSG §6 /
 * OSD §6 / FSF freedom 0, so F-Droid will not accept it. Returning null here
 * means `.rar` files are treated as ordinary files (won't expand, won't crash).
 * See CLAUDE.md "RAR and F-Droid".
 */
internal fun rarFileSystem(): FileSystem? = null

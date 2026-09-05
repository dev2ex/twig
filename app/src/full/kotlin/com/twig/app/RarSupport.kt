package com.twig.app

import com.twig.core.FileSystem
import com.twig.fs.archive.RarFileSystem

/**
 * full flavor: ships with RAR support.
 *
 * The sibling under `src/libre/` returns null — junrar is under the UnRAR license
 * (non-free), and F-Droid requires 100% FLOSS, so the libre flavor has no
 * dependency on :fs-archive-rar at all. See CLAUDE.md "RAR and F-Droid".
 */
internal fun rarFileSystem(): FileSystem? = RarFileSystem()

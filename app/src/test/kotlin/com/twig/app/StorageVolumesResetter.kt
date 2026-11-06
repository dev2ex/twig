package com.twig.app

import org.robolectric.internal.ShadowProvider

/** Robolectric replaces primary storage for each test; discard process-scoped paths too. */
class StorageVolumesResetter : ShadowProvider {
    override fun reset() = StorageVolumes.reset()
    override fun getProvidedPackageNames(): Array<String> = emptyArray()
    override fun getShadows(): Collection<Map.Entry<String, String>> = emptyList()
}

package com.twig.core

/** Display-only source identity. File names and paths retain their addressing semantics. */
data class SourcePresentation(val type: String, val name: String)

/** Optional, listing-populated source presentation; rendering must never perform IO. */
interface SourcePresentationSource {
    fun sourcePresentation(file: XFile): SourcePresentation?
}

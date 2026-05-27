package org.graphiks.kextract.pipeline

/**
 * Global configuration singleton for the kextract pipeline.
 *
 * Set [verbose] to `true` before running the pipeline to emit warnings even
 * for declarations that are marked [org.graphiks.kextract.DeclarationImpl.Skip]
 * (e.g. system declarations filtered out by an include list).  By default those
 * warnings are suppressed to keep stderr readable when processing large SDK
 * headers such as Foundation.
 */
object KextractConfig {
    /** When false (default), warnings for Skip-marked declarations are suppressed. */
    var verbose: Boolean = false
}

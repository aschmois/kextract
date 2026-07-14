package org.graphiks.kextract.kotlin.callbacks

import org.graphiks.kextract.callbacks.ValidatedDirectFunctionBinding

data class KotlinDirectFunctionBindingModel(
    val binding: ValidatedDirectFunctionBinding,
    val preflightName: String,
)

package com.grimels.keristra.core.validation

/**
 * Type-flexible equality used by V0: JSON numbers arrive as Double, engine outputs as Integer/Long/etc.
 * Moved verbatim out of [DmnValidationResult] so both validators share it.
 */
internal fun equalsIgnoreType(a: Any?, b: Any?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false

    if (a is Double || b is Double) return a.toString().toDoubleOrNull() == b.toString().toDoubleOrNull()
    if (a is Float || b is Float) return a.toString().toFloatOrNull() == b.toString().toFloatOrNull()
    if (a is Int || b is Int) return a.toString().toIntOrNull() == b.toString().toIntOrNull()
    if (a is Long || b is Long) return a.toString().toLongOrNull() == b.toString().toLongOrNull()
    if (a is Boolean || b is Boolean) return a.toString().toBooleanStrictOrNull() == b.toString().toBooleanStrictOrNull()

    return a.toString() == b.toString()
}

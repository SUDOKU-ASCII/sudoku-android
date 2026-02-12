package com.futaiii.sudodroid.data

object NodeInputValidator {
    fun normalizeHttpMaskPathRoot(raw: String?): String {
        val trimmed = raw.orEmpty().trim().trim('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains('/')) return ""
        if (!trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return ""
        return trimmed
    }

    fun requireHttpMaskPathRoot(raw: String): String {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains('/')) {
            throw IllegalArgumentException("HTTP path root must be a single segment (no '/')")
        }
        if (!trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            throw IllegalArgumentException("HTTP path root may only contain letters, digits, '_' or '-'")
        }
        return trimmed
    }

    fun parseCustomTablePatterns(raw: String): List<String> {
        return raw
            .trim()
            .split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun requireValidCustomTablePattern(pattern: String) {
        val p = pattern.trim().lowercase()
        if (p.isEmpty()) return
        if (p.length != 8) {
            throw IllegalArgumentException("Custom table must be 8 chars (got ${p.length}): $pattern")
        }
        val allowed = setOf('x', 'p', 'v')
        if (p.any { it !in allowed }) {
            throw IllegalArgumentException("Custom table must only contain x/p/v: $pattern")
        }
        val counts = p.groupingBy { it }.eachCount()
        if (counts.getOrDefault('x', 0) != 2 || counts.getOrDefault('p', 0) != 2 || counts.getOrDefault('v', 0) != 4) {
            throw IllegalArgumentException("Custom table must contain 2x, 2p, 4v: $pattern")
        }
    }

    fun requirePaddingPercentRange(min: Int, max: Int) {
        if (min !in 0..100 || max !in 0..100) {
            throw IllegalArgumentException("Padding range must be between 0 and 100")
        }
    }
}

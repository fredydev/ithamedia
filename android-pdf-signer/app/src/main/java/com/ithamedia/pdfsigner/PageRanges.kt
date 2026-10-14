package com.ithamedia.pdfsigner

/** Lecture des plages de pages saisies par l'utilisateur (« 1-3, 5, 8- »), numérotées à partir de 1. */
object PageRanges {

    /** Plages 0-based, ou null si la saisie est invalide. */
    fun parse(input: String, pageCount: Int): List<IntRange>? {
        val text = input.replace(" ", "").replace(";", ",")
        if (text.isEmpty()) return null
        val ranges = mutableListOf<IntRange>()
        for (part in text.split(',')) {
            if (part.isEmpty()) continue
            val range = when {
                part.contains('-') -> {
                    val a = part.substringBefore('-').ifEmpty { "1" }.toIntOrNull() ?: return null
                    val b = part.substringAfter('-').ifEmpty { pageCount.toString() }.toIntOrNull() ?: return null
                    if (a > b) return null
                    a..b
                }
                else -> part.toIntOrNull()?.let { it..it } ?: return null
            }
            if (range.first < 1 || range.last > pageCount) return null
            ranges += (range.first - 1)..(range.last - 1)
        }
        return ranges.ifEmpty { null }
    }

    fun pages(input: String, pageCount: Int): Set<Int>? = parse(input, pageCount)?.flatMap { it.toList() }?.toSet()
}

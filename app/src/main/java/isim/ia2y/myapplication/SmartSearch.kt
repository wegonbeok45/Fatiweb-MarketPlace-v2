package isim.ia2y.myapplication

import java.text.Normalizer
import java.util.Locale

object SmartSearch {
    private val tokenRegex = Regex("[^a-z0-9\\p{IsArabic}]+")
    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val spacesRegex = Regex("\\s+")

    private val conceptGroups = listOf(
        setOf(
            "baby", "babies", "bebe", "kids", "kid", "child", "children", "infant", "toddler",
            "enfant", "enfants", "garcon", "fille", "boy", "girl", "toy", "toys", "jouet", "jouets",
            "stroller", "strollers", "diaper", "diapers", "nursery", "maternity", "feeding", "school",
            "baby-and-toys"
        ),
        setOf(
            "cream", "creams", "creme", "cremes", "skincare", "skin", "care", "soin", "soins",
            "face", "visage", "lotion", "gel", "beauty", "beaute", "health", "cosmetic", "cosmetics",
            "cosmetique", "makeup", "haircare", "bath", "body", "wellness", "organic", "natural",
            "beauty-and-health"
        ),
        setOf(
            "fashion", "clothes", "clothing", "cloth", "vetement", "vetements", "habit", "habits",
            "dress", "robe", "shirt", "pants", "shoes", "shoe", "bag", "bags", "jewelry", "watch",
            "kids-clothing"
        ),
        setOf(
            "food", "grocery", "groceries", "nourriture", "alimentaire", "epicerie", "snack", "snacks",
            "drink", "drinks", "fruit", "fruits", "vegetable", "vegetables", "bio", "organic",
            "food-and-grocery"
        ),
        setOf(
            "home", "maison", "furniture", "meuble", "meubles", "decor", "deco", "decoration",
            "kitchen", "cuisine", "bedroom", "bathroom", "lamp", "table", "chair", "home-and-furniture"
        ),
        setOf(
            "electronics", "electronic", "electronique", "phone", "phones", "mobile", "computer",
            "laptop", "audio", "headphone", "headphones", "casque", "camera", "charger"
        ),
        setOf(
            "sport", "sports", "fitness", "outdoor", "outdoors", "gym", "running", "bike", "bicycle",
            "sports-and-outdoors"
        ),
        setOf("auto", "automotive", "car", "cars", "voiture", "vehicle", "motor", "moto"),
        setOf("real", "estate", "real-estate", "immobilier", "house", "apartment", "appartement", "rent", "sale"),
        setOf("service", "services", "job", "jobs", "repair", "cleaning", "delivery", "freelance", "tutor")
    )

    private val expansions: Map<String, Set<String>> by lazy {
        buildMap {
            conceptGroups.forEach { group ->
                val normalizedGroup = group.flatMap(::tokens).toSet() + group.map(::normalize)
                normalizedGroup.filter { it.isNotBlank() }.forEach { token ->
                    put(token, normalizedGroup)
                }
            }
        }
    }

    fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(combiningMarksRegex, "")
            .trim()
            .replace(spacesRegex, " ")
    }

    fun tokens(value: String): List<String> {
        return normalize(value)
            .split(tokenRegex)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun expandedQueryTokens(value: String): List<String> {
        val baseTokens = tokens(value)
        if (baseTokens.isEmpty()) return emptyList()

        return buildList {
            baseTokens.forEach { token ->
                add(token)
                add(stem(token))
                expansions[token].orEmpty().forEach(::add)
            }
        }
            .flatMap { token -> listOf(token, stem(token)) }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun indexedQueryTokens(value: String): List<String> {
        val directTokens = tokens(value).filter { it.length >= 3 }
        val expandedTokens = expandedQueryTokens(value).filter { it.length >= 3 }
        return (directTokens + expandedTokens)
            .distinct()
            .take(10)
    }

    fun productIndexTokens(value: String): List<String> {
        return tokens(value)
            .flatMap { token ->
                buildList {
                    add(token)
                    add(stem(token))
                    for (length in 3 until token.length) {
                        add(token.take(length))
                    }
                }
            }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun matchesToken(productToken: String, queryToken: String): Boolean {
        val normalizedProductToken = normalize(productToken)
        val normalizedQueryToken = normalize(queryToken)
        if (normalizedProductToken.isBlank() || normalizedQueryToken.isBlank()) return false
        return normalizedProductToken.startsWith(normalizedQueryToken) ||
            normalizedProductToken.contains(normalizedQueryToken) ||
            normalizedQueryToken.startsWith(normalizedProductToken) ||
            (normalizedQueryToken.length >= 4 &&
                normalizedProductToken.length >= 4 &&
                editDistanceAtMostOne(normalizedProductToken, normalizedQueryToken))
    }

    private fun stem(token: String): String {
        return when {
            token.length > 5 && token.endsWith("ies") -> token.dropLast(3) + "y"
            token.length > 4 && token.endsWith("es") -> token.dropLast(2)
            token.length > 3 && token.endsWith("s") -> token.dropLast(1)
            else -> token
        }
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}

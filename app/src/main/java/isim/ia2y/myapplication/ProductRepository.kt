package isim.ia2y.myapplication

import androidx.annotation.DrawableRes

// Cette classe organise cette partie de l'app.
data class Product(
    val id: String,
    val title: String,
    val subtitle: String,
    val price: Double,
    val rating: Double,
    val reviewsCount: Int,
    val tags: List<String>,
    val description: String,
    val bullets: List<String>,
    @DrawableRes val imageRes: Int
) {
    val unitPrice: Double get() = price
    val tag: String get() = tags.firstOrNull().orEmpty()

    val searchableText: String by lazy {
        buildString {
            append(title).append(' ')
            append(subtitle).append(' ')
            append(description).append(' ')
            append(tags.joinToString(" "))
        }.lowercase(java.util.Locale.getDefault())
    }
}

// Cette classe organise cette partie de l'app.
object ProductCatalog {
    private val products = listOf(
        Product(
            id = "chechia",
            title = "Chechia Traditionnelle Rouge",
            subtitle = "Laine pure, fabrication artisanale",
            price = 45.500,
            rating = 4.7,
            reviewsCount = 132,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Confectionnée à la main dans la médina de Tunis, cette chechia conserve sa silhouette emblématique et le confort d'une laine naturelle soigneusement travaillée.",
            bullets = listOf(
                "Laine 100 % naturelle",
                "Teinture stable sans dégorgement",
                "Finition artisanale tunisienne"
            ),
            imageRes = R.drawable.product1example
        ),
        Product(
            id = "bijoux",
            title = "Bijoux en Argent de Djerba",
            subtitle = "Argent 925, gravure fine",
            price = 120.000,
            rating = 4.6,
            reviewsCount = 89,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Pièce d'inspiration berbère réalisée en atelier local, avec un travail de gravure délicat et une finition pensée pour durer.",
            bullets = listOf(
                "Argent 925 certifié",
                "Finition polie anti-ternissement",
                "Design inspiré du patrimoine djerbien"
            ),
            imageRes = R.drawable.product2example
        ),
        Product(
            id = "marqoum",
            title = "Marqoum de Kairouan",
            subtitle = "Tissage traditionnel, laine épaisse",
            price = 350.000,
            rating = 4.9,
            reviewsCount = 64,
            tags = listOf("TUNISIE", "DERNIÈRES PIÈCES"),
            description = "Tapis marqoum tissé sur métier traditionnel avec des motifs géométriques inspirés du patrimoine de Kairouan.",
            bullets = listOf(
                "Nouage artisanal haute densité",
                "Motifs traditionnels kairouanais",
                "Laine durable pour usage quotidien"
            ),
            imageRes = R.drawable.product4example
        ),
        Product(
            id = "balgha",
            title = "Balgha Cuir Véritable",
            subtitle = "Cuir souple, couture renforcée",
            price = 65.000,
            rating = 4.5,
            reviewsCount = 176,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Balgha classique de la médina, façonnée en cuir véritable avec une ligne sobre et une semelle confortable au quotidien.",
            bullets = listOf(
                "Cuir véritable tunisien",
                "Semelle anti-glisse",
                "Confortable et légère"
            ),
            imageRes = R.drawable.product3example
        ),
        Product(
            id = "huile_olive_1l",
            title = "Huile d'Olive Extra Vierge 1L",
            subtitle = "Première pression à froid",
            price = 45.500,
            rating = 4.8,
            reviewsCount = 124,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Produite au cœur des oliveraies tunisiennes, cette huile d'olive extra vierge est extraite à froid pour préserver sa fraîcheur, sa rondeur et ses qualités nutritionnelles.",
            bullets = listOf(
                "Première pression à froid",
                "Origine: Zaghouan, Tunisie",
                "Acidité inférieure à 0,8 %"
            ),
            imageRes = R.drawable.categorie1
        ),
        Product(
            id = "harissa_artisanale",
            title = "Harissa Artisanale Bio 380g",
            subtitle = "Piments séchés au soleil",
            price = 12.900,
            rating = 4.4,
            reviewsCount = 211,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Recette familiale préparée avec des piments rouges séchés, de l'ail frais et une huile d'olive locale au goût équilibré.",
            bullets = listOf(
                "Sans conservateurs",
                "Piments de Cap Bon",
                "Pot en verre recyclable"
            ),
            imageRes = R.drawable.categorie2
        ),
        Product(
            id = "safran_tunisien",
            title = "Safran Tunisien Premium 2g",
            subtitle = "Qualité supérieure",
            price = 38.000,
            rating = 4.7,
            reviewsCount = 53,
            tags = listOf("TUNISIE", "DERNIÈRES PIÈCES"),
            description = "Filaments de safran soigneusement récoltés puis séchés pour offrir une signature aromatique fine et intense.",
            bullets = listOf(
                "Filaments 100 % purs",
                "Récolte manuelle",
                "Arrière-goût floral fin"
            ),
            imageRes = R.drawable.categorie3
        ),
        Product(
            id = "savon_neroli",
            title = "Savon Neroli Naturel",
            subtitle = "Peaux sensibles",
            price = 9.500,
            rating = 4.3,
            reviewsCount = 97,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Savon doux enrichi en huiles végétales et en néroli, idéal pour nettoyer la peau sans l'agresser.",
            bullets = listOf(
                "Formule sans sulfate",
                "Parfum léger naturel",
                "Fabrication artisanale"
            ),
            imageRes = R.drawable.product1example
        ),
        Product(
            id = "poterie_nabeul",
            title = "Poterie Décorative de Nabeul",
            subtitle = "Céramique peinte à la main",
            price = 79.000,
            rating = 4.6,
            reviewsCount = 41,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Poterie traditionnelle aux motifs méditerranéens, pensée pour apporter une touche artisanale et lumineuse à l'intérieur.",
            bullets = listOf(
                "Peinture manuelle",
                "Cuisson haute température",
                "Finition brillante"
            ),
            imageRes = R.drawable.product2example
        ),
        Product(
            id = "fouta_hammamet",
            title = "Fouta de Hammamet Coton",
            subtitle = "Tissage léger et absorbant",
            price = 29.900,
            rating = 4.5,
            reviewsCount = 118,
            tags = listOf("TUNISIE", "EN STOCK"),
            description = "Fouta en coton doux et polyvalente, parfaite pour la plage, le hammam ou une touche déco naturelle à la maison.",
            bullets = listOf(
                "Coton peigné absorbant",
                "Séchage rapide",
                "Couleurs résistantes"
            ),
            imageRes = R.drawable.product3example
        )
    )

    // Cette fonction fait une action de cette partie de l'app.
    fun byId(id: String): Product? = products.firstOrNull { it.id == id }

    // Cette fonction fait une action de cette partie de l'app.
    fun orderedFavorites(ids: Set<String>): List<Product> {
        return products.filter { ids.contains(it.id) }
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun all(): List<Product> = products
}


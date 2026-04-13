import json
import random

categories = {
    "craft": {
        "titles": ["Potery Artisanale de Nabeul", "Tapis Margoum Authentique", "Couffin en Osier Traditionnel", "Plat en Cuivre Martel\u00e9", "Bo\u00eete en Bois d'Olivier", "Vase en C\u00e9ramique Andalouse", "Miroir Berb\u00e8re", "Tableau Calligraphie", "Service \u00e0 Th\u00e9 Artisanal", "Pouff en Cuir", "Assiette d\u00e9corative Peinte", "Tissage Manuel de Gafsa", "Couverture en Laine d'Abre", "Lampe en Cuivre", "Vide Poche en Bois"],
        "price_range": (30, 450),
        "tags": ["artisanat", "fait main", "tradition"],
        "keyword": "pottery,craft"
    },
    "food": {
        "titles": ["Huile d'Olive Extra Vierge Bio", "Dattes Deglet Nour Premium", "Harissa Artisanale D\u00e9lice", "Miel de Romarin Sauvage", "Bsissa Traditionnelle", "Confiture de Figues", "Epices Raz el Hanout", "Citrons Confits G\u00e9ants", "Huile d'Argan Alimentaire", "P\u00e2te de Pistache 100%", "Amandes Torrefi\u00e9es", "Sirop de Grenadine", "Amlou aux Amandes", "Olives Noires aux Herbes", "Caf\u00e9 Arabe \u00e0 la Cardamome"],
        "price_range": (10, 150),
        "tags": ["\u00e9picerie", "terroir", "bio"],
        "keyword": "spices,food"
    },
    "beauty": {
        "titles": ["Savon Nourrissant \u00e0 l'Huile d'Olive", "Eau de Rose M\u00e9diterran\u00e9enne", "Argile Verde (Tfal) Purifiante", "S\u00e9rum Visage \u00e0 la Figue de Barbarie", "Lait Corporel au Jasmin", "Huile de Nigelle Press\u00e9e \u00e0 Froid", "Baume \u00e0 L\u00e8vres Miel & Cire", "Shampoing Solide au Romarin", "Eau de Fleur d'Oranger", "Gommage au Sucre et Argan", "Masque Visage Eclat", "Huile de Ricin Fortifiante", "Beurre de Karit\u00e9 Pur", "Sel de Bain D\u00e9tente", "Parfum Solide Ambre"],
        "price_range": (15, 80),
        "tags": ["beaut\u00e9", "naturel", "soins"],
        "keyword": "soap,beauty"
    },
    "fashion": {
        "titles": ["Ch\u00e9chia Rouge Authentique", "Fouta Tiss\u00e9e Main", "Jebba Traditionnelle en Lin", "Sac en Cuir Fauve", "Balgha Artisanale D\u00e9cor\u00e9e", "Ceinture Berb\u00e8re Brod\u00e9e", "Collier en Argent Filigrane", "Bracelet Tunisien Ancien", "Echarpe en Soie Sauvage", "Tunique Boh\u00e8me", "Chaussettes en Laine Orientale", "Chapeau de Paille Artisanal", "Pochette Broderie", "Boucles d'oreilles Tradition", "Caftan Moderne"],
        "price_range": (40, 600),
        "tags": ["mode", "traditionnel", "cuir"],
        "keyword": "fashion,tunisia"
    },
    "decor": {
        "titles": ["Lanterne Arabe en M\u00e9tal", "Miroir Sculpt\u00e9 en Bois", "Coussin Kilim Vintage", "Vide Poche C\u00e9ramique", "Set de Table en Alfa", "Cadre Photo en Bois de Palmier", "Bougie Parfum\u00e9e Jasmin", "Vase en Verre Souffl\u00e9", "Tenture Murale Margoum", "Encensoir Traditionnel", "Coupe \u00e0 Fruits Olivier", "Lustre Artisanal Laiton", "Chandelier Fer Forg\u00e9", "Horloge Murale Zellige", "Figurine Chameau Tissu"],
        "price_range": (20, 300),
        "tags": ["d\u00e9coration", "maison", "oriental"],
        "keyword": "decoration,lamp"
    }
}

cities = ["Tunis", "Sfax", "Sousse", "Kairouan", "Nabeul", "Djerba", "Hammamet", "Bizerte"]
bullets = ["100% fait main", "Mat\u00e9riaux \u00e9co-responsables", "Artisanat tunisien authentique", "Qualit\u00e9 premium garantie", "Directement depuis l'atelier", "Design unique et original"]

products = []
id_counter = 1

for cat, data in categories.items():
    titles = data["titles"]
    for i, title in enumerate(titles):
        price = round(random.uniform(data["price_range"][0], data["price_range"][1]), 1)
        stock = random.randint(1, 50)
        origin = random.choice(cities)
        desc = f"Ce d\u00e9licat {title.lower()} est une v\u00e9ritable pi\u00e8ce ma\u00eetresse de l'artisanat tunisien de {origin}. R\u00e9alis\u00e9 avec passion, il allie tradition et qualit\u00e9."
        subtitle = "Authenticit\u00e9 et Savoir-faire"
        
        # Use loremflickr with specific locks to ensure images are unique and domain-relevant
        image_url = f"https://loremflickr.com/800/800/{data['keyword']}?lock={id_counter}"
        
        selected_bullets = random.sample(bullets, 3)
        tags = data["tags"].copy()
        
        products.append({
            "id": f"seed_prod_{id_counter:03}",
            "title": title,
            "subtitle": subtitle,
            "description": desc,
            "category": cat,
            "price": price,
            "stock": stock,
            "origin": origin,
            "sourceImageUrl": image_url,
            "tags": tags,
            "bullets": selected_bullets,
            "isBio": cat in ["food", "beauty"] and random.random() > 0.5
        })
        id_counter += 1

with open(r"c:\Users\ta\AndroidStudioProjects\MyApplication3\app\src\main\assets\products_seed.json", "w", encoding="utf-8") as f:
    json.dump(products, f, ensure_ascii=False, indent=2)

print(f"Generated {len(products)} products at assets/products_seed.json")

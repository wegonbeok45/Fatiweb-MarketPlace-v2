# FatiWeb Market — Kit de soumission Play Store

Dernière mise à jour : 12 juin 2026.
Package : **`com.fatiweb.store`** · versionCode **1** · versionName **1.0**
Artefact à téléverser : `delivery/com.fatiweb.store.aab` (Android App Bundle signé).

---

## 1. URLs obligatoires (déjà en ligne)

| Usage | URL |
|---|---|
| Politique de confidentialité (FR) | https://fatiweb-marketplace.web.app/privacy.html |
| Privacy policy (EN) | https://fatiweb-marketplace.web.app/en/privacy.html |
| Suppression de compte (FR) | https://fatiweb-marketplace.web.app/account-deletion.html |
| Account deletion (EN) | https://fatiweb-marketplace.web.app/en/account-deletion.html |
| Conditions d'utilisation | https://fatiweb-marketplace.web.app/terms.html |

- **Play Console → Politique de confidentialité** : mettre l'URL FR.
- **Play Console → Sécurité des données → Suppression de compte** : mettre l'URL account-deletion FR.

## 2. Fiche Play Store (store listing)

### Français (langue par défaut)
- **Titre** (≤30 car.) : `FatiWeb Market`
- **Description courte** (≤80 car.) :
  `Marketplace tunisienne : commandez des produits locaux, payez à la livraison.`
- **Description complète** (≤4000 car.) :

```
FatiWeb Market est la place de marché mobile qui connecte clients et vendeurs locaux.

🛍️ ACHETEZ EN TOUTE SIMPLICITÉ
• Parcourez des milliers de produits par catégorie (mode, maison, beauté, high-tech…)
• Recherchez et découvrez les nouveautés et coups de cœur
• Choisissez vos options (couleur, taille…) et ajoutez au panier en un geste

💵 PAIEMENT À LA LIVRAISON
Aucune carte bancaire requise : vous payez en espèces à la réception de votre commande.
Livraison standard ou express, à l'adresse de votre choix.

📦 SUIVI DE COMMANDE EN TEMPS RÉEL
Suivez chaque étape de votre commande, de la confirmation à la livraison, avec des
notifications à chaque changement de statut.

💬 MESSAGERIE INTÉGRÉE
Échangez directement avec les vendeurs pour poser vos questions avant ou après l'achat,
et profitez de l'assistant intégré pour trouver ce que vous cherchez.

🏪 VENDEZ SUR FATIWEB
Devenez vendeur : publiez vos produits avec photos, gérez votre stock, suivez vos
commandes et vos statistiques de vente depuis un tableau de bord dédié.

⭐ AVIS VÉRIFIÉS
Les avis produits proviennent d'acheteurs ayant réellement commandé.

🔒 VOTRE COMPTE, VOS DONNÉES
Connexion par e-mail, téléphone ou Google. Vous pouvez supprimer votre compte et vos
données à tout moment depuis l'application.

Téléchargez FatiWeb Market et découvrez le commerce local, autrement.
```

### English
- **Title**: `FatiWeb Market`
- **Short description**:
  `Tunisian marketplace: order local products and pay cash on delivery.`
- **Full description**: traduire le bloc FR (mêmes sections : achat, paiement à la
  livraison, suivi, messagerie, espace vendeur, avis vérifiés, compte/données).

## 3. Assets graphiques requis

| Asset | Spécification | Statut |
|---|---|---|
| Icône | 512×512 PNG, 32-bit | À exporter depuis `app/src/main/res/mipmap-xxxhdpi/ic_launcher` (ou la source vectorielle dans `icons/`) |
| Bannière (feature graphic) | 1024×500 PNG/JPG | À créer (logo + tagline sur fond de marque) |
| Captures téléphone | ≥2 (max 8), 16:9 ou 9:16, ≥320px | Recommandé : Accueil, fiche produit, panier, suivi de commande, tableau de bord vendeur |

Captures faciles à produire sur émulateur : `adb exec-out screencap -p > screen.png`
(des captures existantes sont dans `.screenshots/` — vérifier qu'elles montrent l'UI à jour).

## 4. Formulaire « Sécurité des données » (Data Safety)

Déclarer **collecte** des types suivants (chiffrés en transit ✔, suppression possible ✔) :

| Catégorie Play | Données | Finalité | Partagé ? |
|---|---|---|---|
| Informations personnelles | Nom, e-mail, n° de téléphone, identifiants | Fonctionnement de l'app (compte) | Non |
| Localisation | Position approximative/précise (optionnelle, permission) | Fonctionnement (préremplir l'adresse) | Non |
| Infos financières | **Aucune** (paiement espèces uniquement) | — | — |
| Historique d'achats | Commandes | Fonctionnement | Non (visible du vendeur concerné uniquement) |
| Photos | Avatar, photos produits (vendeurs) | Fonctionnement | Non |
| Messages | Messages in-app client↔vendeur | Fonctionnement | Non |
| Identifiants d'appareil | Jeton FCM | Notifications | Non |
| Données d'app et diagnostic | Analytics, crash logs (Crashlytics) | Analyse / stabilité | Non |

- « Toutes les données sont chiffrées en transit » : **Oui**.
- « Les utilisateurs peuvent demander la suppression » : **Oui** (in-app + URL).

## 5. Classification du contenu (questionnaire IARC)

- Catégorie : **Shopping / utilitaire**.
- **Déclarer la communication entre utilisateurs** (chat client↔vendeur = UGC).
  L'app dispose déjà de modération : signalement (`reportConversationMessage`) et
  blocage (`blockConversationUser`).
- Pas de violence, jeux d'argent, contenu sexuel → classification attendue : 3+/Everyone.

## 6. Accès pour l'équipe de validation (App access)

L'app exige une connexion pour acheter. Fournir dans Play Console → App access :
- Un **compte client de test** (e-mail + mot de passe) créé pour la review.
- Optionnel : un compte vendeur de démo.
- Note : « Payment is cash on delivery only; no payment instrument is needed to test checkout. »

## 7. Étapes APRÈS le premier upload (critiques)

1. **Play App Signing** : Play Console → Configuration → Intégrité de l'app → copier les
   empreintes **SHA-1 et SHA-256 du certificat de signature Google Play**, puis les ajouter
   à l'app Android `com.fatiweb.store` dans la console Firebase (Paramètres du projet →
   vos applications → Ajouter une empreinte).
   ⚠️ Sans cela, Google Sign-In et l'OTP téléphone échoueront sur les builds distribués par Play.
2. **App Check** : console Firebase → App Check → enregistrer `com.fatiweb.store` avec
   **Play Integrity** (étape manuelle, pas de CLI). Pour les builds debug, enregistrer le
   jeton debug affiché dans logcat.
3. **Activer l'enforcement App Check** une fois l'app distribuée en test interne et le
   trafic vérifié : passer `enforceAppCheck: true` dans
   `firebase_functions_setup/src/shared/callableOptions.ts` puis
   `firebase deploy --only functions --project fatiweb-marketplace`.
   ⚠️ Ne pas l'activer avant : Play Integrity n'atteste que les builds installés via Play —
   l'activer trop tôt casse les APK sideloadés.

## 8. Checklist de soumission

- [ ] Compte Play Console (25 $ une fois) — `tata.gounounou@gmail.com`
- [ ] Créer l'app (« FatiWeb Market », français, application, gratuite)
- [ ] Téléverser `delivery/com.fatiweb.store.aab` en **test interne** d'abord
- [ ] Renseigner fiche store FR (+ EN), icône 512, bannière 1024×500, ≥2 captures
- [ ] URL politique de confidentialité + URL suppression de compte (section 1)
- [ ] Formulaire Sécurité des données (section 4)
- [ ] Questionnaire de classification (section 5, déclarer l'UGC/chat)
- [ ] App access : compte de test (section 6)
- [ ] Public cible : 18+ recommandé (marketplace) ; pas de pub → « ne contient pas d'annonces »
- [ ] Après upload : empreintes Play App Signing → Firebase (section 7.1)
- [ ] Tester Google Sign-In + OTP sur build de test interne
- [ ] Promouvoir en production quand le test interne est validé

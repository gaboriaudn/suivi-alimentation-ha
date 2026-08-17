# Suivi Alimentation Android — MVP v2

Premier socle Android du projet « Application Android Suivi Alimentation ».

## Périmètre réalisé

- Kotlin + Jetpack Compose.
- Architecture UI → ViewModel → Repository → transport Home Assistant.
- Home Assistant reste la source unique de vérité.
- Authentification OAuth Home Assistant par navigateur / Custom Tab, sans mot de passe ni token statique embarqué.
- Refresh token et access token chiffrés avec Android Keystore (AES/GCM).
- Client `/api/websocket` : `auth_required` → `auth` → `auth_ok`, commandes numérotées, gestion des erreurs, reconnexion et réabonnement.
- Modèles Kotlin alignés sur le contrat `suivi_alimentation/v2` vérifié en direct le 17 août 2026.
- Paramètres de commandes v2 en `snake_case` (`profile_id`, `local_date`) et réponses métier en camelCase.
- Repository indépendant de Compose et d'OkHttp.
- Journal DataStore des mutations ambiguës avec conservation du même `operation_id` jusqu'à réponse définitive.
- Suivi de `storeRevision` et des révisions d'entités ; les erreurs serveur de conflit/révision sont remontées comme conflits Repository.
- Réabonnement à `suivi_alimentation/v2/subscribe` après reconnexion et rafraîchissement serveur de l'écran.
- Écran « Aujourd'hui » : profil actif, objectifs calories/protéines, totaux reçus du backend, repas et aliments, chargement/erreur/déconnexion/reconnexion.

Aucun calcul nutritionnel n'est reproduit dans l'application : `totals`, `totalsSnapshot` et `nutritionSnapshot` sont affichés tels que reçus de Home Assistant. Les `null` nutritionnels restent inconnus.

## Versions retenues (vérifiées sur la documentation officielle au 17/08/2026)

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- compileSdk / targetSdk 37
- Java 17
- Kotlin Gradle Plugin / Compose Compiler 2.3.21 (épinglé explicitement pour Kotlin intégré AGP 9)
- Compose BOM 2026.06.00
- Activity Compose 1.13.0
- Lifecycle 2.11.0
- Browser 1.10.0
- DataStore 1.2.1
- kotlinx.coroutines 1.11.0
- kotlinx.serialization-json 1.11.0
- OkHttp 5.3.0

## Configuration OAuth obligatoire

Home Assistant utilise OAuth 2 + IndieAuth. Pour une application native, `client_id` doit être l'URL publique d'une page appartenant à l'application et cette page doit autoriser le schéma de retour Android.

1. Héberger le fichier `oauth-client/index.html` à une URL HTTPS publique, par exemple `https://votre-domaine.example/suivi-alimentation-android`.
2. Vérifier que la balise suivante se trouve dans les 10 premiers Ko de cette page :

```html
<link rel="redirect_uri" href="suivialimentation://auth-callback">
```

3. Définir l'URL exacte comme propriété Gradle, idéalement dans `~/.gradle/gradle.properties` :

```properties
HA_OAUTH_CLIENT_ID=https://votre-domaine.example/suivi-alimentation-android
```

Le `client_id` n'est pas un secret. Aucun client secret n'est nécessaire. Le projet refuse volontairement de lancer l'authentification tant que la valeur d'exemple n'a pas été remplacée.

## Connexion Home Assistant

L'utilisateur saisit l'URL de son Home Assistant. HTTPS est accepté partout. HTTP n'est accepté que pour `localhost`, `.local`, ou une adresse IPv4 privée/loopback. L'autorisation s'ouvre dans un Custom Tab et revient dans l'application via `suivialimentation://auth-callback`.

Le WebSocket utilise ensuite uniquement l'access token OAuth. En cas d'expiration, l'access token est renouvelé avec le refresh token. Si le refresh token n'est plus valable, les jetons locaux sont supprimés et l'utilisateur doit se reconnecter.

## Contrat v2 utilisé par l'écran Aujourd'hui

- `suivi_alimentation/v2/get_my_profile`
- `suivi_alimentation/v2/get_profile` avec `profile_id`
- `suivi_alimentation/v2/get_day` avec `profile_id` et `local_date`
- `suivi_alimentation/v2/subscribe` pour les changements temps réel

`get_recent` est également modélisé et prêt pour l'étape de saisie suivante. Le serveur conserve la limite des récents ; Android ne la recalcule pas.

## Idempotence et conflits

La couche Repository possède déjà le mécanisme de mutation générique nécessaire aux prochaines commandes atomiques :

- génération UUID d'un `operation_id` ;
- persistance de la commande complète avant envoi ;
- suppression seulement après succès ou refus serveur définitif ;
- conservation si la connexion tombe avant de connaître le résultat ;
- réémission automatique du même `operation_id` après reconnexion ;
- suivi de `storeRevision` et des révisions d'entités lues ;
- remontée des conflits renvoyés par Home Assistant.

Les formulaires d'ajout/modification de repas ne sont volontairement pas inclus dans ce jalon « Aujourd'hui ». Ils devront utiliser les commandes v2 déjà présentes côté Home Assistant et passer leurs préconditions de révision exactes dans le payload métier.

## Vérifications locales

Le dépôt contient des tests de contrat sur un exemple réel de `get_day`, sur la politique d'URL et sur le suivi monotone des révisions.

Commandes normales dans un environnement Android SDK complet. Le wrapper Gradle n'a pas pu être généré dans l'environnement de production de ce socle, car Gradle/Android SDK n'y sont pas installés et les archives binaires externes y sont bloquées. Après ouverture dans Android Studio, ou avec Gradle 9.5.0 installé localement, générer une fois le wrapper puis lancer les contrôles :

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Le projet a néanmoins fait l'objet de contrôles statiques, de tests Kotlin autonomes sur la politique d'URL et les révisions, d'une validation XML et d'une fixture de test de contrat construite à partir d'une réponse v2 réelle.

Ce package ne contient volontairement aucune clé OpenAI, clé CIQUAL/OFF ni secret serveur.

## Hors périmètre de ce jalon

- pipeline photo v2 ;
- recettes complètes/versionnées ;
- adaptateur final du dashboard vers v2 ;
- bascule production v1 → v2.

Le dashboard v1 Home Assistant n'est pas modifié par ce projet Android.

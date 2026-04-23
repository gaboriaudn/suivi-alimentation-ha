# 🥗 Suivi Alimentation pour Home Assistant

Une intégration Home Assistant pour suivre facilement vos apports journaliers en **calories** et **protéines**.

![Version](https://img.shields.io/badge/version-0.22-blue)
![Langue](https://img.shields.io/badge/langue-Français-green)
![HA](https://img.shields.io/badge/Home%20Assistant-2024.1+-orange)

---

## ✨ Fonctionnalités

- 📅 **Suivi journalier** des calories et protéines par repas (Petit-déjeuner, Déjeuner, Dîner, Collation)
- 🎯 **Objectifs personnalisables** de calories et protéines
- 📷 **Analyse photo par IA** : prenez une photo d'un repas et laissez l'IA estimer les valeurs nutritionnelles
- 📚 **Bibliothèque d'aliments** : enregistrez vos aliments favoris avec leurs valeurs nutritionnelles
- 📊 **Historique graphique** sur 14 jours
- 💾 **Données stockées nativement** dans Home Assistant (sauvegardées automatiquement)
- 🌙 **Interface en français**, compatible thème clair et sombre

---

## 📋 Prérequis

- Home Assistant **2024.1** ou plus récent
- L'add-on **Studio Code Server** ou **File Editor** pour copier les fichiers
- *(Optionnel)* Un service IA configuré dans HA (ex: OpenAI) pour la fonctionnalité photo

---

## 🚀 Installation manuelle

> ⚠️ L'installation via HACS n'est pas encore disponible. Suivez les étapes ci-dessous.

### Étape 1 — Copier les fichiers

1. Dans votre Home Assistant, ouvrez le gestionnaire de fichiers
2. Naviguez jusqu'au dossier `/config/custom_components/`
3. Créez un dossier nommé `suivi_alimentation`
4. Copiez **tout le contenu** du dossier `custom_components/suivi_alimentation/` de ce dépôt dans ce nouveau dossier

### Étape 2 — Redémarrer Home Assistant

1. Allez dans **Paramètres → Système → Redémarrer**
2. Attendez que HA redémarre complètement

### Étape 3 — Ajouter l'intégration

1. Allez dans **Paramètres → Intégrations**
2. Cliquez sur **+ Ajouter une intégration**
3. Recherchez **"Suivi Alimentation"**
4. Cliquez sur **Soumettre**

L'intégration apparaît alors dans le menu latéral de Home Assistant sous le nom **"Calories"** 🎉

---

## 📷 Fonctionnalité analyse photo par IA (optionnel)

Pour utiliser l'analyse de photos, vous devez avoir configuré un service IA dans Home Assistant (par exemple OpenAI via l'intégration officielle HA).

Le service utilisé est `ai_task.openai_ai_task` — vous pouvez le modifier dans le code si vous utilisez un autre fournisseur.

---

## 🗂️ Structure des fichiers

```
custom_components/
  suivi_alimentation/
    __init__.py          → Point d'entrée de l'intégration
    config_flow.py       → Interface de configuration
    const.py             → Constantes
    manifest.json        → Métadonnées de l'intégration
    store.py             → Gestion du stockage des données
    websocket.py         → API WebSocket pour le frontend
    strings.json         → Textes de l'interface de config
    translations/
      fr.json            → Traduction française
    frontend/
      calories-panel.js  → Composant principal
      storage.js         → Gestion des données côté JS
      utils.js           → Fonctions utilitaires
      views/
        home.js          → Vue Accueil
        day.js           → Vue Ma Journée
        foods.js         → Vue Aliments et plats
        history.js       → Vue Historique
```

---

## 📝 Licence

Ce projet est sous licence [MIT](LICENSE).

---

## 🙏 Crédits

Développé avec ❤️ en français pour la communauté Home Assistant francophone.

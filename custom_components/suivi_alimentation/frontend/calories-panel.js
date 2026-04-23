// calories-panel.js - v0.21
// Panneau principal Suivi Alimentation
// Détection automatique du profil selon l'utilisateur HA connecté
// Sélecteur de profil visible uniquement pour les administrateurs

import {
  getLocalDateString,
  escapeHtml,
  escapeAttr,
  toNumber,
  createId,
} from "./utils.js";
import {
  loadCaloriesData,
  saveCaloriesData,
  loadAllProfiles,
  setActiveProfileId,
  getActiveProfileId,
} from "./storage.js";
import { renderTabs, renderHomeView } from "./views/home.js?v=1021";
import { renderFoodsView } from "./views/foods.js?v=1021";
import { renderDayView } from "./views/day.js?v=1021";
import { renderHistoryView } from "./views/history.js?v=1021";

class CalorieTrackerPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });

    this._hass = null;
    this.config = {};
    this.initialized = false;
    this.loading = false;
    this.message = "";
    this.currentView = "home";
    this.appVersion = "v0.21";

    // Multi-profils
    this.isAdmin = false;
    this.allProfiles = [];
    this.currentProfileName = "";

    this.categories = ["Petit-déjeuner", "Déjeuner", "Dîner", "Collation"];

    this.data = {
      goal: 2000,
      proteinGoal: 100,
      foods: [],
      entriesByDate: {},
    };

    this.selectedDate = getLocalDateString();

    this.foodForm = {
      templateId: "custom",
      name: "",
      quantity: "",
      calories: "",
      proteins: "",
      category: "Petit-déjeuner",
    };

    this.newFood = {
      name: "",
      mode: "unit",
      unitLabel: "portion",
      caloriesValue: "",
      proteinsValue: "",
      defaultCategory: "Déjeuner",
    };

    this.editingFoodId = null;
    this.editingFood = null;
    this.historyDetailDate = null;
    this.addPopupCategory = null;
    this.dayDetailOpen = false;
    this.aiLoading = false;
    this.aiResult = null;
    this.aiError = null;
    this.saveToLibraryData = null;
    this.aiSavedToDay = false;
    this.mealDetailCategory = null;
    this.editingGoal = false;
    this.editingProteinGoal = false;

    this.shadowRoot.addEventListener("input",  (e) => this.handleInput(e));
    this.shadowRoot.addEventListener("change", (e) => this.handleChange(e));
    this.shadowRoot.addEventListener("click",  (e) => this.handleClick(e));

    this.render();
  }

  setConfig(config) { this.config = config || {}; }

  set hass(hass) {
    this._hass = hass;
    if (!this.initialized && hass) {
      this.initialized = true;
      this.loadData();
    }
  }

  get hass() { return this._hass; }

  // ── Helpers ────────────────────────────────────────────────
  escapeHtml(v)         { return escapeHtml(v); }
  escapeAttr(v)         { return escapeAttr(v); }
  toNumber(v, fb = 0)   { return toNumber(v, fb); }
  createId(p = "id")    { return createId(p); }
  formatNumber(v)       { return Number(v ?? 0).toLocaleString("fr-FR"); }

  get dayEntries() {
    return Array.isArray(this.data.entriesByDate[this.selectedDate])
      ? this.data.entriesByDate[this.selectedDate] : [];
  }
  get totalCalories()    { return this.dayEntries.reduce((s, i) => s + this.toNumber(i.calories, 0), 0); }
  get remainingCalories(){ return this.toNumber(this.data.goal, 0) - this.totalCalories; }
  get totalProteins()    { return this.dayEntries.reduce((s, i) => s + this.toNumber(i.proteins, 0), 0); }
  get remainingProteins(){ return this.toNumber(this.data.proteinGoal, 0) - this.totalProteins; }

  get selectedFood() {
    return this.data.foods.find((f) => f.id === this.foodForm.templateId) || null;
  }
  get computedCalories() {
    const food = this.selectedFood, qty = this.toNumber(this.foodForm.quantity, 0);
    if (!food || qty <= 0) return "";
    return food.mode === "grams"
      ? Math.round(this.toNumber(food.caloriesPer100g, 0) * qty / 100)
      : Math.round(this.toNumber(food.caloriesPerUnit, 0) * qty);
  }
  get computedProteins() {
    const food = this.selectedFood, qty = this.toNumber(this.foodForm.quantity, 0);
    if (!food || qty <= 0) return "";
    return food.mode === "grams"
      ? Math.round(this.toNumber(food.proteinsPer100g, 0) * qty / 100)
      : Math.round(this.toNumber(food.proteinsPerUnit, 0) * qty);
  }

  getTotalFoodsCount()   { return this.data.foods.length; }
  getTrackedDaysCount()  { return Object.keys(this.data.entriesByDate || {}).length; }
  getEntriesForDate(d)   { return Array.isArray(this.data.entriesByDate[d]) ? this.data.entriesByDate[d] : []; }
  formatDateShort(s)     { if (!s?.includes("-")) return s||""; const[,m,d]=s.split("-"); return`${d}/${m}`; }
  formatDateLong(s)      { if (!s?.includes("-")) return s||""; const[y,m,d]=s.split("-"); return`${d}/${m}/${y}`; }

  openHistoryDetail(date)  { this.historyDetailDate = date; this.render(); }
  closeHistoryDetail()     { this.historyDetailDate = null; this.render(); }
  getHistoryDetailGroups(date) {
    return this.categories.map(cat => ({
      category: cat,
      items: this.getEntriesForDate(date).filter(i => i.category === cat),
    })).filter(g => g.items.length > 0);
  }
  getRecentDays(limit = 30) {
    return Object.keys(this.data.entriesByDate || {})
      .sort((a, b) => b.localeCompare(a)).slice(0, limit)
      .map(date => {
        const items = Array.isArray(this.data.entriesByDate[date]) ? this.data.entriesByDate[date] : [];
        return {
          date,
          total:    items.reduce((s, i) => s + this.toNumber(i.calories, 0), 0),
          proteins: items.reduce((s, i) => s + this.toNumber(i.proteins, 0), 0),
          count:    items.length,
        };
      });
  }
  getChartDays(limit = 14) { return this.getRecentDays(limit).reverse(); }

  setMessage(msg = "")   { this.message = msg; this.updateStatusUI(); }
  setLoading(v)          { this.loading = Boolean(v); this.updateLoadingUI(); }
  setView(v)             { this.currentView = v; this.render(); }

  updateStatusUI() {
    const el = this.shadowRoot.getElementById("statusMessage");
    if (!el) return;
    el.textContent = this.message || "";
    el.classList.toggle("hidden", !this.message);
  }
  updateLoadingUI() {
    const root = this.shadowRoot.querySelector(".app-shell");
    if (root) root.classList.toggle("is-loading", this.loading);
    this.shadowRoot.querySelectorAll("button").forEach(b => b.disabled = this.loading);
  }
  updateSummaryUI() {
    const t = this.shadowRoot.getElementById("summaryTotal");
    const g = this.shadowRoot.getElementById("summaryGoal");
    const r = this.shadowRoot.getElementById("summaryRemaining");
    if (t) t.textContent = `${this.formatNumber(this.totalCalories)} kcal`;
    if (g) g.textContent = `${this.formatNumber(this.data.goal ?? 0)} kcal`;
    if (r) {
      const rem = this.remainingCalories;
      r.textContent = rem >= 0
        ? `${this.formatNumber(rem)} kcal`
        : `+${this.formatNumber(Math.abs(rem))} kcal`;
    }
  }
  updateComputedCaloriesUI() {
    const el = this.shadowRoot.getElementById("computedCalories");
    if (el) el.value = this.computedCalories === "" ? "" : String(this.computedCalories);
  }
  updateComputedProteinsUI() {
    const el = this.shadowRoot.getElementById("computedProteins");
    if (el) el.value = this.computedProteins === "" ? "" : String(this.computedProteins);
  }

  // ── Chargement des données ─────────────────────────────────
  async loadData() {
    this.setLoading(true);
    this.setMessage("Chargement...");
    try {
      const loaded = await loadCaloriesData(this._hass);
      this.data = { ...loaded, proteinGoal: loaded.proteinGoal ?? 100 };
      this.isAdmin = loaded.isAdmin || false;

      // Si admin, charger la liste de tous les profils pour le sélecteur
      if (this.isAdmin) {
        this.allProfiles = await loadAllProfiles(this._hass);
      }

      // Nom du profil actif
      const activeId = getActiveProfileId();
      const activeProfile = this.allProfiles.find(p => p.id === activeId);
      this.currentProfileName = activeProfile?.name || "Mon profil";

      this.render();
      this.setMessage("");
    } catch (error) {
      console.error(error);
      this.render();
      this.setMessage("Erreur de chargement.");
    } finally {
      this.setLoading(false);
    }
  }

  // ── Changement de profil (admin uniquement) ────────────────
  async switchProfile(profileId) {
    if (!this.isAdmin) return;
    this.setLoading(true);
    this.setMessage("Chargement du profil...");
    try {
      const loaded = await loadCaloriesData(this._hass, profileId);
      this.data = { ...loaded, proteinGoal: loaded.proteinGoal ?? 100 };
      setActiveProfileId(profileId);
      const activeProfile = this.allProfiles.find(p => p.id === profileId);
      this.currentProfileName = activeProfile?.name || "Profil";
      this.render();
      this.setMessage("");
    } catch (error) {
      console.error(error);
      this.setMessage("Erreur lors du changement de profil.");
    } finally {
      this.setLoading(false);
    }
  }

  async saveData() {
    this.setLoading(true);
    try {
      await saveCaloriesData(this._hass, this.data);
      return true;
    } catch (error) {
      console.error(error);
      this.setMessage("Erreur pendant l'enregistrement.");
      return false;
    } finally {
      this.setLoading(false);
    }
  }

  // ── Objectifs ──────────────────────────────────────────────
  setGoal(value) {
    this.data.goal = value === "" ? 0 : this.toNumber(value, 0);
    this.updateSummaryUI();
  }
  setProteinGoal(value) {
    this.data.proteinGoal = value === "" ? 0 : this.toNumber(value, 0);
  }
  setSelectedDate(value) {
    this.selectedDate = value || getLocalDateString();
    this.render();
  }

  // ── Gestion des aliments ───────────────────────────────────
  updateFoodTemplate(value) {
    this.foodForm.templateId = value;
    const food = this.data.foods.find(i => i.id === value);
    if (food) {
      this.foodForm.name = food.name;
      this.foodForm.category = this.addPopupCategory || this.foodForm.category;
      this.foodForm.quantity = "";
      this.foodForm.calories = "";
    } else {
      this.foodForm.name = "";
      this.foodForm.quantity = "";
      this.foodForm.calories = "";
    }
    this.render();
  }

  updateNewFoodMode(value) {
    this.newFood.mode = value;
    if (value === "grams") this.newFood.unitLabel = "g";
    else if (!this.newFood.unitLabel || this.newFood.unitLabel === "g") this.newFood.unitLabel = "portion";
    this.render();
  }

  startEditFood(id) {
    const food = this.data.foods.find(i => i.id === id);
    if (!food) return;
    this.editingFoodId = id;
    this.editingFood = {
      name: food.name,
      mode: food.mode,
      unitLabel: food.mode === "grams" ? "g" : food.unitLabel || "portion",
      caloriesValue: food.mode === "grams" ? String(food.caloriesPer100g ?? "") : String(food.caloriesPerUnit ?? ""),
      proteinsValue: food.mode === "grams" ? String(food.proteinsPer100g ?? "") : String(food.proteinsPerUnit ?? ""),
      defaultCategory: food.defaultCategory || "Déjeuner",
    };
    this.render();
  }

  updateEditingFoodMode(value) {
    if (!this.editingFood) return;
    this.editingFood.mode = value;
    if (value === "grams") this.editingFood.unitLabel = "g";
    else if (!this.editingFood.unitLabel || this.editingFood.unitLabel === "g") this.editingFood.unitLabel = "portion";
    this.render();
  }

  cancelEditFood() {
    this.editingFoodId = null;
    this.editingFood = null;
    this.historyDetailDate = null;
    this.render();
  }

  openAddPopup(category) {
    this.addPopupCategory = category;
    this.foodForm = { templateId: "custom", name: "", quantity: "", calories: "", proteins: "", category };
    this.render();
  }

  closeAddPopup() {
    this.addPopupCategory = null;
    this.foodForm = { templateId: "custom", name: "", quantity: "", calories: "", category: "Petit-déjeuner" };
    this.render();
  }

  openDayDetail()   { this.dayDetailOpen = true; this.render(); }
  closeDayDetail()  { this.dayDetailOpen = false; this.render(); }
  openMealDetail(c) { this.mealDetailCategory = c; this.render(); }
  closeMealDetail() { this.mealDetailCategory = null; this.render(); }

  toggleEditGoal() {
    this.editingGoal = !this.editingGoal;
    this.render();
  }
  toggleEditProteinGoal() {
    this.editingProteinGoal = !this.editingProteinGoal;
    this.render();
  }

  async deleteUploadedPhoto(mediaContentId) {
    if (!mediaContentId) return;
    try {
      await fetch("/api/media_source/local_source/remove", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${this._hass.auth.data.access_token}`,
        },
        body: JSON.stringify({ media_content_id: mediaContentId }),
      });
    } catch (e) { console.warn("Suppression photo échouée :", e); }
  }

  // ── Analyse IA ─────────────────────────────────────────────
  async analyzePhoto(file) {
    if (!file) return;
    this.aiLoading = true; this.aiResult = null; this.aiError = null; this.render();
    try {
      const fd = new FormData();
      fd.append("media_content_id", "media-source://media_source/local/.");
      fd.append("file", file, file.name || "photo.jpg");
      const upResp = await fetch("/api/media_source/local_source/upload", {
        method: "POST",
        headers: { "Authorization": `Bearer ${this._hass.auth.data.access_token}` },
        body: fd,
      });
      if (!upResp.ok) throw new Error(`Upload échoué : HTTP ${upResp.status}`);
      const upData = await upResp.json();
      const mediaContentId = upData?.media_content_id;
      if (!mediaContentId) throw new Error("Impossible de récupérer l'ID du média uploadé.");

      const response = await fetch("/api/services/ai_task/generate_data?return_response", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${this._hass.auth.data.access_token}`,
        },
        body: JSON.stringify({
          task_name: "Analyse nutritionnelle",
          entity_id: "ai_task.openai_ai_task",
          instructions: "Tu es un expert en nutrition. Analyse cette photo de repas et estime le nom du plat en français, les calories et les protéines.",
          structure: {
            name:        { selector: { text: {} },   description: "Nom court et descriptif du repas en français" },
            calories:    { selector: { number: {} }, description: "Nombre entier estimé de calories (kcal)" },
            proteins:    { selector: { number: {} }, description: "Nombre entier estimé de protéines en grammes" },
            description: { selector: { text: {} },   description: "Courte description de ce que tu vois (1 phrase en français)" },
          },
          attachments: [{ media_content_id: mediaContentId, media_content_type: file.type || "image/jpeg" }],
        }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const result = await response.json();
      await this.deleteUploadedPhoto(mediaContentId);
      const rd = result?.service_response?.data || result?.response?.data || result?.data || {};
      this.aiResult = {
        name:        rd.name || "",
        calories:    Math.round(rd.calories || 0),
        proteins:    Math.round(rd.proteins || 0),
        description: rd.description || "",
      };
    } catch (error) {
      console.error("Erreur analyse IA :", error);
      this.aiError = "L'analyse a échoué : " + (error.message || "erreur inconnue");
    } finally {
      this.aiLoading = false; this.render();
    }
  }

  async analyzePhotoForLibrary(file) {
    if (!file) return;
    this.aiLoading = true; this.aiError = null; this.render();
    try {
      const fd = new FormData();
      fd.append("media_content_id", "media-source://media_source/local/.");
      fd.append("file", file, file.name || "photo.jpg");
      const upResp = await fetch("/api/media_source/local_source/upload", {
        method: "POST",
        headers: { "Authorization": `Bearer ${this._hass.auth.data.access_token}` },
        body: fd,
      });
      if (!upResp.ok) throw new Error(`Upload échoué : HTTP ${upResp.status}`);
      const upData = await upResp.json();
      const mediaContentId = upData?.media_content_id;
      if (!mediaContentId) throw new Error("Impossible de récupérer l'ID du média.");

      const response = await fetch("/api/services/ai_task/generate_data?return_response", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${this._hass.auth.data.access_token}`,
        },
        body: JSON.stringify({
          task_name: "Analyse nutritionnelle bibliothèque",
          entity_id: "ai_task.openai_ai_task",
          instructions: "Tu es un expert en nutrition. Analyse cette photo d'aliment et détermine ses valeurs nutritionnelles.",
          structure: {
            name:      { selector: { text: {} },   description: "Nom court et descriptif de l'aliment en français" },
            mode:      { selector: { text: {} },   description: "Mode de mesure : 'grams' ou 'unit'" },
            calories:  { selector: { number: {} }, description: "Calories : si mode=grams, pour 100g. Si mode=unit, par unité" },
            proteins:  { selector: { number: {} }, description: "Protéines en g" },
            unitLabel: { selector: { text: {} },   description: "Nom de l'unité si mode=unit" },
          },
          attachments: [{ media_content_id: mediaContentId, media_content_type: file.type || "image/jpeg" }],
        }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const result = await response.json();
      await this.deleteUploadedPhoto(mediaContentId);
      const d = result?.service_response?.data || result?.response?.data || result?.data || {};
      this.newFood.name         = d.name || "";
      this.newFood.mode         = d.mode === "grams" ? "grams" : "unit";
      this.newFood.unitLabel    = d.mode === "grams" ? "g" : (d.unitLabel || "portion");
      this.newFood.caloriesValue = String(Math.round(d.calories || 0));
      this.newFood.proteinsValue = String(Math.round(d.proteins || 0));
    } catch (error) {
      console.error("Erreur analyse IA bibliothèque :", error);
      this.setMessage("L'analyse a échoué. Réessayez.");
    } finally {
      this.aiLoading = false; this.render();
    }
  }

  _fileToDataUrl(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(new Error("Lecture du fichier échouée"));
      r.readAsDataURL(file);
    });
  }

  async confirmAiResult() {
    if (!this.aiResult) return;
    const libraryData = {
      name: this.aiResult.name,
      calories: this.aiResult.calories,
      mode: "unit",
      unitLabel: "portion",
      defaultCategory: this.addPopupCategory || "Déjeuner",
    };
    const entry = {
      id: this.createId("entry"),
      name: this.aiResult.name,
      calories: this.aiResult.calories,
      proteins: this.aiResult.proteins,
      category: this.addPopupCategory || this.foodForm.category,
      quantity: null, quantityUnit: null,
      createdAt: new Date().toISOString(),
    };
    if (!Array.isArray(this.data.entriesByDate[this.selectedDate])) {
      this.data.entriesByDate[this.selectedDate] = [];
    }
    this.data.entriesByDate[this.selectedDate].push(entry);
    this.aiResult = null; this.aiError = null;
    this.saveToLibraryData = libraryData;
    this.aiSavedToDay = true;
    this.render();
    await this.saveData();
  }

  cancelAiResult() { this.aiResult = null; this.aiError = null; this.render(); }

  closeSaveToLibrary() {
    this.saveToLibraryData = null;
    this.aiSavedToDay = false;
    this.addPopupCategory = null;
    this.render();
  }

  async confirmSaveToLibrary() {
    if (!this.saveToLibraryData) return;
    const d = this.saveToLibraryData;
    const nameEl     = this.shadowRoot.getElementById("libFoodName");
    const caloriesEl = this.shadowRoot.getElementById("libFoodCalories");
    const unitEl     = this.shadowRoot.getElementById("libFoodUnit");
    const name       = (nameEl?.value || d.name).trim();
    const calories   = this.toNumber(caloriesEl?.value || d.calories, 0);
    const unitLabel  = unitEl?.value?.trim() || "portion";
    const mode       = d.mode || "unit";
    if (!name || calories <= 0) { this.setMessage("Nom ou calories invalides."); return; }
    const food = {
      id: this.createId("food"),
      name, mode,
      unitLabel: mode === "grams" ? "g" : unitLabel,
      defaultCategory: d.defaultCategory || "Déjeuner",
    };
    if (mode === "grams") food.caloriesPer100g = calories;
    else food.caloriesPerUnit = calories;
    this.data.foods.push(food);
    this.saveToLibraryData = null;
    this.aiSavedToDay = false;
    this.addPopupCategory = null;
    this.render();
    await this.saveData();
  }

  async addFood() {
    const name         = this.newFood.name.trim();
    const caloriesValue = this.toNumber(this.newFood.caloriesValue, 0);
    if (!name || caloriesValue <= 0) { this.setMessage("Erreur : nom ou calories invalides."); return; }
    const food = {
      id: this.createId("food"),
      name,
      mode: this.newFood.mode,
      unitLabel: this.newFood.mode === "grams" ? "g" : this.newFood.unitLabel.trim() || "portion",
      defaultCategory: this.newFood.defaultCategory,
    };
    if (this.newFood.mode === "grams") {
      food.caloriesPer100g = caloriesValue;
      food.proteinsPer100g = this.toNumber(this.newFood.proteinsValue, 0);
    } else {
      food.caloriesPerUnit = caloriesValue;
      food.proteinsPerUnit = this.toNumber(this.newFood.proteinsValue, 0);
    }
    this.data.foods.push(food);
    this.newFood = { name: "", mode: "unit", unitLabel: "portion", caloriesValue: "", proteinsValue: "", defaultCategory: "Déjeuner" };
    this.render();
    await this.saveData();
  }

  async saveEditedFood() {
    if (!this.editingFoodId || !this.editingFood) return;
    const name         = this.editingFood.name.trim();
    const caloriesValue = this.toNumber(this.editingFood.caloriesValue, 0);
    if (!name || caloriesValue <= 0) { this.setMessage("Erreur : nom ou calories invalides."); return; }
    this.data.foods = this.data.foods.map(food => {
      if (food.id !== this.editingFoodId) return food;
      const updated = {
        id: food.id, name,
        mode: this.editingFood.mode,
        unitLabel: this.editingFood.mode === "grams" ? "g" : this.editingFood.unitLabel.trim() || "portion",
        defaultCategory: this.editingFood.defaultCategory,
      };
      if (this.editingFood.mode === "grams") {
        updated.caloriesPer100g = caloriesValue;
        updated.proteinsPer100g = this.toNumber(this.editingFood.proteinsValue, 0);
      } else {
        updated.caloriesPerUnit = caloriesValue;
        updated.proteinsPerUnit = this.toNumber(this.editingFood.proteinsValue, 0);
      }
      return updated;
    });
    this.editingFoodId = null; this.editingFood = null; this.historyDetailDate = null;
    this.render();
    await this.saveData();
  }

  async deleteFood(id) {
    this.data.foods = this.data.foods.filter(f => f.id !== id);
    if (this.foodForm.templateId === id) {
      this.foodForm = { templateId: "custom", name: "", quantity: "", calories: "", category: "Petit-déjeuner" };
    }
    if (this.editingFoodId === id) { this.editingFoodId = null; this.editingFood = null; }
    this.render();
    await this.saveData();
  }

  async addEntry() {
    const finalName     = (this.foodForm.name || this.selectedFood?.name || "").trim();
    const finalCalories = this.foodForm.templateId !== "custom"
      ? this.toNumber(this.computedCalories, 0) : this.toNumber(this.foodForm.calories, 0);
    const finalProteins = this.foodForm.templateId !== "custom"
      ? this.toNumber(this.computedProteins, 0) : this.toNumber(this.foodForm.proteins, 0);
    if (!finalName || finalCalories <= 0) { this.setMessage("Veuillez renseigner un nom et des calories valides."); return; }
    const entry = {
      id: this.createId("entry"),
      name: finalName, calories: finalCalories, proteins: finalProteins,
      category: this.foodForm.category,
      quantity: this.foodForm.quantity === "" ? null : this.toNumber(this.foodForm.quantity, 0),
      quantityUnit: this.selectedFood?.unitLabel || null,
      createdAt: new Date().toISOString(),
    };
    if (!Array.isArray(this.data.entriesByDate[this.selectedDate])) {
      this.data.entriesByDate[this.selectedDate] = [];
    }
    this.data.entriesByDate[this.selectedDate].push(entry);
    const currentCategory = this.addPopupCategory || this.foodForm.category;
    this.foodForm = { templateId: "custom", name: "", quantity: "", calories: "", proteins: "", category: currentCategory };
    this.render();
    await this.saveData();
  }

  async deleteEntry(id) {
    const current = Array.isArray(this.data.entriesByDate[this.selectedDate])
      ? this.data.entriesByDate[this.selectedDate] : [];
    this.data.entriesByDate[this.selectedDate] = current.filter(i => i.id !== id);
    if (this.data.entriesByDate[this.selectedDate].length === 0) {
      delete this.data.entriesByDate[this.selectedDate];
    }
    this.render();
    await this.saveData();
  }

  // ── Sélecteur de profil (admin uniquement) ─────────────────
  renderProfileSelector() {
    if (!this.isAdmin || this.allProfiles.length <= 1) return "";
    const activeId = getActiveProfileId();
    return `
      <div style="display:flex; align-items:center; gap:10px; margin-top:12px; padding:10px 14px; border-radius:12px; background:rgba(255,152,0,0.1); border:1px solid rgba(255,152,0,0.3);">
        <span style="font-size:13px; color:var(--secondary-text-color);">👑 Admin — Profil :</span>
        <select id="profileSelector" style="flex:1; border-radius:8px; border:1px solid rgba(120,120,120,0.3); padding:6px 10px; font-size:14px; background:var(--card-background-color,#fff); color:var(--primary-text-color);">
          ${this.allProfiles.map(p => `
            <option value="${this.escapeAttr(p.id)}" ${p.id === activeId ? "selected" : ""}>
              ${this.escapeHtml(p.name)}
            </option>
          `).join("")}
        </select>
      </div>
    `;
  }

  // ── Popup historique ───────────────────────────────────────
  renderHistoryDetailPopup() {
    if (!this.historyDetailDate) return "";
    const entries = this.getEntriesForDate(this.historyDetailDate);
    const groups  = this.getHistoryDetailGroups(this.historyDetailDate);
    const total   = entries.reduce((s, i) => s + this.toNumber(i.calories, 0), 0);
    return `
      <div class="popup-overlay" data-close-history-popup>
        <div class="popup-card" role="dialog" aria-modal="true" data-popup-card>
          <div class="popup-header">
            <div>
              <h2>Détail du ${this.escapeHtml(this.formatDateLong(this.historyDetailDate))}</h2>
              <div class="muted">${entries.length} saisie(s) • ${total} kcal</div>
            </div>
            <button type="button" class="popup-close" data-close-history-popup>Fermer</button>
          </div>
          <div class="popup-content">
            ${groups.length === 0
              ? `<p class="muted">Aucune saisie pour cette journée.</p>`
              : groups.map(group => `
                  <div class="popup-group">
                    <h3>${this.escapeHtml(group.category)}</h3>
                    <div class="popup-list">
                      ${group.items.map(item => `
                        <div class="popup-item">
                          <div>
                            <strong>${this.escapeHtml(item.name)}</strong>
                            <div class="muted">${item.quantity != null ? `${this.escapeHtml(item.quantity)} ${this.escapeHtml(item.quantityUnit || "")}`.trim() : "Saisie libre"}</div>
                          </div>
                          <div><strong>${this.toNumber(item.calories, 0)} kcal</strong></div>
                        </div>
                      `).join("")}
                    </div>
                  </div>
                `).join("")}
          </div>
        </div>
      </div>
    `;
  }

  renderCurrentView() {
    if (this.currentView === "foods")   return renderFoodsView(this);
    if (this.currentView === "day")     return renderDayView(this);
    if (this.currentView === "history") return renderHistoryView(this);
    return renderHomeView(this);
  }

  // ── Gestion des événements ─────────────────────────────────
  handleInput(event) {
    const t = event.target;
    if (!(t instanceof HTMLElement)) return;
    if (t.id === "foodName")       { this.foodForm.name = t.value; return; }
    if (t.id === "quantity")       { this.foodForm.quantity = t.value; this.updateComputedCaloriesUI(); this.updateComputedProteinsUI(); return; }
    if (t.id === "manualCalories") { this.foodForm.calories = t.value; return; }
    if (t.id === "manualProteins") { this.foodForm.proteins = t.value; return; }
    if (t.id === "newFoodName")    { this.newFood.name = t.value; return; }
    if (t.id === "newFoodUnit")    { this.newFood.unitLabel = t.value; return; }
    if (t.id === "newFoodCalories"){ this.newFood.caloriesValue = t.value; return; }
    if (t.id === "newFoodProteins"){ this.newFood.proteinsValue = t.value; return; }
    if (t.id === "goal")           { this.setGoal(t.value); return; }
    if (t.id === "proteinGoal")    { this.setProteinGoal(t.value); return; }
    if (t.hasAttribute("data-edit-name")     && this.editingFood) { this.editingFood.name = t.value; return; }
    if (t.hasAttribute("data-edit-unit")     && this.editingFood) { this.editingFood.unitLabel = t.value; return; }
    if (t.hasAttribute("data-edit-calories") && this.editingFood) { this.editingFood.caloriesValue = t.value; return; }
    if (t.hasAttribute("data-edit-proteins") && this.editingFood) { this.editingFood.proteinsValue = t.value; }
  }

  async handleChange(event) {
    const t = event.target;
    if (!(t instanceof HTMLElement)) return;

    // Sélecteur de profil (admin)
    if (t.id === "profileSelector") { await this.switchProfile(t.value); return; }

    if (t.id === "selectedDate")  { this.setSelectedDate(t.value); return; }
    if (t.id === "goal")          { this.setGoal(t.value); await this.saveData(); return; }
    if (t.id === "proteinGoal")   { this.setProteinGoal(t.value); await this.saveData(); return; }
    if (t.id === "templateId")    { this.updateFoodTemplate(t.value); return; }
    if (t.id === "category")      { this.foodForm.category = t.value; return; }
    if (t.id === "newFoodMode")   { this.updateNewFoodMode(t.value); return; }
    if (t.id === "newFoodCategory") { this.newFood.defaultCategory = t.value; return; }
    if (t.hasAttribute("data-edit-mode"))     { this.updateEditingFoodMode(t.value); return; }
    if (t.hasAttribute("data-edit-category") && this.editingFood) { this.editingFood.defaultCategory = t.value; }

    if (t.id === "aiPhotoCamera"    && t.files?.[0]) { await this.analyzePhoto(t.files[0]); return; }
    if (t.id === "aiPhotoGallery"   && t.files?.[0]) { await this.analyzePhoto(t.files[0]); return; }
    if (t.id === "aiPhotoInput"     && t.files?.[0]) { await this.analyzePhoto(t.files[0]); return; }
    if (t.id === "foodsPhotoCamera" && t.files?.[0]) { await this.analyzePhotoForLibrary(t.files[0]); return; }
    if (t.id === "foodsPhotoGallery"&& t.files?.[0]) { await this.analyzePhotoForLibrary(t.files[0]); return; }
    if (t.id === "libFoodMode" && this.saveToLibraryData) { this.saveToLibraryData.mode = t.value; this.render(); }
  }

  async handleClick(event) {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;

    const popupCloseTarget = target.closest("[data-close-history-popup]");
    const insidePopupCard  = target.closest("[data-popup-card]");
    if (popupCloseTarget && (!insidePopupCard || popupCloseTarget.tagName === "BUTTON")) {
      this.closeHistoryDetail(); return;
    }

    const closeAddTarget = target.closest("[data-close-add-popup]");
    if (closeAddTarget) {
      if (!target.closest("[data-popup-card]") || closeAddTarget.tagName === "BUTTON") {
        this.closeAddPopup(); return;
      }
    }

    const closeDayTarget = target.closest("[data-close-day-detail]");
    if (closeDayTarget) {
      if (!target.closest("[data-popup-card]") || closeDayTarget.tagName === "BUTTON") {
        this.closeDayDetail(); return;
      }
    }

    const closeMealTarget = target.closest("[data-close-meal-detail]");
    if (closeMealTarget) {
      if (!target.closest("[data-popup-card]") || closeMealTarget.tagName === "BUTTON") {
        this.closeMealDetail(); return;
      }
    }

    const openMealDetail = target.closest("[data-open-meal-detail]");
    if (openMealDetail && !target.closest("[data-open-add-popup]")) {
      this.openMealDetail(openMealDetail.getAttribute("data-open-meal-detail")); return;
    }

    const openDayDetail = target.closest("[data-open-day-detail]");
    if (openDayDetail) { this.openDayDetail(); return; }

    const openAddPopup = target.closest("[data-open-add-popup]");
    if (openAddPopup) { this.openAddPopup(openAddPopup.getAttribute("data-open-add-popup")); return; }

    const historyTarget = target.closest("[data-history-date]");
    if (historyTarget) { this.openHistoryDetail(historyTarget.getAttribute("data-history-date")); return; }

    const button = target.closest("button");
    if (!button || this.loading) return;

    const view = button.getAttribute("data-view");
    if (view) { this.setView(view); return; }

    if (button.id === "addEntryBtn")  { await this.addEntry(); return; }
    if (button.id === "addFoodBtn")   { await this.addFood(); return; }

    const editFoodId   = button.getAttribute("data-edit-food");
    if (editFoodId)   { this.startEditFood(editFoodId); return; }

    const deleteFoodId = button.getAttribute("data-delete-food");
    if (deleteFoodId) { await this.deleteFood(deleteFoodId); return; }

    const deleteEntryId = button.getAttribute("data-delete-entry");
    if (deleteEntryId) { await this.deleteEntry(deleteEntryId); return; }

    if (button.hasAttribute("data-confirm-ai-result"))  { this.confirmAiResult(); return; }
    if (button.hasAttribute("data-cancel-ai-result"))   { this.cancelAiResult(); return; }
    if (button.hasAttribute("data-close-save-library")) { this.closeSaveToLibrary(); return; }
    if (button.hasAttribute("data-confirm-save-library")){ await this.confirmSaveToLibrary(); return; }

    if (button.hasAttribute("data-toggle-edit-goal")) {
      if (this.editingGoal) await this.saveData();
      this.toggleEditGoal(); return;
    }
    if (button.hasAttribute("data-toggle-edit-protein-goal")) {
      if (this.editingProteinGoal) await this.saveData();
      this.toggleEditProteinGoal(); return;
    }
    if (button.hasAttribute("data-save-food"))   { await this.saveEditedFood(); return; }
    if (button.hasAttribute("data-cancel-food")) { this.cancelEditFood(); }
  }

  // ── Styles ─────────────────────────────────────────────────
  styles() {
    return `
      <style>
        :host { display:block; color:var(--primary-text-color); font-family:Arial,sans-serif; box-sizing:border-box; }
        .app-shell { max-width:1200px; margin:0 auto; padding:16px; display:flex; flex-direction:column; gap:16px; }
        .app-shell.is-loading { opacity:0.92; }
        .header-card { background:linear-gradient(135deg,rgba(33,150,243,0.14),rgba(120,120,120,0.08)); border-radius:22px; padding:20px; box-shadow:0 4px 14px rgba(0,0,0,0.08); border:1px solid rgba(120,120,120,0.12); }
        .header-top { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; flex-wrap:wrap; }
        .title-block { display:flex; flex-direction:column; gap:6px; }
        .app-title { font-size:30px; font-weight:800; line-height:1.1; }
        .profile-name { font-size:15px; color:var(--secondary-text-color); font-weight:600; }
        .version-badge { display:inline-flex; align-items:center; justify-content:center; padding:8px 12px; border-radius:999px; background:var(--card-background-color,#fff); border:1px solid rgba(120,120,120,0.18); font-size:12px; font-weight:700; min-width:90px; }
        .tabs { display:flex; gap:8px; flex-wrap:wrap; margin-top:18px; }
        .tab-btn { width:auto; border-radius:999px; padding:10px 16px; font-size:14px; font-weight:700; border:1px solid rgba(120,120,120,0.35); background:rgba(120,120,120,0.15); color:var(--primary-text-color); cursor:pointer; transition:transform 0.15s ease; }
        .tab-btn:hover { background:rgba(120,120,120,0.25); transform:translateY(-1px); }
        .tab-btn.active { background:var(--primary-color,#2196f3); color:white; border-color:transparent; }
        .status-bar { background:rgba(33,150,243,0.12); border:1px solid rgba(33,150,243,0.25); border-radius:12px; padding:10px 14px; font-size:13px; font-weight:600; }
        .hidden { display:none; }
        .content { display:flex; flex-direction:column; gap:16px; }
        .hero-card,.section-card,.stat-card { background:var(--card-background-color,#fff); border-radius:20px; padding:18px; box-shadow:0 2px 8px rgba(0,0,0,0.06); border:1px solid rgba(33,150,243,0.12); }
        .hero-title { font-size:28px; font-weight:800; margin-bottom:6px; }
        .summary-grid { display:grid; grid-template-columns:repeat(2,1fr); gap:12px; }
        .summary-card { display:flex; align-items:center; justify-content:space-between; gap:12px; border:1px solid rgba(33,150,243,0.5); background:rgba(33,150,243,0.1); border-radius:18px; padding:16px; }
        .summary-left { flex:1; }
        .summary-right { flex-shrink:0; width:160px; margin-right:40px; }
        .hero-grid-2 { display:grid; grid-template-columns:repeat(2,1fr); gap:12px; }
        .hero-tile { text-align:left; min-height:130px; border-radius:18px; padding:18px; border:1px solid rgba(33,150,243,0.5); background:rgba(33,150,243,0.1); display:flex; flex-direction:column; justify-content:space-between; cursor:pointer; transition:border-color 0.15s ease,background 0.15s ease; }
        .hero-tile:hover { border-color:rgba(33,150,243,0.7); background:rgba(33,150,243,0.15); }
        .hero-tile-title { font-size:18px; font-weight:800; }
        .hero-tile-value { font-size:36px; font-weight:800; line-height:1; }
        .hero-tile-sub { font-size:13px; color:var(--secondary-text-color); }
        .stats-grid { display:grid; grid-template-columns:repeat(2,1fr); gap:16px; }
        .big { font-size:30px; font-weight:800; margin-top:6px; }
        .muted { color:var(--secondary-text-color); font-size:13px; }
        h1,h2,h3,p { margin:0; }
        h2 { font-size:20px; margin-bottom:2px; }
        .row { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-top:12px; }
        .row-2 { display:grid; grid-template-columns:repeat(2,1fr); gap:12px; margin-top:12px; }
        .full { grid-column:1/-1; }
        label { display:block; font-size:13px; margin-bottom:6px; color:var(--secondary-text-color); }
        input,select,button { width:100%; box-sizing:border-box; border-radius:12px; border:1px solid rgba(120,120,120,0.24); padding:11px 12px; font-size:14px; background:var(--card-background-color,#fff); color:var(--primary-text-color); }
        input[readonly] { opacity:0.82; }
        button { cursor:pointer; font-weight:700; }
        button:disabled { cursor:not-allowed; opacity:0.6; }
        .actions { display:flex; gap:8px; flex-wrap:wrap; }
        .item { border:1px solid rgba(120,120,120,0.14); border-radius:14px; padding:10px 14px; display:flex; justify-content:space-between; gap:12px; align-items:center; margin-top:8px; background:rgba(120,120,120,0.03); }
        .item .actions { display:flex; flex-direction:row; gap:8px; flex-shrink:0; }
        .item .actions button { width:auto; padding:6px 12px; font-size:13px; }
        .chart-wrap { margin-top:14px; overflow-x:auto; }
        .chart-svg { width:100%; min-width:720px; height:auto; }
        .axis-line { stroke:rgba(120,120,120,0.35); stroke-width:1; }
        .axis-label { fill:currentColor; font-size:11px; }
        .value-label { fill:currentColor; font-size:11px; font-weight:700; }
        .chart-grid { stroke:rgba(120,120,120,0.18); stroke-width:1; stroke-dasharray:4 4; }
        .chart-area { fill:rgba(33,150,243,0.10); }
        .chart-line { fill:none; stroke:rgba(33,150,243,0.95); stroke-width:3; stroke-linecap:round; stroke-linejoin:round; }
        .chart-point { fill:white; stroke:rgba(33,150,243,0.95); stroke-width:3; cursor:pointer; }
        .history-list { display:flex; flex-direction:column; gap:10px; margin-top:12px; }
        .history-row { display:flex; justify-content:space-between; align-items:center; gap:12px; text-align:left; border-radius:14px; padding:12px 14px; border:1px solid rgba(33,150,243,0.2); background:rgba(33,150,243,0.05); transition:border-color 0.15s ease,background 0.15s ease; }
        .history-row:hover { border-color:rgba(33,150,243,0.4); background:rgba(33,150,243,0.1); }
        .history-subline { display:block; margin-top:4px; }
        .popup-overlay { position:fixed; inset:0; background:rgba(0,0,0,0.42); display:flex; align-items:center; justify-content:center; padding:16px; z-index:9999; }
        .popup-card { width:min(760px,100%); max-height:85vh; overflow:auto; background:var(--card-background-color,#fff); color:var(--primary-text-color); border-radius:22px; padding:18px; box-shadow:0 14px 36px rgba(0,0,0,0.24); }
        .popup-header { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; margin-bottom:12px; }
        .popup-close { width:auto; min-width:96px; }
        .popup-content { display:flex; flex-direction:column; gap:14px; }
        .popup-group { border:1px solid rgba(120,120,120,0.14); border-radius:16px; padding:14px; background:rgba(120,120,120,0.03); }
        .popup-group h3 { font-size:16px; margin:0 0 10px; }
        .popup-list { display:flex; flex-direction:column; gap:10px; }
        .popup-item { display:flex; justify-content:space-between; gap:12px; align-items:center; padding:10px 0; border-top:1px solid rgba(120,120,120,0.12); }
        .popup-item:first-child { border-top:none; padding-top:0; }
        .ai-photo-zone { border:1px solid rgba(33,150,243,0.28); border-radius:16px; padding:14px; background:rgba(33,150,243,0.05); }
        .ai-photo-title { font-weight:700; font-size:15px; margin-bottom:10px; }
        .ai-photo-btn { display:inline-flex; align-items:center; justify-content:center; padding:11px 16px; border-radius:12px; border:1px solid rgba(33,150,243,0.4); background:var(--primary-color,#2196f3); color:white; font-size:14px; font-weight:700; cursor:pointer; width:100%; box-sizing:border-box; }
        .ai-result-box { margin-top:14px; border:1px solid rgba(33,150,243,0.3); border-radius:14px; padding:14px; background:rgba(33,150,243,0.07); }
        .ai-result-title { font-weight:700; font-size:14px; margin-bottom:10px; color:var(--primary-color,#2196f3); }
        .ai-separator { text-align:center; color:var(--secondary-text-color); font-size:13px; position:relative; margin:4px 0; }
        .meals-grid { display:grid; grid-template-columns:repeat(2,1fr); gap:12px; }
        .meal-block { border:1px solid rgba(120,120,120,0.14); border-radius:16px; padding:14px; background:rgba(120,120,120,0.03); display:flex; flex-direction:column; gap:10px; transition:border-color 0.15s ease; }
        .meal-block--active { border-color:rgba(33,150,243,0.5); background:rgba(33,150,243,0.1); }
        .meal-header { display:flex; justify-content:space-between; align-items:center; gap:8px; }
        .meal-name { font-size:15px; }
        .meal-add-btn { width:36px; min-width:36px; height:36px; padding:0; border-radius:50%; font-size:22px; font-weight:700; background:var(--primary-color,#2196f3); color:white; border:none; cursor:pointer; display:flex; align-items:center; justify-content:center; flex-shrink:0; }
        .meal-summary { display:flex; flex-direction:column; gap:2px; }
        .meal-kcal { font-size:22px; font-weight:800; }
        .gauge-wrap { position:relative; width:200px; height:110px; margin:0 auto; }
        .gauge-svg { width:200px; height:110px; overflow:visible; }
        .gauge-text-center { position:absolute; bottom:0; left:0; right:0; text-align:center; }
        .gauge-remaining { font-size:18px; font-weight:800; line-height:1; }
        .gauge-label { font-size:12px; color:var(--secondary-text-color); margin-top:2px; }
        .day-header-card { border-color:rgba(33,150,243,0.5); background:rgba(33,150,243,0.15); }
        .day-header-row { display:flex; align-items:center; gap:12px; }
        .day-header-label { font-size:13px; color:var(--secondary-text-color); white-space:nowrap; }
        .day-date-input { width:auto !important; padding:7px 10px; font-size:14px; border-radius:10px; }
        @media (max-width:1000px) {
          .hero-grid-2,.summary-grid,.stats-grid,.row,.row-2,.meals-grid { grid-template-columns:1fr; }
          .summary-card { flex-direction:column; align-items:stretch; }
          .summary-right { width:100%; }
          .item { flex-direction:column; align-items:stretch; }
        }
      </style>
    `;
  }

  // ── Rendu ──────────────────────────────────────────────────
  render() {
    this.shadowRoot.innerHTML = `
      ${this.styles()}
      <div class="app-shell ${this.loading ? "is-loading" : ""}">
        <div class="header-card">
          <div class="header-top">
            <div class="title-block">
              <div class="app-title">Suivi Alimentation</div>
              ${this.currentProfileName
                ? `<div class="profile-name">👤 ${this.escapeHtml(this.currentProfileName)}</div>`
                : ""}
            </div>
            <div class="version-badge">${this.escapeHtml(this.appVersion)}</div>
          </div>

          ${this.renderProfileSelector()}
          ${renderTabs(this)}
        </div>

        <div class="content">
          ${this.message ? `<div class="status-bar" id="statusMessage">${this.escapeHtml(this.message)}</div>` : ""}
          ${this.renderCurrentView()}
        </div>

        ${this.renderHistoryDetailPopup()}
      </div>
    `;

    this.updateLoadingUI();
    this.updateStatusUI();
    this.updateSummaryUI();
    this.updateComputedCaloriesUI();
    this.updateComputedProteinsUI();
  }
}

if (!customElements.get("calorie-tracker-panel")) {
  customElements.define("calorie-tracker-panel", CalorieTrackerPanel);
}

function renderMiniGauge(panel, total, goal, unit, elementId) {
  const pct = goal > 0 ? Math.min(Math.max(total / goal, 0), 1) : 0;
  const cx = 60, cy = 58, r = 45;
  const arcLength = Math.PI * r;
  const filled = arcLength * pct;
  const gap = arcLength - filled;

  let color = "#4caf50";
  if (pct >= 1) color = "#e53935";
  else if (pct >= 0.8) color = "#ff9800";

  const remaining = goal - total;
  const remainingText = remaining >= 0
    ? `${panel.formatNumber(remaining)} ${unit}`
    : `+${panel.formatNumber(Math.abs(remaining))} ${unit}`;

  return `
    <div style="position:relative; width:120px; height:66px;">
      <svg viewBox="0 0 120 66" style="width:120px; height:66px;">
        <path d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
          fill="none" stroke="rgba(120,120,120,0.2)" stroke-width="10" stroke-linecap="round"/>
        ${pct > 0.01 ? `<path d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
          fill="none" stroke="${color}" stroke-width="10" stroke-linecap="round"
          stroke-dasharray="${filled.toFixed(2)} ${(gap + 10).toFixed(2)}"/>` : ""}
      </svg>
      <div style="position:absolute; bottom:0; left:0; right:0; text-align:center;">
        <div style="font-size:12px; font-weight:800; color:${color};" id="${elementId}">${remainingText}</div>
        <div style="font-size:10px; color:var(--secondary-text-color);">${remaining >= 0 ? "restants" : "dépassés"}</div>
      </div>
    </div>
  `;
}

function renderSummaryCard(label, total, goal, unit, elementId) {
  const pct = goal > 0 ? Math.min(Math.max(total / goal, 0), 1) : 0;
  const cx = 100, cy = 95, r = 75;
  const arcLength = Math.PI * r;
  const filled = arcLength * pct;
  const gap = arcLength - filled;

  let gaugeColor = "#4caf50";
  if (pct >= 1) gaugeColor = "#e53935";
  else if (pct >= 0.8) gaugeColor = "#ff9800";

  const remaining = goal - total;
  const remainingText = remaining >= 0
    ? `${Number(remaining).toLocaleString("fr-FR")} ${unit}`
    : `+${Number(Math.abs(remaining)).toLocaleString("fr-FR")} ${unit}`;
  const remainingLabel = remaining >= 0 ? "restants" : "dépassés";

  return `
    <div class="summary-card">
      <div class="summary-left">
        <strong class="meal-name">${label}</strong>
        <div class="big" style="margin-top:6px;" id="${elementId}-total">${Number(total).toLocaleString("fr-FR")} ${unit}</div>
        <div class="muted" style="margin-top:8px;">sur <strong id="${elementId}-goal">${Number(goal).toLocaleString("fr-FR")} ${unit}</strong></div>
      </div>
      <div class="summary-right">
        <div class="gauge-wrap">
          <svg class="gauge-svg" viewBox="0 0 200 110">
            <path d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
              fill="none" stroke="rgba(120,120,120,0.2)" stroke-width="14" stroke-linecap="round"/>
            ${pct > 0.01 ? `<path d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
              fill="none" stroke="${gaugeColor}" stroke-width="14" stroke-linecap="round"
              stroke-dasharray="${filled.toFixed(2)} ${(gap + 20).toFixed(2)}"/>` : ""}
          </svg>
          <div class="gauge-text-center">
            <div class="gauge-remaining" style="color:${gaugeColor};" id="${elementId}">${remainingText}</div>
            <div class="gauge-label">${remainingLabel}</div>
          </div>
        </div>
      </div>
    </div>
  `;
}

export function renderDayView(panel) {
  const categoryIcons = {
    "Petit-déjeuner": "☀️",
    "Déjeuner": "🌿",
    "Dîner": "🌙",
    "Collation": "🍎",
  };

  const groupedEntries = panel.categories.map((category) => {
    const items = panel.dayEntries.filter((item) => item.category === category);
    const total = items.reduce((sum, item) => sum + panel.toNumber(item.calories, 0), 0);
    return { category, items, total };
  });

  return `
    <!-- Bannière photo en attente (deep link Android) -->
    ${panel.pendingPhotoCategory ? `
      <div style="background:var(--primary-color,#2196f3);border-radius:20px;padding:20px;text-align:center;box-shadow:0 4px 14px rgba(33,150,243,0.35);margin-bottom:0;">
        <div style="color:white;font-size:16px;font-weight:700;margin-bottom:6px;">📷 Photo pour — ${panel.escapeHtml(panel.pendingPhotoCategory)}</div>
        <div style="color:rgba(255,255,255,0.85);font-size:13px;margin-bottom:16px;">Appuie sur le bouton ci-dessous pour analyser ta photo</div>
        <label for="pendingPhotoInput" style="display:block;background:white;color:var(--primary-color,#2196f3);border-radius:14px;padding:16px;font-size:18px;font-weight:800;cursor:pointer;">
          📷 Prendre ou choisir une photo
        </label>
        <input type="file" id="pendingPhotoInput" accept="image/*" capture="environment" style="display:none;">
        <button type="button" data-cancel-pending-photo style="margin-top:12px;background:transparent;border:1px solid rgba(255,255,255,0.5);color:white;font-size:13px;width:auto;padding:8px 16px;">Annuler</button>
      </div>
    ` : ""}
    <!-- Carte unique Ma journée -->
    <div class="section-card day-header-card">

      <!-- Titre + bouton détail -->
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
        <h2 style="margin:0;">Ma journée</h2>
        <button
          type="button"
          data-open-day-detail
          style="width:auto; padding:8px 16px; font-size:13px;"
        >Voir le détail à la journée</button>
      </div>

      <!-- Ligne date -->
      <div class="day-header-row">
        <span class="day-header-label">Date</span>
        <input type="date" id="selectedDate" value="${panel.escapeAttr(panel.selectedDate)}" class="day-date-input">
      </div>

      <!-- Ligne objectifs -->
      <div class="day-header-row" style="margin-top:12px; flex-wrap:wrap; gap:24px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <span class="day-header-label">Objectif calorique</span>
          ${panel.editingGoal
            ? `<input type="number" id="goal" value="${panel.escapeAttr(Number(panel.data.goal ?? 0))}" style="width:100px;">
               <button type="button" data-toggle-edit-goal style="width:auto; padding:6px 12px;">✓ OK</button>`
            : `<span style="font-size:18px; font-weight:800;">${panel.formatNumber(panel.data.goal ?? 0)} kcal</span>
               <button type="button" data-toggle-edit-goal style="width:auto; padding:4px 8px; font-size:13px;">✏️</button>`
          }
        </div>
        <div style="display:flex; align-items:center; gap:10px;">
          <span class="day-header-label">Objectif protéines</span>
          ${panel.editingProteinGoal
            ? `<input type="number" id="proteinGoal" value="${panel.escapeAttr(Number(panel.data.proteinGoal ?? 100))}" style="width:100px;">
               <button type="button" data-toggle-edit-protein-goal style="width:auto; padding:6px 12px;">✓ OK</button>`
            : `<span style="font-size:18px; font-weight:800;">${panel.formatNumber(panel.data.proteinGoal ?? 100)} g</span>
               <button type="button" data-toggle-edit-protein-goal style="width:auto; padding:4px 8px; font-size:13px;">✏️</button>`
          }
        </div>
      </div>

      <!-- Séparateur -->
      <div style="border-top:1px solid rgba(33,150,243,0.2); margin:16px 0;"></div>

      <!-- Récap jauges style meal-block -->
      <div class="stats-grid" style="margin-bottom:12px;">
        ${renderSummaryCard("Calories consommées", panel.totalCalories, panel.data.goal ?? 0, "kcal", "summaryCalories")}
        ${renderSummaryCard("Protéines consommées", panel.totalProteins, panel.data.proteinGoal ?? 0, "g", "summaryProteins")}
      </div>

      <!-- Séparateur -->
      <div style="border-top:1px solid rgba(33,150,243,0.2); margin:16px 0;"></div>

      <!-- 4 blocs repas -->
      <div class="meals-grid">
        ${groupedEntries.map((group) => {
          const groupProteins = group.items.reduce((sum, item) => sum + panel.toNumber(item.proteins, 0), 0);

          const illustrations = {
            "Petit-déjeuner": `
              <svg viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="10" y="42" width="38" height="24" rx="5" stroke="white" stroke-width="3.5"/>
                <path d="M48 49 Q60 49 60 58 Q60 67 48 67" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <line x1="5" y1="70" x2="55" y2="70" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <path d="M18 36 Q21 28 18 20" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
                <path d="M28 36 Q31 26 28 18" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
                <path d="M38 36 Q41 28 38 20" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
              </svg>`,
            "Déjeuner": `
              <svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="50" cy="55" r="32" stroke="white" stroke-width="3.5"/>
                <circle cx="50" cy="55" r="20" stroke="white" stroke-width="2"/>
                <line x1="18" y1="18" x2="18" y2="42" stroke="white" stroke-width="3" stroke-linecap="round"/>
                <line x1="13" y1="18" x2="13" y2="30" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
                <line x1="23" y1="18" x2="23" y2="30" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
                <line x1="32" y1="18" x2="32" y2="42" stroke="white" stroke-width="3" stroke-linecap="round"/>
                <path d="M32 18 Q38 25 32 34" stroke="white" stroke-width="2.5" fill="none"/>
              </svg>`,
            "Dîner": `
              <svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="18" y="42" width="55" height="35" rx="6" stroke="white" stroke-width="3.5"/>
                <line x1="73" y1="52" x2="88" y2="52" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <line x1="73" y1="62" x2="88" y2="62" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <line x1="12" y1="52" x2="18" y2="52" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <line x1="12" y1="62" x2="18" y2="62" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <path d="M18 42 Q45 28 73 42" stroke="white" stroke-width="3.5" stroke-linecap="round"/>
                <line x1="45" y1="28" x2="45" y2="20" stroke="white" stroke-width="3" stroke-linecap="round"/>
                <circle cx="45" cy="18" r="3" fill="white"/>
              </svg>`,
            "Collation": `
              <svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M50 30 Q25 30 22 58 Q20 80 35 88 Q42 92 50 88 Q58 92 65 88 Q80 80 78 58 Q75 30 50 30Z" stroke="white" stroke-width="3.5"/>
                <path d="M50 30 Q52 18 62 15" stroke="white" stroke-width="3" stroke-linecap="round"/>
                <path d="M57 20 Q68 14 70 22 Q65 28 57 20Z" stroke="white" stroke-width="2.5"/>
              </svg>`,
          };

          return `
          <div class="meal-block meal-block--active"
               data-open-meal-detail="${panel.escapeAttr(group.category)}"
               style="cursor:pointer; position:relative; overflow:hidden;"
          >
            <!-- Illustration en filigrane centre-droit -->
            <div style="position:absolute; right:52px; top:50%; transform:translateY(-50%); opacity:0.12; width:70px; height:70px; pointer-events:none;">
              ${illustrations[group.category] || ""}
            </div>

            <div class="meal-header">
              <strong class="meal-name">${panel.escapeHtml(group.category)}</strong>
              <button
                type="button"
                class="meal-add-btn"
                data-open-add-popup="${panel.escapeAttr(group.category)}"
                title="Ajouter un aliment à ${panel.escapeHtml(group.category)}"
              >+</button>
            </div>
            <div class="meal-summary">
              <div style="display:flex; align-items:center; gap:8px;">
                <span class="meal-kcal">${panel.formatNumber(group.total)} kcal</span>
                <span style="color:var(--secondary-text-color);">•</span>
                <span class="meal-kcal">${panel.formatNumber(groupProteins)} g prot.</span>
              </div>
              <div class="muted" style="margin-top:8px;">${group.items.length} aliment(s) enregistré(s)</div>
            </div>
          </div>
        `}).join("")}
      </div>

    </div>

    <!-- Popup ajout par repas -->
    ${renderAddPopup(panel)}

    <!-- Popup détail journée complète -->
    ${renderDayDetailPopup(panel, groupedEntries)}

    <!-- Popup détail par repas -->
    ${renderMealDetailPopup(panel, groupedEntries)}

    <!-- Popup bibliothèque depuis IA -->
    ${renderSaveToLibraryPopup(panel)}
  `;
}

function renderGauge(panel, total, goal, elementId, unit) {
  const safeGoal = panel.toNumber(goal, 0);
  const safeTotal = panel.toNumber(total, 0);
  const remaining = safeGoal - safeTotal;
  const pct = safeGoal > 0 ? Math.min(Math.max(safeTotal / safeGoal, 0), 1) : 0;

  let color = "#4caf50";
  if (pct >= 1) color = "#e53935";
  else if (pct >= 0.8) color = "#ff9800";

  const cx = 100, cy = 95, r = 75;
  const arcLength = Math.PI * r;
  const filled = arcLength * pct;
  const gap = arcLength - filled;

  const remainingText = remaining >= 0
    ? `${panel.formatNumber(remaining)} ${unit}`
    : `+${panel.formatNumber(Math.abs(remaining))} ${unit}`;
  const remainingLabel = remaining >= 0 ? "restants" : "dépassés";

  return `
    <div class="gauge-wrap">
      <svg class="gauge-svg" viewBox="0 0 200 110">
        <path
          d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
          fill="none"
          stroke="rgba(120,120,120,0.2)"
          stroke-width="14"
          stroke-linecap="round"
        />
        ${pct > 0.01 ? `
        <path
          d="M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}"
          fill="none"
          stroke="${color}"
          stroke-width="14"
          stroke-linecap="round"
          stroke-dasharray="${filled.toFixed(2)} ${(gap + 20).toFixed(2)}"
        />` : ""}
      </svg>
      <div class="gauge-text-center">
        <div class="gauge-remaining" style="color:${color};" id="${elementId}">${remainingText}</div>
        <div class="gauge-label">${remainingLabel}</div>
      </div>
    </div>
  `;
}

// ── Détection Android ────────────────────────────────────────────────────────
// On détecte Android une seule fois au chargement du module.
const isAndroid = /android/i.test(navigator.userAgent);

function renderPhotoButton(inputId, label) {
  // Sur Android : deep link vers l'appareil photo natif HA
  if (isAndroid) {
    return `
      <a
        href="homeassistant://navigate/suivi-alimentation?modal=food_camera&target=${inputId}"
        class="ai-photo-btn"
        style="display:block; text-align:center; text-decoration:none;"
      >
        📷 ${label}
      </a>
    `;
  }
  // Sur iOS et bureau : input file classique
  return `
    <label class="ai-photo-btn" for="${inputId}" style="text-align:center; display:block; cursor:pointer;">
      📷 ${label}
    </label>
    <input type="file" id="${inputId}" accept="image/*" style="display:none;">
  `;
}

function renderAddPopup(panel) {
  if (!panel.addPopupCategory) return "";

  const food = panel.selectedFood;
  const category = panel.addPopupCategory;
  const ai = panel.aiResult;

  return `
    <div class="popup-overlay" data-close-add-popup>
      <div class="popup-card" role="dialog" aria-modal="true" aria-label="Ajouter un aliment" data-popup-card>
        <div class="popup-header">
          <div>
            <h2>Ajouter — ${panel.escapeHtml(category)}</h2>
            <div class="muted">Le popup reste ouvert pour enchaîner les ajouts.</div>
          </div>
          <button type="button" class="popup-close" data-close-add-popup>Fermer</button>
        </div>

        <div class="popup-content">

          <!-- Zone analyse photo IA -->
          <div class="ai-photo-zone">
            <div class="ai-photo-title">📷 Analyser une photo</div>
            <div class="ai-photo-row">
              ${panel.aiLoading
                ? `<div class="ai-photo-btn" style="opacity:0.7; cursor:not-allowed;">⏳ Analyse en cours...</div>`
                : renderPhotoButton("aiPhotoInput", "Prendre ou choisir une photo")
              }
            </div>

            ${ai ? `
              <!-- Résultat IA à confirmer -->
              <div class="ai-result-box">
                <div class="ai-result-title">✨ Proposition de l'IA</div>
                <div class="row">
                  <div class="full">
                    <label>Nom du repas</label>
                    <input id="aiResultName" value="${panel.escapeAttr(ai.name)}">
                  </div>
                  <div>
                    <label>Calories estimées (kcal)</label>
                    <input type="number" id="aiResultCalories" value="${panel.escapeAttr(ai.calories)}">
                  </div>
                  <div>
                    <label>Protéines estimées (g)</label>
                    <input type="number" id="aiResultProteins" value="${panel.escapeAttr(ai.proteins)}">
                  </div>
                  <div class="full">
                    <div class="muted" style="font-style:italic; margin-bottom:8px;">
                      💡 ${panel.escapeHtml(ai.description || "")}
                    </div>
                  </div>
                  <div class="full actions">
                    <button type="button" data-confirm-ai-result>✅ Utiliser ces valeurs</button>
                    <button type="button" data-cancel-ai-result>Annuler</button>
                  </div>
                </div>
              </div>
            ` : ""}

            ${panel.aiError ? `
              <div class="muted" style="color:#e53935; margin-top:8px;">
                ⚠️ ${panel.escapeHtml(panel.aiError)}
              </div>
            ` : ""}
          </div>

          <!-- Séparateur -->
          <div class="ai-separator">ou saisie manuelle</div>

          <!-- Formulaire classique -->
          <div class="row">
            <div class="full">
              <label>Aliment enregistré</label>
              <select id="templateId">
                <option value="custom" ${panel.foodForm.templateId === "custom" ? "selected" : ""}>Saisie libre</option>
                ${[...panel.data.foods]
                  .sort((a, b) => a.name.localeCompare(b.name, "fr"))
                  .map((item) => `
                    <option value="${panel.escapeAttr(item.id)}" ${panel.foodForm.templateId === item.id ? "selected" : ""}>
                      ${panel.escapeHtml(item.name)}
                    </option>
                  `)
                  .join("")}
              </select>
            </div>

            <div class="full">
              <label>Nom</label>
              <input id="foodName" value="${panel.escapeAttr(panel.foodForm.name)}">
            </div>

            ${panel.foodForm.templateId !== "custom" && food
              ? `
                <div>
                  <label>Quantité ${food.mode === "grams" ? "(en grammes)" : `(${panel.escapeHtml(food.unitLabel)})`}</label>
                  <input type="number" id="quantity" value="${panel.escapeAttr(panel.foodForm.quantity)}">
                </div>
                <div>
                  <label>Calories calculées (kcal)</label>
                  <input type="number" id="computedCalories" value="${panel.escapeAttr(panel.computedCalories)}" readonly>
                </div>
                <div>
                  <label>Protéines calculées (g)</label>
                  <input type="number" id="computedProteins" value="${panel.escapeAttr(panel.computedProteins)}" readonly>
                </div>
                <div class="full">
                  <label>Base utilisée</label>
                  <div class="muted">
                    ${food.mode === "grams"
                      ? `${panel.toNumber(food.caloriesPer100g, 0)} kcal / ${panel.toNumber(food.proteinsPer100g, 0)} g protéines pour 100 g`
                      : `${panel.toNumber(food.caloriesPerUnit, 0)} kcal / ${panel.toNumber(food.proteinsPerUnit, 0)} g protéines par ${panel.escapeHtml(food.unitLabel)}`
                    }
                  </div>
                </div>
              `
              : `
                <div>
                  <label>Calories (kcal)</label>
                  <input type="number" id="manualCalories" value="${panel.escapeAttr(panel.foodForm.calories)}">
                </div>
                <div>
                  <label>Protéines (g) — optionnel</label>
                  <input type="number" id="manualProteins" value="${panel.escapeAttr(panel.foodForm.proteins)}">
                </div>
              `
            }

            <div class="full">
              <label>Catégorie</label>
              <select id="category">
                ${panel.categories
                  .map((cat) => `
                    <option value="${panel.escapeAttr(cat)}" ${panel.foodForm.category === cat ? "selected" : ""}>
                      ${panel.escapeHtml(cat)}
                    </option>
                  `)
                  .join("")}
              </select>
            </div>

            <div class="full actions">
              <button id="addEntryBtn">Ajouter au jour</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `;
}

function renderSaveToLibraryPopup(panel) {
  if (!panel.saveToLibraryData) return "";

  const d = panel.saveToLibraryData;

  return `
    <div class="popup-overlay" data-close-save-library>
      <div class="popup-card" role="dialog" aria-modal="true" aria-label="Ajouter à la bibliothèque" data-popup-card>
        <div class="popup-header">
          <div>
            <h2>Enregistrer dans la bibliothèque ?</h2>
            <div class="muted">✅ L'aliment a bien été ajouté à votre journée.</div>
          </div>
        </div>

        <div class="popup-content">
          <div class="row">
            <div class="full">
              <label>Nom de l'aliment</label>
              <input id="libFoodName" value="${panel.escapeAttr(d.name)}">
            </div>

            <div class="full">
              <label>Mode de saisie</label>
              <select id="libFoodMode">
                <option value="unit" ${d.mode === "unit" ? "selected" : ""}>À l'unité</option>
                <option value="grams" ${d.mode === "grams" ? "selected" : ""}>Aux grammes</option>
              </select>
            </div>

            <div class="full">
              <label>Nom de l'unité ${d.mode === "grams" ? "" : "(ex: portion, tranche, bol...)"}</label>
              <input
                id="libFoodUnit"
                value="${panel.escapeAttr(d.mode === "grams" ? "g" : (d.unitLabel || "portion"))}"
                ${d.mode === "grams" ? "readonly" : ""}
              >
            </div>

            <div class="full">
              <label>${d.mode === "grams" ? "Calories pour 100 g (kcal)" : "Calories par unité (kcal)"}</label>
              <input type="number" id="libFoodCalories" value="${panel.escapeAttr(d.calories)}">
            </div>

            <div class="full actions">
              <button type="button" data-confirm-save-library>Oui, enregistrer dans la bibliothèque</button>
              <button type="button" data-close-save-library>Non merci</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `;
}

function renderMealDetailPopup(panel, groupedEntries) {
  if (!panel.mealDetailCategory) return "";

  const group = groupedEntries.find((g) => g.category === panel.mealDetailCategory);
  if (!group) return "";

  const totalProteins = group.items.reduce((sum, item) => sum + panel.toNumber(item.proteins, 0), 0);

  return `
    <div class="popup-overlay" data-close-meal-detail>
      <div class="popup-card" role="dialog" aria-modal="true" aria-label="Détail repas" data-popup-card>
        <div class="popup-header">
          <div>
            <h2>${panel.escapeHtml(group.category)}</h2>
            <div class="muted">${group.items.length} aliment(s) • ${panel.formatNumber(group.total)} kcal • ${panel.formatNumber(totalProteins)} g protéines</div>
          </div>
          <button type="button" class="popup-close" data-close-meal-detail>Fermer</button>
        </div>
        <div class="popup-content">
          ${group.items.length === 0
            ? `<p class="muted">Aucune saisie pour ce repas.</p>`
            : `<div class="popup-list">
                ${group.items.map((item) => `
                  <div class="popup-item">
                    <div>
                      <strong>${panel.escapeHtml(item.name)}</strong>
                      <div class="muted">
                        ${item.quantity !== null && item.quantity !== undefined
                          ? `${panel.escapeHtml(item.quantity)} ${panel.escapeHtml(item.quantityUnit || "")}`.trim()
                          : "Saisie libre"
                        }
                      </div>
                    </div>
                    <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                      <span>${panel.formatNumber(item.calories)} kcal</span>
                      <span class="muted">${panel.formatNumber(panel.toNumber(item.proteins, 0))} g prot.</span>
                      <button
                        type="button"
                        data-delete-entry="${panel.escapeAttr(item.id)}"
                        style="width:auto; padding:6px 12px; font-size:13px;"
                      >Supprimer</button>
                    </div>
                  </div>
                `).join("")}
              </div>`
          }
        </div>
      </div>
    </div>
  `;
}

function renderDayDetailPopup(panel, groupedEntries) {
  if (!panel.dayDetailOpen) return "";

  const total = panel.totalCalories;

  return `
    <div class="popup-overlay" data-close-day-detail>
      <div class="popup-card" role="dialog" aria-modal="true" aria-label="Détail de la journée" data-popup-card>
        <div class="popup-header">
          <div>
            <h2>Détail du ${panel.escapeHtml(panel.formatDateLong(panel.selectedDate))}</h2>
            <div class="muted">${panel.dayEntries.length} saisie(s) • ${total} kcal</div>
          </div>
          <button type="button" class="popup-close" data-close-day-detail>Fermer</button>
        </div>

        <div class="popup-content">
          ${groupedEntries.filter((g) => g.items.length > 0).length === 0
            ? `<p class="muted">Aucune saisie pour cette journée.</p>`
            : groupedEntries
                .filter((g) => g.items.length > 0)
                .map((group) => `
                  <div class="popup-group">
                    <h3>${panel.escapeHtml(group.category)} — ${group.total} kcal</h3>
                    <div class="popup-list">
                      ${group.items.map((item) => `
                        <div class="popup-item">
                          <div>
                            <strong>${panel.escapeHtml(item.name)}</strong>
                            <div class="muted">
                              ${item.quantity !== null && item.quantity !== undefined
                                ? `${panel.escapeHtml(item.quantity)} ${panel.escapeHtml(item.quantityUnit || "")}`.trim()
                                : "Saisie libre"
                              }
                            </div>
                          </div>
                          <div style="display:flex; gap:10px; align-items:center;">
                            <strong>${panel.toNumber(item.calories, 0)} kcal</strong>
                            <button
                              type="button"
                              data-delete-entry="${panel.escapeAttr(item.id)}"
                              style="width:auto; padding:6px 12px; font-size:13px;"
                            >Supprimer</button>
                          </div>
                        </div>
                      `).join("")}
                    </div>
                  </div>
                `).join("")
          }
        </div>
      </div>
    </div>
  `;
}
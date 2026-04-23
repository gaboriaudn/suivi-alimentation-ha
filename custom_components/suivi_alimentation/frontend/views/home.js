export function renderTabs(panel) {
  const tabs = [
    { key: "home", label: "Accueil" },
    { key: "day", label: "Ma Journée" },
    { key: "history", label: "Historique" },
    { key: "foods", label: "Aliments et plats" },
  ];

  return `
    <div class="tabs">
      ${tabs.map((tab) => `
        <button
          class="tab-btn ${panel.currentView === tab.key ? "active" : ""}"
          data-view="${tab.key}"
          type="button"
        >
          ${panel.escapeHtml(tab.label)}
        </button>
      `).join("")}
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
        <div class="big" style="margin-top:6px;">${Number(total).toLocaleString("fr-FR")} ${unit}</div>
        <div class="muted" style="margin-top:8px;">sur <strong>${Number(goal).toLocaleString("fr-FR")} ${unit}</strong></div>
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

export function renderHomeView(panel) {
  const goal = Number(panel.data.goal ?? 0);
  const total = panel.totalCalories;
  const proteinGoal = Number(panel.data.proteinGoal ?? 100);
  const totalProteins = panel.totalProteins;

  return `
    <div class="hero-card">
      <div class="hero-title">Tableau de bord</div>

      <!-- 2 cartes résumé avec jauge intégrée -->
      <div class="summary-grid" style="margin-top:16px;">
        ${renderSummaryCard("Calories consommées", total, goal, "kcal", "homeCalRemaining")}
        ${renderSummaryCard("Protéines consommées", totalProteins, proteinGoal, "g", "homeProtRemaining")}
      </div>

      <!-- 2 tuiles de navigation -->
      <div class="hero-grid-2" style="margin-top:16px;">
        <button class="hero-tile" data-view="history" style="position:relative; overflow:hidden;">
          <!-- Illustration courbe historique en filigrane -->
          <svg style="position:absolute; bottom:5px; right:-10px; opacity:0.12; width:140px; height:80px;" viewBox="0 0 140 80" fill="none">
            <polyline points="0,60 20,45 40,50 60,25 80,30 100,10 120,20 140,5"
              stroke="white" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="60" cy="25" r="5" fill="white"/>
            <circle cx="100" cy="10" r="5" fill="white"/>
            <circle cx="140" cy="5" r="5" fill="white"/>
          </svg>
          <span class="hero-tile-title">Historique</span>
          <span style="display:flex; align-items:baseline; gap:10px;">
            <span class="hero-tile-value">${panel.getTrackedDaysCount()}</span>
            <span class="hero-tile-sub">jour(s) suivis</span>
          </span>
        </button>

        <button class="hero-tile" data-view="foods" style="position:relative; overflow:hidden;">
          <!-- Illustration assiette/fourchette en filigrane -->
          <svg style="position:absolute; bottom:5px; right:-10px; opacity:0.12; width:120px; height:120px;" viewBox="0 0 120 120" fill="none">
            <!-- Assiette -->
            <circle cx="60" cy="65" r="42" stroke="white" stroke-width="4"/>
            <circle cx="60" cy="65" r="28" stroke="white" stroke-width="2.5"/>
            <!-- Fourchette -->
            <line x1="20" y1="10" x2="20" y2="40" stroke="white" stroke-width="3" stroke-linecap="round"/>
            <line x1="15" y1="10" x2="15" y2="25" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
            <line x1="25" y1="10" x2="25" y2="25" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
            <line x1="20" y1="40" x2="20" y2="55" stroke="white" stroke-width="3" stroke-linecap="round"/>
            <!-- Couteau -->
            <line x1="35" y1="10" x2="35" y2="55" stroke="white" stroke-width="3" stroke-linecap="round"/>
            <path d="M35 10 Q42 20 35 35" stroke="white" stroke-width="2.5" fill="none"/>
          </svg>
          <span class="hero-tile-title">Aliments et plats</span>
          <span style="display:flex; align-items:baseline; gap:10px;">
            <span class="hero-tile-value">${panel.getTotalFoodsCount()}</span>
            <span class="hero-tile-sub">Aliments et plats enregistrés</span>
          </span>
        </button>
      </div>
    </div>
  `;
}

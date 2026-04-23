function buildLineChart(panel) {
  const days = panel.getChartDays(14);

  if (days.length === 0) {
    return '<p class="muted">Pas encore assez de données pour le graphique.</p>';
  }

  const width = 760;
  const height = 300;
  const leftPad = 54;
  const rightPad = 16;
  const topPad = 24;
  const bottomPad = 42;
  const plotWidth = width - leftPad - rightPad;
  const plotHeight = height - topPad - bottomPad;
  const rawMax = Math.max(...days.map((d) => d.total), 1);
  const yMax = Math.max(100, Math.ceil(rawMax / 100) * 100);
  const tickCount = 5;
  const xStep = days.length > 1 ? plotWidth / (days.length - 1) : 0;

  const points = days.map((day, index) => {
    const x = leftPad + index * xStep;
    const yCalories = topPad + plotHeight - (day.total / yMax) * plotHeight;
    const yProteins = topPad + plotHeight - (Math.min(day.proteins, yMax) / yMax) * plotHeight;
    return { ...day, x, yCalories, yProteins };
  });

  const pathCalories = points
    .map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(1)} ${point.yCalories.toFixed(1)}`)
    .join(" ");

  const pathProteins = points
    .map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(1)} ${point.yProteins.toFixed(1)}`)
    .join(" ");

  const areaPathCalories = points.length > 1
    ? `${pathCalories} L ${points[points.length - 1].x.toFixed(1)} ${(topPad + plotHeight).toFixed(1)} L ${points[0].x.toFixed(1)} ${(topPad + plotHeight).toFixed(1)} Z`
    : "";

  const yTicks = Array.from({ length: tickCount + 1 }, (_, i) => {
    const value = Math.round((yMax / tickCount) * i);
    const y = topPad + plotHeight - (value / yMax) * plotHeight;
    return { value, y };
  });

  const yGrid = yTicks
    .map((tick) => `
      <g>
        <line x1="${leftPad}" y1="${tick.y}" x2="${width - rightPad}" y2="${tick.y}" class="chart-grid"></line>
        <text x="${leftPad - 8}" y="${tick.y + 4}" text-anchor="end" class="axis-label">${tick.value}</text>
      </g>
    `)
    .join("");

  const xLabels = points
    .map((point) => `
      <text x="${point.x}" y="${height - 12}" text-anchor="middle" class="axis-label">${panel.formatDateShort(point.date)}</text>
    `)
    .join("");

  const circlesCalories = points
    .map((point) => `
      <g>
        <circle cx="${point.x}" cy="${point.yCalories}" r="5.5" class="chart-point" data-history-date="${panel.escapeAttr(point.date)}" tabindex="0"></circle>
        <text x="${point.x}" y="${point.yCalories - 12}" text-anchor="middle" class="value-label">${panel.formatNumber(point.total)}</text>
      </g>
    `)
    .join("");

  const circlesProteins = points
    .map((point) => `
      <circle cx="${point.x}" cy="${point.yProteins}" r="4" fill="white" stroke="rgba(255,152,0,0.95)" stroke-width="2.5"></circle>
    `)
    .join("");

  return `
    <div class="chart-wrap">
      <div style="display:flex; gap:16px; margin-bottom:8px; font-size:13px;">
        <span style="display:flex; align-items:center; gap:6px;">
          <span style="display:inline-block; width:20px; height:3px; background:rgba(33,150,243,0.95); border-radius:2px;"></span>
          Calories (kcal)
        </span>
        <span style="display:flex; align-items:center; gap:6px;">
          <span style="display:inline-block; width:20px; height:3px; background:rgba(255,152,0,0.95); border-radius:2px;"></span>
          Protéines (g)
        </span>
      </div>
      <svg viewBox="0 0 ${width} ${height}" class="chart-svg" role="img" aria-label="Historique calories et protéines">
        <text x="18" y="20" class="axis-label">kcal/g</text>
        ${yGrid}
        <line x1="${leftPad}" y1="${topPad}" x2="${leftPad}" y2="${topPad + plotHeight}" class="axis-line"></line>
        <line x1="${leftPad}" y1="${topPad + plotHeight}" x2="${width - rightPad}" y2="${topPad + plotHeight}" class="axis-line"></line>
        ${areaPathCalories ? `<path d="${areaPathCalories}" class="chart-area"></path>` : ""}
        ${points.length > 1 ? `<path d="${pathCalories}" class="chart-line"></path>` : ""}
        ${points.length > 1 ? `<path d="${pathProteins}" fill="none" stroke="rgba(255,152,0,0.95)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="5 3"></path>` : ""}
        ${circlesCalories}
        ${circlesProteins}
        ${xLabels}
      </svg>
      <div class="muted" style="margin-top:8px;">Clique sur un point ou sur une journée pour voir le détail.</div>
    </div>
  `;
}

export function renderHistoryView(panel) {
  const history = panel.getRecentDays(30);

  return `
    <div class="section-card">
      <h2>Historique</h2>
      ${buildLineChart(panel)}
    </div>

    <div class="section-card">
      <h2>Historique journalier</h2>
      ${
        history.length === 0
          ? '<p class="muted">Aucun historique.</p>'
          : '<div class="history-list">' + history
              .map((day) => `
                <button class="history-row" type="button" data-history-date="${panel.escapeAttr(day.date)}">
                  <span>
                    <strong>${panel.escapeHtml(panel.formatDateLong(day.date))}</strong>
                    <span class="muted history-subline">${day.count} saisie(s)</span>
                  </span>
                  <span style="text-align:right;">
                    <div><strong>${panel.formatNumber(day.total)} kcal</strong></div>
                    <div class="muted">${panel.formatNumber(day.proteins)} g prot.</div>
                  </span>
                </button>
              `)
              .join("") + '</div>'
      }
    </div>
  `;
}

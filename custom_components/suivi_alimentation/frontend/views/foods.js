export function renderFoodsView(panel) {
  return `
    <div class="section-card">
      <h2>Ajouter un nouvel aliment</h2>

      <!-- Zone photo IA -->
      <div class="ai-photo-zone" style="margin-bottom:16px;">
        <div class="ai-photo-title">📷 Analyser une photo pour pré-remplir</div>
        <div class="ai-photo-row">
          ${panel.aiLoading
            ? `<div class="ai-photo-btn" style="opacity:0.7; cursor:not-allowed;">⏳ Analyse en cours...</div>`
            : `
              <label class="ai-photo-btn" for="foodsPhotoInput" style="text-align:center;">
                📷 Prendre ou choisir une photo
              </label>
              <input type="file" id="foodsPhotoInput" accept="image/*, android/hack" style="display:none;">
            `
          }
        </div>
      </div>

      <div class="row">
        <div class="full">
          <label>Nom</label>
          <input id="newFoodName" value="${panel.escapeAttr(panel.newFood.name)}">
        </div>
        <div>
          <label>Mode</label>
          <select id="newFoodMode">
            <option value="unit" ${panel.newFood.mode === "unit" ? "selected" : ""}>À l'unité</option>
            <option value="grams" ${panel.newFood.mode === "grams" ? "selected" : ""}>Aux grammes</option>
          </select>
        </div>
        <div>
          <label>${panel.newFood.mode === "grams" ? "Unité" : "Nom de l'unité"}</label>
          <input
            id="newFoodUnit"
            value="${panel.escapeAttr(panel.newFood.mode === "grams" ? "g" : panel.newFood.unitLabel)}"
            ${panel.newFood.mode === "grams" ? "readonly" : ""}
          >
        </div>
        <div>
          <label>${panel.newFood.mode === "grams" ? "Calories pour 100 g (kcal)" : "Calories par unité (kcal)"}</label>
          <input type="number" id="newFoodCalories" value="${panel.escapeAttr(panel.newFood.caloriesValue)}">
        </div>
        <div>
          <label>${panel.newFood.mode === "grams" ? "Protéines pour 100 g (g)" : "Protéines par unité (g)"}</label>
          <input type="number" id="newFoodProteins" value="${panel.escapeAttr(panel.newFood.proteinsValue)}">
        </div>
        <div class="full actions">
          <button id="addFoodBtn">Enregistrer dans la base</button>
        </div>
      </div>
    </div>

    <div class="section-card">
      <h2>Base d'aliments</h2>
      ${
        panel.data.foods.length === 0
          ? '<p class="muted">Aucun aliment enregistré.</p>'
          : [...panel.data.foods]
              .sort((a, b) => a.name.localeCompare(b.name, "fr"))
              .map((foodItem) => {
                const isEditing = panel.editingFoodId === foodItem.id;

                if (isEditing && panel.editingFood) {
                  const proteinsLabel = panel.editingFood.mode === "grams"
                    ? "Protéines pour 100 g (g)"
                    : "Protéines par unité (g)";
                  const caloriesLabel = panel.editingFood.mode === "grams"
                    ? "Calories pour 100 g (kcal)"
                    : "Calories par unité (kcal)";

                  return '<div class="item"><div style="width:100%"><div class="row">' +
                    '<div class="full"><label>Nom</label>' +
                    '<input data-edit-name value="' + panel.escapeAttr(panel.editingFood.name) + '"></div>' +
                    '<div><label>Mode</label><select data-edit-mode>' +
                    '<option value="unit" ' + (panel.editingFood.mode === "unit" ? "selected" : "") + '>À l\'unité</option>' +
                    '<option value="grams" ' + (panel.editingFood.mode === "grams" ? "selected" : "") + '>Aux grammes</option>' +
                    '</select></div>' +
                    '<div><label>Unité</label><input data-edit-unit value="' +
                    panel.escapeAttr(panel.editingFood.mode === "grams" ? "g" : panel.editingFood.unitLabel) + '" ' +
                    (panel.editingFood.mode === "grams" ? "readonly" : "") + '></div>' +
                    '<div><label>' + caloriesLabel + '</label>' +
                    '<input type="number" data-edit-calories value="' + panel.escapeAttr(panel.editingFood.caloriesValue) + '"></div>' +
                    '<div><label>' + proteinsLabel + '</label>' +
                    '<input type="number" data-edit-proteins value="' + panel.escapeAttr(panel.editingFood.proteinsValue || "") + '"></div>' +
                    '<div class="full actions"><button data-save-food>Enregistrer</button>' +
                    '<button data-cancel-food>Annuler</button></div>' +
                    '</div></div></div>';
                }

                const caloriesInfo = foodItem.mode === "grams"
                  ? panel.toNumber(foodItem.caloriesPer100g, 0) + " kcal / " + panel.toNumber(foodItem.proteinsPer100g, 0) + " g prot. pour 100 g"
                  : panel.toNumber(foodItem.caloriesPerUnit, 0) + " kcal / " + panel.toNumber(foodItem.proteinsPerUnit, 0) + " g prot. par " + panel.escapeHtml(foodItem.unitLabel || "portion");

                return '<div class="item"><div>' +
                  '<div><strong>' + panel.escapeHtml(foodItem.name) + '</strong></div>' +
                  '<div class="muted">' + caloriesInfo + '</div>' +
                  '</div>' +
                  '<div class="actions">' +
                  '<button data-edit-food="' + panel.escapeAttr(foodItem.id) + '">Modifier</button>' +
                  '<button data-delete-food="' + panel.escapeAttr(foodItem.id) + '">Supprimer</button>' +
                  '</div></div>';
              })
              .join("")
      }
    </div>
  `;
}

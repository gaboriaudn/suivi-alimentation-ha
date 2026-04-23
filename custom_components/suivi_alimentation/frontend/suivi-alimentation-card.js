// suivi-alimentation-card.js — v0.20
// Carte tableau de bord Home Assistant — Suivi Alimentation
// Multi-profils, jauges configurables, éditeur visuel complet

// ============================================================
//  ÉDITEUR VISUEL
// ============================================================
class SuiviAlimentationCardEditor extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._config  = {};
    this._hass    = null;
    this._profiles = [];
    this._dragSrc  = null;
  }

  set hass(hass) {
    this._hass = hass;
    this._loadProfiles();
  }

  async _loadProfiles() {
    if (!this._hass) return;
    try {
      const res = await this._hass.callWS({ type: "suivi_alimentation/get_profiles" });
      this._profiles = res.profiles || [];
      this._render();
    } catch(e) {
      this._profiles = [];
      this._render();
    }
  }

  setConfig(config) {
    this._config = { ...config };
    this._render();
  }

  _fire(config) {
    this.dispatchEvent(new CustomEvent("config-changed", { detail: { config }, bubbles: true, composed: true }));
  }
  _set(key, value) { this._fire({ ...this._config, [key]: value }); }
  _toggle(key, def = true) {
    const cur = this._config[key] !== undefined ? this._config[key] : def;
    this._fire({ ...this._config, [key]: !cur });
  }
  _isOn(key, def = true) { return this._config[key] !== undefined ? this._config[key] : def; }
  _esc(v) { return String(v ?? "").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;"); }
  _defaultBlocks() { return ["calories","proteins","recap","chart","meals"]; }

  _render() {
    const cfg        = this._config;
    const allCats    = ["Petit-déjeuner","Déjeuner","Collation","Dîner"];
    const activeCats = cfg.categories || allCats;
    const blocks     = cfg.blocks || this._defaultBlocks();
    const blockLabels = {
      calories: "🔥 Jauge Calories",
      proteins: "💪 Jauge Protéines",
      recap:    "📋 Bouton Récap",
      chart:    "📈 Mini graphique",
      meals:    "🍽️ Boutons repas",
    };

    const profileOptions = this._profiles.map(p =>
      `<option value="${this._esc(p.id)}" ${(cfg.profile_id||"default")===p.id?"selected":""}>${this._esc(p.name)}</option>`
    ).join("") || `<option value="default" selected>Par défaut</option>`;

    this.shadowRoot.innerHTML = `
      <style>
        :host{display:block;font-family:Arial,sans-serif;}
        .section{margin-bottom:20px;}
        .section-title{font-size:12px;font-weight:700;color:var(--secondary-text-color);text-transform:uppercase;margin-bottom:10px;letter-spacing:0.5px;border-bottom:1px solid rgba(120,120,120,0.15);padding-bottom:6px;}
        .row{display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-bottom:1px solid rgba(120,120,120,0.08);gap:12px;}
        .row:last-child{border-bottom:none;}
        .row-label{font-size:14px;font-weight:600;}
        .row-sub{font-size:12px;color:var(--secondary-text-color);margin-top:2px;}
        input[type=text],select{border-radius:8px;border:1px solid rgba(120,120,120,0.3);padding:8px 10px;font-size:14px;background:var(--card-background-color,#fff);color:var(--primary-text-color);}
        input[type=color]{width:48px;height:36px;padding:2px 4px;cursor:pointer;border-radius:8px;border:1px solid rgba(120,120,120,0.3);}
        .toggle{position:relative;width:44px;height:24px;flex-shrink:0;}
        .toggle input{opacity:0;width:0;height:0;}
        .slider{position:absolute;inset:0;border-radius:24px;background:rgba(120,120,120,0.3);cursor:pointer;transition:background 0.2s;}
        .slider::before{content:"";position:absolute;width:18px;height:18px;left:3px;top:3px;border-radius:50%;background:white;transition:transform 0.2s;}
        input:checked+.slider{background:var(--primary-color,#2196f3);}
        input:checked+.slider::before{transform:translateX(20px);}
        .cats-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px;}
        .cat-chip{display:flex;align-items:center;gap:8px;padding:8px 12px;border-radius:10px;cursor:pointer;border:2px solid rgba(120,120,120,0.2);background:rgba(120,120,120,0.05);font-size:13px;font-weight:700;transition:all 0.15s;user-select:none;}
        .cat-chip.active{border-color:var(--primary-color,#2196f3);background:rgba(33,150,243,0.1);}
        .blocks-list{display:flex;flex-direction:column;gap:6px;margin-top:8px;}
        .block-item{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px;border:1px solid rgba(120,120,120,0.2);background:var(--card-background-color,#fff);cursor:grab;user-select:none;transition:box-shadow 0.15s;}
        .block-item.drag-over{box-shadow:0 0 0 2px var(--primary-color,#2196f3);background:rgba(33,150,243,0.07);}
        .block-label{flex:1;font-size:14px;font-weight:600;}
        .color-row{display:flex;align-items:center;gap:10px;}
        .color-preview{width:32px;height:32px;border-radius:8px;border:1px solid rgba(120,120,120,0.2);}
        .reset-btn{width:auto;padding:6px 10px;font-size:12px;border-radius:8px;border:1px solid rgba(120,120,120,0.3);background:rgba(120,120,120,0.1);color:var(--primary-text-color);cursor:pointer;}
      </style>

      <div class="section">
        <div class="section-title">🏷️ Titre</div>
        <div class="row">
          <div><div class="row-label">Titre de la carte</div><div class="row-sub">Affiché en haut de la carte</div></div>
          <input type="text" id="cardTitle" placeholder="🥗 Suivi du jour" value="${this._esc(cfg.title||"")}">
        </div>
      </div>

      <div class="section">
        <div class="section-title">👤 Profil utilisateur</div>
        <div class="row">
          <div><div class="row-label">Profil affiché</div><div class="row-sub">Chaque carte peut afficher un profil différent</div></div>
          <select id="profileSelect">${profileOptions}</select>
        </div>
      </div>

      <div class="section">
        <div class="section-title">🎨 Apparence</div>
        <div class="row">
          <div><div class="row-label">Couleur de fond</div><div class="row-sub">Arrière-plan de la carte (optionnel)</div></div>
          <div class="color-row">
            <div class="color-preview" id="colorPreview" style="background:${cfg.bg_color||"transparent"};${!cfg.bg_color?"border-style:dashed;":""}"></div>
            <input type="color" id="colorPicker" value="${cfg.bg_color||"#ffffff"}">
            <button class="reset-btn" id="resetColor">↺</button>
          </div>
        </div>
        <div class="row">
          <div><div class="row-label">Style des jauges</div><div class="row-sub">Forme d'affichage</div></div>
          <select id="gaugeStyle">
            <option value="arc" ${(cfg.gauge_style||"arc")==="arc"?"selected":""}>Demi-cercle</option>
            <option value="bar" ${cfg.gauge_style==="bar"?"selected":""}>Barre de progression</option>
          </select>
        </div>
      </div>

      <div class="section">
        <div class="section-title">🔀 Ordre et visibilité des blocs</div>
        <div style="font-size:12px;color:var(--secondary-text-color);margin-bottom:8px;">Glisse pour réordonner. Le bouton masque/affiche chaque bloc.</div>
        <div class="blocks-list" id="blocksList">
          ${blocks.map(b => `
            <div class="block-item" draggable="true" data-block="${b}">
              <span style="color:var(--secondary-text-color);font-size:16px;">⠿</span>
              <span class="block-label">${blockLabels[b]||b}</span>
              <label class="toggle">
                <input type="checkbox" class="block-toggle" data-block="${b}" ${this._isOn("show_"+b)?"checked":""}>
                <span class="slider"></span>
              </label>
            </div>
          `).join("")}
        </div>
      </div>

      <div class="section">
        <div class="section-title">🍽️ Repas affichés</div>
        <div class="cats-grid">
          ${allCats.map(cat=>`
            <div class="cat-chip ${activeCats.includes(cat)?"active":""}" data-cat="${cat}">
              <span>${{"Petit-déjeuner":"☀️","Déjeuner":"🌿","Collation":"🍎","Dîner":"🌙"}[cat]}</span>
              <span>${cat}</span>
            </div>
          `).join("")}
        </div>
      </div>

      <div class="section">
        <div class="section-title">📊 Options d'affichage</div>
        <div class="row">
          <div><div class="row-label">Compteur d'aliments</div><div class="row-sub">Nombre d'aliments sur les boutons repas</div></div>
          <label class="toggle"><input type="checkbox" id="toggleCount" ${this._isOn("show_count")?"checked":""}><span class="slider"></span></label>
        </div>
      </div>
    `;

    // ── Événements ──
    this.shadowRoot.getElementById("cardTitle")?.addEventListener("input", e => this._set("title", e.target.value));
    this.shadowRoot.getElementById("profileSelect")?.addEventListener("change", e => this._set("profile_id", e.target.value));
    this.shadowRoot.getElementById("gaugeStyle")?.addEventListener("change", e => this._set("gauge_style", e.target.value));
    this.shadowRoot.getElementById("toggleCount")?.addEventListener("change", () => this._toggle("show_count"));

    this.shadowRoot.getElementById("colorPicker")?.addEventListener("input", e => {
      this.shadowRoot.getElementById("colorPreview").style.cssText = `background:${e.target.value};border-style:solid;`;
      this._set("bg_color", e.target.value);
    });
    this.shadowRoot.getElementById("resetColor")?.addEventListener("click", () => {
      const prev = this.shadowRoot.getElementById("colorPreview");
      prev.style.cssText = "background:transparent;border-style:dashed;";
      this._fire({ ...this._config, bg_color: null });
    });

    // Toggles blocs
    this.shadowRoot.querySelectorAll(".block-toggle").forEach(cb =>
      cb.addEventListener("change", () => this._toggle("show_"+cb.getAttribute("data-block")))
    );

    // Drag & drop blocs
    const list = this.shadowRoot.getElementById("blocksList");
    list?.querySelectorAll(".block-item").forEach(item => {
      item.addEventListener("dragstart", e => { this._dragSrc = item; e.dataTransfer.effectAllowed = "move"; });
      item.addEventListener("dragover",  e => { e.preventDefault(); item.classList.add("drag-over"); });
      item.addEventListener("dragleave", () => item.classList.remove("drag-over"));
      item.addEventListener("drop", e => {
        e.preventDefault(); item.classList.remove("drag-over");
        if (!this._dragSrc || this._dragSrc === item) return;
        const items   = [...list.querySelectorAll(".block-item")];
        const newOrder = [...(this._config.blocks || this._defaultBlocks())];
        const [moved] = newOrder.splice(items.indexOf(this._dragSrc), 1);
        newOrder.splice(items.indexOf(item), 0, moved);
        this._set("blocks", newOrder);
      });
      item.addEventListener("dragend", () => list.querySelectorAll(".block-item").forEach(i => i.classList.remove("drag-over")));
    });

    // Repas
    this.shadowRoot.querySelectorAll(".cat-chip").forEach(chip =>
      chip.addEventListener("click", () => {
        const cat = chip.getAttribute("data-cat");
        const cur = [...(this._config.categories || allCats)];
        const idx = cur.indexOf(cat);
        if (idx >= 0 && cur.length > 1) cur.splice(idx, 1);
        else if (idx < 0) cur.push(cat);
        this._set("categories", allCats.filter(c => cur.includes(c)));
      })
    );
  }
}

customElements.define("suivi-alimentation-card-editor", SuiviAlimentationCardEditor);


// ============================================================
//  CARTE PRINCIPALE
// ============================================================
class SuiviAlimentationCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._hass    = null;
    this._profile = null;
    this._today   = this._getToday();
    this._config  = {};

    this._addPopupCategory  = null;
    this._showRecapPopup    = false;
    this._showHistoryPopup  = false;
    this._saveToLibraryData = null;

    this._foodForm = { templateId:"custom", name:"", quantity:"", calories:"", proteins:"", category:"Petit-déjeuner" };

    this._aiLoading = false;
    this._aiResult  = null;
    this._aiError   = null;
    this._saving    = false;

    this._allCategories = ["Petit-déjeuner","Déjeuner","Dîner","Collation"];
    this._catIcons      = { "Petit-déjeuner":"☀️","Déjeuner":"🌿","Collation":"🍎","Dîner":"🌙" };
  }

  static getConfigElement() { return document.createElement("suivi-alimentation-card-editor"); }
  static getStubConfig() {
    return {
      title: "",
      profile_id: "default",
      bg_color: null,
      gauge_style: "arc",
      blocks: ["calories","proteins","recap","chart","meals"],
      show_calories: true, show_proteins: true, show_recap: true, show_chart: true, show_meals: true,
      show_count: true,
      categories: ["Petit-déjeuner","Déjeuner","Collation","Dîner"],
    };
  }

  setConfig(config) { this._config = config || {}; this._render(); }

  set hass(hass) {
    this._hass = hass;
    if (!this._profile) this._loadProfile();
  }

  // ── Helpers ────────────────────────────────────────────────
  _getToday() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,"0")}-${String(d.getDate()).padStart(2,"0")}`;
  }
  _esc(v)      { return String(v??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;"); }
  _num(v,fb=0) { const n=Number(v); return Number.isFinite(n)?n:fb; }
  _fmt(v)      { return Number(v??0).toLocaleString("fr-FR"); }
  _cfg(key,def=true) { return this._config[key]!==undefined ? this._config[key] : def; }
  _categories()  { return this._config.categories || this._allCategories; }
  _blocks()      { return this._config.blocks || ["calories","proteins","recap","chart","meals"]; }
  _profileId()   { return this._config.profile_id || "default"; }

  // ── Chargement du profil ───────────────────────────────────
  async _loadProfile() {
    if (!this._hass) return;
    try {
      this._profile = await this._hass.callWS({
        type: "suivi_alimentation/get_profile_data",
        profile_id: this._profileId(),
      });
      this._render();
    } catch(e) {
      // Fallback : tenter avec get_data (compatibilité v0.10)
      try {
        const data = await this._hass.callWS({ type: "suivi_alimentation/get_data" });
        const profiles = data?.profiles || {};
        this._profile = profiles[this._profileId()] || Object.values(profiles)[0] || null;
        this._render();
      } catch(e2) { console.error("SuiviCard: erreur chargement profil", e2); }
    }
  }

  async _saveProfile() {
    if (!this._hass || !this._profile) return;
    try {
      await this._hass.callWS({
        type: "suivi_alimentation/save_profile_data",
        profile_id: this._profileId(),
        data: this._profile,
      });
    } catch(e) {
      // Fallback save_data
      try {
        const fullData = await this._hass.callWS({ type: "suivi_alimentation/get_data" });
        if (fullData?.profiles) {
          fullData.profiles[this._profileId()] = this._profile;
          await this._hass.callWS({ type: "suivi_alimentation/save_data", data: fullData });
        }
      } catch(e2) { console.error("SuiviCard: erreur sauvegarde", e2); }
    }
  }

  // ── Données du jour ────────────────────────────────────────
  _getTodayEntries() {
    if (!this._profile) return [];
    return Array.isArray(this._profile.entriesByDate?.[this._today])
      ? this._profile.entriesByDate[this._today] : [];
  }
  _getTotalCalories() { return this._getTodayEntries().reduce((s,e)=>s+this._num(e.calories),0); }
  _getTotalProteins()  { return this._getTodayEntries().reduce((s,e)=>s+this._num(e.proteins),0); }

  _getRecentDays(limit=7) {
    if (!this._profile) return [];
    return Object.keys(this._profile.entriesByDate||{})
      .sort((a,b)=>b.localeCompare(a)).slice(0,limit)
      .map(date => {
        const items = Array.isArray(this._profile.entriesByDate[date]) ? this._profile.entriesByDate[date] : [];
        return { date, total:items.reduce((s,e)=>s+this._num(e.calories),0), proteins:items.reduce((s,e)=>s+this._num(e.proteins),0) };
      }).reverse();
  }

  _getSelectedFood() {
    if (!this._profile || this._foodForm.templateId==="custom") return null;
    return (this._profile.foods||[]).find(f=>f.id===this._foodForm.templateId)||null;
  }
  _getComputedCalories() {
    const food=this._getSelectedFood(), qty=this._num(this._foodForm.quantity,0);
    if (!food||qty<=0) return "";
    return food.mode==="grams"?Math.round(this._num(food.caloriesPer100g,0)*qty/100):Math.round(this._num(food.caloriesPerUnit,0)*qty);
  }
  _getComputedProteins() {
    const food=this._getSelectedFood(), qty=this._num(this._foodForm.quantity,0);
    if (!food||qty<=0) return "";
    return food.mode==="grams"?Math.round(this._num(food.proteinsPer100g,0)*qty/100):Math.round(this._num(food.proteinsPerUnit,0)*qty);
  }

  // ── Actions ────────────────────────────────────────────────
  async _addEntry() {
    const food=this._getSelectedFood();
    const finalName=(this._foodForm.name||food?.name||"").trim();
    const finalCal=this._foodForm.templateId!=="custom"?this._num(this._getComputedCalories(),0):this._num(this._foodForm.calories,0);
    const finalProt=this._foodForm.templateId!=="custom"?this._num(this._getComputedProteins(),0):this._num(this._foodForm.proteins,0);
    if (!finalName||finalCal<=0) return;

    const entry={
      id:`entry_${Date.now()}_${Math.random().toString(16).slice(2)}`,
      name:finalName,calories:finalCal,proteins:finalProt,
      category:this._foodForm.category,
      quantity:this._foodForm.quantity===""?null:this._num(this._foodForm.quantity,0),
      quantityUnit:food?.unitLabel||null,
      createdAt:new Date().toISOString(),
    };
    if (!this._profile.entriesByDate) this._profile.entriesByDate={};
    if (!Array.isArray(this._profile.entriesByDate[this._today])) this._profile.entriesByDate[this._today]=[];
    this._profile.entriesByDate[this._today].push(entry);

    this._saving=true; this._render();
    await this._saveProfile();
    this._saving=false;
    this._foodForm={templateId:"custom",name:"",quantity:"",calories:"",proteins:"",category:this._addPopupCategory};
    this._render();
  }

  async _deleteEntry(id) {
    if (!this._profile) return;
    this._profile.entriesByDate[this._today]=this._getTodayEntries().filter(e=>e.id!==id);
    if (this._profile.entriesByDate[this._today].length===0) delete this._profile.entriesByDate[this._today];
    await this._saveProfile();
    this._render();
  }

  async _confirmAiResult() {
    if (!this._aiResult) return;
    const entry={
      id:`entry_${Date.now()}_${Math.random().toString(16).slice(2)}`,
      name:this._aiResult.name,calories:this._aiResult.calories,proteins:this._aiResult.proteins,
      category:this._addPopupCategory||"Déjeuner",quantity:null,quantityUnit:null,createdAt:new Date().toISOString(),
    };
    if (!this._profile.entriesByDate) this._profile.entriesByDate={};
    if (!Array.isArray(this._profile.entriesByDate[this._today])) this._profile.entriesByDate[this._today]=[];
    this._profile.entriesByDate[this._today].push(entry);
    this._saveToLibraryData={name:this._aiResult.name,calories:this._aiResult.calories,mode:"unit",unitLabel:"portion"};
    this._aiResult=null; this._aiError=null;
    await this._saveProfile();
    this._render();
  }

  async _confirmSaveToLibrary() {
    if (!this._saveToLibraryData||!this._profile) return;
    const d=this._saveToLibraryData;
    const name=(this.shadowRoot.getElementById("libFoodName")?.value||d.name).trim();
    const calories=this._num(this.shadowRoot.getElementById("libFoodCalories")?.value||d.calories,0);
    const unitLabel=this.shadowRoot.getElementById("libFoodUnit")?.value?.trim()||"portion";
    if (!name||calories<=0) return;
    const food={id:`food_${Date.now()}`,name,mode:d.mode||"unit",unitLabel:d.mode==="grams"?"g":unitLabel,defaultCategory:"Déjeuner"};
    if (d.mode==="grams") food.caloriesPer100g=calories; else food.caloriesPerUnit=calories;
    if (!this._profile.foods) this._profile.foods=[];
    this._profile.foods.push(food);
    this._saveToLibraryData=null; this._addPopupCategory=null;
    await this._saveProfile();
    this._render();
  }

  async _analyzePhoto(file) {
    if (!file) return;
    this._aiLoading=true; this._aiResult=null; this._aiError=null; this._render();
    try {
      const fd=new FormData();
      fd.append("media_content_id","media-source://media_source/local/.");
      fd.append("file",file,file.name||"photo.jpg");
      const upResp=await fetch("/api/media_source/local_source/upload",{method:"POST",headers:{"Authorization":`Bearer ${this._hass.auth.data.access_token}`},body:fd});
      if (!upResp.ok) throw new Error(`Upload HTTP ${upResp.status}`);
      const mediaContentId=(await upResp.json())?.media_content_id;
      if (!mediaContentId) throw new Error("ID média introuvable");
      const resp=await fetch("/api/services/ai_task/generate_data?return_response",{
        method:"POST",
        headers:{"Content-Type":"application/json","Authorization":`Bearer ${this._hass.auth.data.access_token}`},
        body:JSON.stringify({
          task_name:"Analyse nutritionnelle",entity_id:"ai_task.openai_ai_task",
          instructions:"Tu es un expert en nutrition. Analyse cette photo de repas et estime le nom du plat en français, les calories et les protéines.",
          structure:{
            name:{selector:{text:{}},description:"Nom court du repas en français"},
            calories:{selector:{number:{}},description:"Calories estimées (kcal)"},
            proteins:{selector:{number:{}},description:"Protéines estimées (g)"},
            description:{selector:{text:{}},description:"Description courte (1 phrase en français)"},
          },
          attachments:[{media_content_id:mediaContentId,media_content_type:file.type||"image/jpeg"}],
        }),
      });
      if (!resp.ok) throw new Error(`AI HTTP ${resp.status}`);
      const result=await resp.json();
      await fetch("/api/media_source/local_source/remove",{method:"POST",headers:{"Content-Type":"application/json","Authorization":`Bearer ${this._hass.auth.data.access_token}`},body:JSON.stringify({media_content_id:mediaContentId})}).catch(()=>{});
      const rd=result?.service_response?.data||result?.response?.data||result?.data||{};
      this._aiResult={name:rd.name||"",calories:Math.round(rd.calories||0),proteins:Math.round(rd.proteins||0),description:rd.description||""};
    } catch(e) {
      this._aiError="L'analyse a échoué : "+(e.message||"erreur inconnue");
    } finally {
      this._aiLoading=false; this._render();
    }
  }

  // ── Jauges ─────────────────────────────────────────────────
  _renderGaugeArc(total, goal, unit) {
    const pct=goal>0?Math.min(Math.max(total/goal,0),1):0;
    const cx=80,cy=75,r=60,arc=Math.PI*r,filled=arc*pct,gap=arc-filled;
    let gc="#4caf50"; if(pct>=1)gc="#e53935"; else if(pct>=0.8)gc="#ff9800";
    const rem=goal-total;
    const txt=rem>=0?`${rem.toLocaleString("fr-FR")} ${unit}`:`+${Math.abs(rem).toLocaleString("fr-FR")} ${unit}`;
    return `
      <div style="display:flex;flex-direction:column;align-items:center;">
        <div style="position:relative;width:160px;height:88px;">
          <svg viewBox="0 0 160 88" style="width:160px;height:88px;">
            <path d="M ${cx-r} ${cy} A ${r} ${r} 0 0 1 ${cx+r} ${cy}" fill="none" stroke="rgba(120,120,120,0.2)" stroke-width="12" stroke-linecap="round"/>
            ${pct>0.01?`<path d="M ${cx-r} ${cy} A ${r} ${r} 0 0 1 ${cx+r} ${cy}" fill="none" stroke="${gc}" stroke-width="12" stroke-linecap="round" stroke-dasharray="${filled.toFixed(2)} ${(gap+14).toFixed(2)}"/>`:""}
          </svg>
          <div style="position:absolute;bottom:0;left:0;right:0;text-align:center;">
            <div style="font-size:13px;font-weight:800;color:${gc};">${txt}</div>
            <div style="font-size:10px;color:var(--secondary-text-color);">${rem>=0?"restants":"dépassés"}</div>
          </div>
        </div>
        <div style="font-size:13px;font-weight:700;margin-top:4px;">${total.toLocaleString("fr-FR")} ${unit}</div>
        <div style="font-size:11px;color:var(--secondary-text-color);">sur ${goal.toLocaleString("fr-FR")} ${unit}</div>
      </div>`;
  }

  _renderGaugeBar(total, goal, unit) {
    const pct=goal>0?Math.min(Math.max(total/goal,0),1):0;
    let gc="#4caf50"; if(pct>=1)gc="#e53935"; else if(pct>=0.8)gc="#ff9800";
    const rem=goal-total;
    const txt=rem>=0?`${rem.toLocaleString("fr-FR")} ${unit} restants`:`+${Math.abs(rem).toLocaleString("fr-FR")} ${unit} dépassés`;
    return `
      <div style="flex:1;padding:0 4px;">
        <div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px;">
          <span style="font-size:13px;font-weight:700;">${total.toLocaleString("fr-FR")} ${unit}</span>
          <span style="font-size:11px;color:var(--secondary-text-color);">/ ${goal.toLocaleString("fr-FR")} ${unit}</span>
        </div>
        <div style="width:100%;height:10px;border-radius:5px;background:rgba(120,120,120,0.15);overflow:hidden;">
          <div style="width:${(pct*100).toFixed(1)}%;height:100%;border-radius:5px;background:${gc};transition:width 0.4s;"></div>
        </div>
        <div style="font-size:11px;color:${gc};font-weight:700;margin-top:4px;">${txt}</div>
      </div>`;
  }

  // ── Mini graphique ─────────────────────────────────────────
  _renderMiniChart() {
    const days=this._getRecentDays(7);
    if (days.length<2) return `<div style="font-size:12px;color:var(--secondary-text-color);text-align:center;padding:8px 0;">Pas encore assez de données</div>`;
    const W=320,H=80,lp=32,rp=8,tp=8,bp=20,pw=W-lp-rp,ph=H-tp-bp;
    const yMax=Math.max(100,Math.ceil(Math.max(...days.map(d=>d.total),1)/100)*100);
    const xStep=pw/(days.length-1);
    const pts=days.map((d,i)=>({...d,x:lp+i*xStep,yc:tp+ph-(d.total/yMax)*ph,yp:tp+ph-(Math.min(d.proteins,yMax)/yMax)*ph}));
    const pathC=pts.map((p,i)=>`${i===0?"M":"L"} ${p.x.toFixed(1)} ${p.yc.toFixed(1)}`).join(" ");
    const pathP=pts.map((p,i)=>`${i===0?"M":"L"} ${p.x.toFixed(1)} ${p.yp.toFixed(1)}`).join(" ");
    const area=`${pathC} L ${pts[pts.length-1].x.toFixed(1)} ${(tp+ph).toFixed(1)} L ${pts[0].x.toFixed(1)} ${(tp+ph).toFixed(1)} Z`;
    const fmtD=s=>{const[,m,d]=s.split("-");return`${d}/${m}`;};
    return `
      <div style="display:flex;gap:12px;margin-bottom:4px;font-size:11px;color:var(--secondary-text-color);">
        <span style="display:flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:2px;background:#2196f3;border-radius:2px;"></span>Cal.</span>
        <span style="display:flex;align-items:center;gap:4px;"><span style="display:inline-block;width:14px;height:2px;background:#ff9800;border-radius:2px;"></span>Prot.</span>
      </div>
      <svg viewBox="0 0 ${W} ${H}" style="width:100%;height:auto;">
        <line x1="${lp}" y1="${tp}" x2="${lp}" y2="${tp+ph}" stroke="rgba(120,120,120,0.2)" stroke-width="1"/>
        <line x1="${lp}" y1="${tp+ph}" x2="${W-rp}" y2="${tp+ph}" stroke="rgba(120,120,120,0.2)" stroke-width="1"/>
        <path d="${area}" fill="#2196f3" opacity="0.1"/>
        <path d="${pathC}" fill="none" stroke="#2196f3" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        <path d="${pathP}" fill="none" stroke="#ff9800" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="4 3"/>
        ${pts.map(p=>`
          <circle cx="${p.x.toFixed(1)}" cy="${p.yc.toFixed(1)}" r="3.5" fill="white" stroke="#2196f3" stroke-width="2"/>
          <text x="${p.x.toFixed(1)}" y="${H-4}" text-anchor="middle" font-size="9" fill="currentColor" opacity="0.6">${fmtD(p.date)}</text>
        `).join("")}
        <text x="${lp-4}" y="${tp+8}" text-anchor="end" font-size="9" fill="currentColor" opacity="0.6">${yMax}</text>
      </svg>`;
  }

  // ── Blocs dynamiques ───────────────────────────────────────
  _renderBlocks(totalCal, goalCal, totalProt, goalProt, entries, categories, gaugeStyle) {
    const blocks   = this._blocks();
    const showCount = this._cfg("show_count");
    const calIdx   = blocks.indexOf("calories");
    const protIdx  = blocks.indexOf("proteins");
    const adjacent = Math.abs(calIdx - protIdx) === 1;
    const skip     = new Set();
    let html       = "";

    blocks.forEach((block) => {
      if (skip.has(block)) return;

      // Jauges côte à côte si adjacentes
      if ((block==="calories"||block==="proteins") && adjacent) {
        if (block === blocks[Math.min(calIdx,protIdx)]) {
          const showCal  = this._cfg("show_calories");
          const showProt = this._cfg("show_proteins");
          if (showCal||showProt) {
            html += `<div style="display:flex;gap:12px;justify-content:space-around;flex-wrap:wrap;">`;
            if (showCal) html += `<div><div style="font-size:12px;font-weight:700;color:var(--secondary-text-color);margin-bottom:6px;text-align:${gaugeStyle==="arc"?"center":"left"};">🔥 Calories</div>${gaugeStyle==="bar"?this._renderGaugeBar(totalCal,goalCal,"kcal"):this._renderGaugeArc(totalCal,goalCal,"kcal")}</div>`;
            if (showProt) html += `<div><div style="font-size:12px;font-weight:700;color:var(--secondary-text-color);margin-bottom:6px;text-align:${gaugeStyle==="arc"?"center":"left"};">💪 Protéines</div>${gaugeStyle==="bar"?this._renderGaugeBar(totalProt,goalProt,"g"):this._renderGaugeArc(totalProt,goalProt,"g")}</div>`;
            html += `</div>`;
          }
          skip.add("calories"); skip.add("proteins");
        }
        return;
      }

      if (block==="calories" && this._cfg("show_calories")) {
        html+=`<div><div style="font-size:12px;font-weight:700;color:var(--secondary-text-color);margin-bottom:6px;">🔥 Calories</div>${gaugeStyle==="bar"?this._renderGaugeBar(totalCal,goalCal,"kcal"):this._renderGaugeArc(totalCal,goalCal,"kcal")}</div>`;
        return;
      }
      if (block==="proteins" && this._cfg("show_proteins")) {
        html+=`<div><div style="font-size:12px;font-weight:700;color:var(--secondary-text-color);margin-bottom:6px;">💪 Protéines</div>${gaugeStyle==="bar"?this._renderGaugeBar(totalProt,goalProt,"g"):this._renderGaugeArc(totalProt,goalProt,"g")}</div>`;
        return;
      }
      if (block==="recap" && this._cfg("show_recap")) {
        html+=`<div class="recap-zone" id="recapZone">📋 Récap du jour — ${entries.length} aliment(s) · ${this._fmt(totalCal)} kcal</div>`;
        return;
      }
      if (block==="chart" && this._cfg("show_chart")) {
        html+=`<div class="chart-zone" id="chartZone"><div class="chart-title">📈 7 derniers jours</div>${this._renderMiniChart()}</div>`;
        return;
      }
      if (block==="meals" && this._cfg("show_meals",true)) {
        html+=`
          <div>
            <hr style="border:none;border-top:1px solid rgba(120,120,120,0.15);margin:4px 0 10px;">
            <div style="font-size:13px;font-weight:700;color:var(--secondary-text-color);margin-bottom:8px;">Ajouter un aliment</div>
            <div class="cat-grid">
              ${categories.map(cat=>{
                const count=entries.filter(e=>e.category===cat).length;
                return `<button class="cat-btn" data-cat="${cat}">
                  <span>${this._catIcons[cat]||"🍽️"}</span>
                  <span>${cat}</span>
                  ${showCount&&count>0?`<span class="cat-count">${count}×</span>`:""}
                </button>`;
              }).join("")}
            </div>
          </div>`;
      }
    });
    return html;
  }

  // ── Popups ─────────────────────────────────────────────────
  _renderRecapPopup() {
    if (!this._showRecapPopup) return "";
    const entries=this._getTodayEntries();
    const today=new Date().toLocaleDateString("fr-FR",{weekday:"long",day:"numeric",month:"long"});
    const byCategory=this._allCategories.map(cat=>({cat,items:entries.filter(e=>e.category===cat)})).filter(g=>g.items.length>0);
    return `
      <div class="popup-overlay" id="recapOverlay">
        <div class="popup-card">
          <div class="popup-header">
            <div><h2>📋 Récap du jour</h2><div class="muted" style="text-transform:capitalize;">${today} · ${entries.length} aliment(s) · ${this._fmt(this._getTotalCalories())} kcal · ${this._fmt(this._getTotalProteins())} g prot.</div></div>
            <button class="popup-close" id="closeRecapBtn">Fermer</button>
          </div>
          <div class="popup-content">
            ${byCategory.length===0?`<p class="muted">Aucune saisie aujourd'hui.</p>`:byCategory.map(g=>`
              <div class="popup-group"><h3>${this._esc(g.cat)}</h3>
                <div class="popup-list">${g.items.map(item=>`
                  <div class="popup-item">
                    <div><strong>${this._esc(item.name)}</strong><div class="muted">${item.quantity!=null?`${this._esc(item.quantity)} ${this._esc(item.quantityUnit||"")}`.trim():"Saisie libre"}</div></div>
                    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
                      <span>${this._fmt(item.calories)} kcal</span>
                      <span class="muted">${this._fmt(this._num(item.proteins,0))} g prot.</span>
                      <button class="del-btn" data-delete-id="${this._esc(item.id)}">🗑</button>
                    </div>
                  </div>`).join("")}
                </div>
              </div>`).join("")}
          </div>
        </div>
      </div>`;
  }

  _renderHistoryPopup() {
    if (!this._showHistoryPopup) return "";
    const days=this._getRecentDays(14).slice().reverse();
    const goalCal=this._num(this._profile?.goal,2000);
    return `
      <div class="popup-overlay" id="historyOverlay">
        <div class="popup-card">
          <div class="popup-header">
            <div><h2>📈 Historique</h2><div class="muted">14 derniers jours</div></div>
            <button class="popup-close" id="closeHistoryBtn">Fermer</button>
          </div>
          <div class="popup-content">
            ${days.length===0?`<p class="muted">Aucun historique.</p>`:days.map(day=>{
              const pct=Math.min((this._num(day.total)/goalCal)*100,100).toFixed(0);
              let bc="#4caf50"; if(day.total/goalCal>=1)bc="#e53935"; else if(day.total/goalCal>=0.8)bc="#ff9800";
              return `<div style="display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid rgba(120,120,120,0.1);">
                <div><strong>${day.date.split("-").reverse().join("/")}</strong>
                  <div style="width:120px;height:6px;border-radius:3px;background:rgba(120,120,120,0.15);margin-top:6px;overflow:hidden;">
                    <div style="width:${pct}%;height:100%;background:${bc};border-radius:3px;"></div>
                  </div>
                </div>
                <div style="text-align:right;"><div style="font-weight:700;">${this._fmt(day.total)} kcal</div><div class="muted">${this._fmt(day.proteins)} g prot.</div></div>
              </div>`;
            }).join("")}
          </div>
        </div>
      </div>`;
  }

  _renderAddPopup() {
    if (!this._addPopupCategory) return "";
    if (this._saveToLibraryData)  return this._renderSaveToLibraryPopup();
    const food=this._getSelectedFood(), ai=this._aiResult;
    const foods=[...(this._profile?.foods||[])].sort((a,b)=>a.name.localeCompare(b.name,"fr"));
    return `
      <div class="popup-overlay" id="addOverlay">
        <div class="popup-card">
          <div class="popup-header">
            <div><h2>Ajouter — ${this._esc(this._addPopupCategory)}</h2><div class="muted">Le popup reste ouvert pour enchaîner les ajouts.</div></div>
            <button class="popup-close" id="closeAddBtn">Fermer</button>
          </div>
          <div class="popup-content">
            <div class="ai-photo-zone">
              <div class="ai-photo-title">📷 Analyser une photo</div>
              ${this._aiLoading
                ?`<div class="ai-photo-btn" style="opacity:0.7;cursor:not-allowed;">⏳ Analyse en cours...</div>`
                :`<label class="ai-photo-btn" for="aiPhotoInput" style="text-align:center;">📷 Prendre ou choisir une photo</label>
                  <input type="file" id="aiPhotoInput" accept="image/*" style="display:none;">`}
              ${ai?`
                <div class="ai-result-box">
                  <div class="ai-result-title">✨ Proposition de l'IA</div>
                  <div class="form-row">
                    <div class="full"><label>Nom du repas</label><input id="aiResultName" value="${this._esc(ai.name)}"></div>
                    <div><label>Calories (kcal)</label><input type="number" id="aiResultCalories" value="${this._esc(ai.calories)}"></div>
                    <div><label>Protéines (g)</label><input type="number" id="aiResultProteins" value="${this._esc(ai.proteins)}"></div>
                    <div class="full muted" style="font-style:italic;">💡 ${this._esc(ai.description)}</div>
                    <div class="full actions"><button id="confirmAiBtn">✅ Utiliser ces valeurs</button><button id="cancelAiBtn">Annuler</button></div>
                  </div>
                </div>`:""}
              ${this._aiError?`<div style="color:#e53935;margin-top:8px;font-size:13px;">⚠️ ${this._esc(this._aiError)}</div>`:""}
            </div>
            <div class="ai-separator">ou saisie manuelle</div>
            <div class="form-row">
              <div class="full">
                <label>Aliment enregistré</label>
                <select id="templateId">
                  <option value="custom" ${this._foodForm.templateId==="custom"?"selected":""}>Saisie libre</option>
                  ${foods.map(f=>`<option value="${this._esc(f.id)}" ${this._foodForm.templateId===f.id?"selected":""}>${this._esc(f.name)}</option>`).join("")}
                </select>
              </div>
              <div class="full"><label>Nom</label><input id="foodName" value="${this._esc(this._foodForm.name)}"></div>
              ${this._foodForm.templateId!=="custom"&&food?`
                <div><label>Quantité ${food.mode==="grams"?"(g)":"("+this._esc(food.unitLabel)+")"}</label><input type="number" id="quantity" value="${this._esc(this._foodForm.quantity)}"></div>
                <div><label>Calories calculées (kcal)</label><input type="number" id="computedCalories" value="${this._esc(this._getComputedCalories())}" readonly></div>
                <div><label>Protéines calculées (g)</label><input type="number" id="computedProteins" value="${this._esc(this._getComputedProteins())}" readonly></div>
                <div class="full muted">${food.mode==="grams"?`${this._num(food.caloriesPer100g,0)} kcal / ${this._num(food.proteinsPer100g,0)} g pour 100 g`:`${this._num(food.caloriesPerUnit,0)} kcal / ${this._num(food.proteinsPerUnit,0)} g par ${this._esc(food.unitLabel)}`}</div>`
              :`<div><label>Calories (kcal)</label><input type="number" id="manualCalories" value="${this._esc(this._foodForm.calories)}"></div>
                <div><label>Protéines (g) — optionnel</label><input type="number" id="manualProteins" value="${this._esc(this._foodForm.proteins)}"></div>`}
              <div class="full">
                <label>Catégorie</label>
                <select id="category">
                  ${this._allCategories.map(cat=>`<option value="${this._esc(cat)}" ${this._foodForm.category===cat?"selected":""}>${this._esc(cat)}</option>`).join("")}
                </select>
              </div>
              <div class="full actions"><button id="addEntryBtn" ${this._saving?"disabled":""}>${this._saving?"Enregistrement…":"Ajouter au jour"}</button></div>
            </div>
          </div>
        </div>
      </div>`;
  }

  _renderSaveToLibraryPopup() {
    const d=this._saveToLibraryData;
    return `
      <div class="popup-overlay" id="addOverlay">
        <div class="popup-card">
          <div class="popup-header"><div><h2>Enregistrer dans la bibliothèque ?</h2><div class="muted">✅ L'aliment a bien été ajouté à votre journée.</div></div></div>
          <div class="popup-content">
            <div class="form-row">
              <div class="full"><label>Nom</label><input id="libFoodName" value="${this._esc(d.name)}"></div>
              <div class="full"><label>Calories par unité (kcal)</label><input type="number" id="libFoodCalories" value="${this._esc(d.calories)}"></div>
              <div class="full"><label>Nom de l'unité</label><input id="libFoodUnit" value="${this._esc(d.unitLabel||"portion")}"></div>
              <div class="full actions"><button id="confirmLibBtn">Oui, enregistrer</button><button id="cancelLibBtn">Non merci</button></div>
            </div>
          </div>
        </div>
      </div>`;
  }

  // ── Rendu principal ────────────────────────────────────────
  _render() {
    const profile    = this._profile;
    const totalCal   = this._getTotalCalories();
    const totalProt  = this._getTotalProteins();
    const goalCal    = this._num(profile?.goal, 2000);
    const goalProt   = this._num(profile?.proteinGoal, 100);
    const entries    = this._getTodayEntries();
    const today      = new Date().toLocaleDateString("fr-FR",{weekday:"long",day:"numeric",month:"long"});
    const categories = this._categories();
    const gaugeStyle = this._config.gauge_style || "arc";
    const bgColor    = this._config.bg_color || null;
    const title      = this._config.title || (profile ? `🥗 ${profile.name}` : "🥗 Suivi du jour");

    this.shadowRoot.innerHTML = `
      <style>
        :host{display:block;} *{box-sizing:border-box;}
        .card{background:${bgColor?bgColor:"var(--card-background-color,#fff)"};border-radius:16px;padding:16px;color:var(--primary-text-color);font-family:Arial,sans-serif;display:flex;flex-direction:column;gap:12px;}
        .title{font-size:17px;font-weight:800;}
        .date{font-size:12px;color:var(--secondary-text-color);text-transform:capitalize;margin-top:-8px;}
        .recap-zone{padding:10px 14px;border-radius:12px;border:1px solid rgba(33,150,243,0.2);background:rgba(33,150,243,0.05);cursor:pointer;text-align:center;font-size:13px;color:var(--secondary-text-color);transition:background 0.15s;}
        .recap-zone:hover{background:rgba(33,150,243,0.12);}
        .chart-zone{padding:10px 12px;border-radius:12px;border:1px solid rgba(120,120,120,0.12);background:rgba(120,120,120,0.03);cursor:pointer;}
        .chart-zone:hover{background:rgba(33,150,243,0.05);}
        .chart-title{font-size:12px;font-weight:700;color:var(--secondary-text-color);margin-bottom:4px;}
        .cat-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;}
        .cat-btn{display:flex;align-items:center;gap:8px;padding:10px 12px;border-radius:12px;border:1px solid rgba(33,150,243,0.25);background:rgba(33,150,243,0.07);color:var(--primary-text-color);font-size:14px;font-weight:700;cursor:pointer;transition:background 0.15s;width:100%;}
        .cat-btn:hover{background:rgba(33,150,243,0.15);}
        .cat-count{margin-left:auto;font-size:11px;font-weight:400;color:var(--secondary-text-color);}
        .muted{color:var(--secondary-text-color);font-size:13px;}
        .popup-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.42);display:flex;align-items:center;justify-content:center;padding:16px;z-index:9999;}
        .popup-card{width:min(760px,100%);max-height:85vh;overflow:auto;background:var(--card-background-color,#fff);color:var(--primary-text-color);border-radius:22px;padding:18px;box-shadow:0 14px 36px rgba(0,0,0,0.24);font-family:Arial,sans-serif;}
        .popup-header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:12px;}
        .popup-header h2{margin:0;font-size:20px;}
        .popup-close{width:auto;min-width:80px;padding:8px 14px;border-radius:10px;border:1px solid rgba(120,120,120,0.3);background:rgba(120,120,120,0.1);color:var(--primary-text-color);cursor:pointer;font-weight:700;}
        .popup-content{display:flex;flex-direction:column;gap:14px;}
        .popup-group{border:1px solid rgba(120,120,120,0.14);border-radius:16px;padding:14px;background:rgba(120,120,120,0.03);}
        .popup-group h3{font-size:16px;margin:0 0 10px;}
        .popup-list{display:flex;flex-direction:column;gap:10px;}
        .popup-item{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 0;border-top:1px solid rgba(120,120,120,0.12);}
        .popup-item:first-child{border-top:none;padding-top:0;}
        .del-btn{background:none;border:1px solid rgba(220,80,80,0.3);border-radius:8px;padding:4px 8px;cursor:pointer;font-size:13px;color:#e53935;width:auto;}
        .ai-photo-zone{border:1px solid rgba(33,150,243,0.28);border-radius:16px;padding:14px;background:rgba(33,150,243,0.05);}
        .ai-photo-title{font-weight:700;font-size:15px;margin-bottom:10px;}
        .ai-photo-btn{display:flex;align-items:center;justify-content:center;padding:11px 16px;border-radius:12px;border:1px solid rgba(33,150,243,0.4);background:var(--primary-color,#2196f3);color:white;font-size:14px;font-weight:700;cursor:pointer;width:100%;}
        .ai-result-box{margin-top:14px;border:1px solid rgba(33,150,243,0.3);border-radius:14px;padding:14px;background:rgba(33,150,243,0.07);}
        .ai-result-title{font-weight:700;font-size:14px;margin-bottom:10px;color:var(--primary-color,#2196f3);}
        .ai-separator{text-align:center;color:var(--secondary-text-color);font-size:13px;margin:4px 0;}
        .form-row{display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin-top:8px;}
        .full{grid-column:1/-1;}
        label{display:block;font-size:13px;margin-bottom:4px;color:var(--secondary-text-color);}
        input,select{width:100%;box-sizing:border-box;border-radius:10px;border:1px solid rgba(120,120,120,0.24);padding:10px 12px;font-size:14px;background:var(--card-background-color,#fff);color:var(--primary-text-color);}
        input[readonly]{opacity:0.7;}
        .actions{display:flex;gap:8px;flex-wrap:wrap;}
        button{width:100%;box-sizing:border-box;border-radius:10px;border:none;padding:10px 14px;font-size:14px;font-weight:700;background:var(--primary-color,#2196f3);color:white;cursor:pointer;}
        button:disabled{opacity:0.6;cursor:not-allowed;}
        .actions button:last-child{background:rgba(120,120,120,0.15);color:var(--primary-text-color);}
        .popup-close{background:rgba(120,120,120,0.15)!important;color:var(--primary-text-color)!important;}
      </style>

      <div class="card">
        <div class="title">${this._esc(title)}</div>
        <div class="date">${today}</div>
        ${profile
          ? this._renderBlocks(totalCal, goalCal, totalProt, goalProt, entries, categories, gaugeStyle)
          : `<div style="color:var(--secondary-text-color);font-size:13px;text-align:center;padding:16px 0;">⏳ Chargement du profil...</div>`
        }
      </div>

      ${this._renderRecapPopup()}
      ${this._renderHistoryPopup()}
      ${this._renderAddPopup()}
    `;

    // ── Événements ──
    this.shadowRoot.getElementById("recapZone")?.addEventListener("click",()=>{this._showRecapPopup=true;this._render();});
    this.shadowRoot.getElementById("closeRecapBtn")?.addEventListener("click",()=>{this._showRecapPopup=false;this._render();});
    this.shadowRoot.getElementById("recapOverlay")?.addEventListener("click",(e)=>{if(e.target.id==="recapOverlay"){this._showRecapPopup=false;this._render();}});

    this.shadowRoot.getElementById("chartZone")?.addEventListener("click",()=>{this._showHistoryPopup=true;this._render();});
    this.shadowRoot.getElementById("closeHistoryBtn")?.addEventListener("click",()=>{this._showHistoryPopup=false;this._render();});
    this.shadowRoot.getElementById("historyOverlay")?.addEventListener("click",(e)=>{if(e.target.id==="historyOverlay"){this._showHistoryPopup=false;this._render();}});

    this.shadowRoot.querySelectorAll(".del-btn").forEach(btn=>btn.addEventListener("click",()=>this._deleteEntry(btn.getAttribute("data-delete-id"))));

    this.shadowRoot.querySelectorAll(".cat-btn").forEach(btn=>btn.addEventListener("click",()=>{
      this._addPopupCategory=btn.getAttribute("data-cat");
      this._foodForm={templateId:"custom",name:"",quantity:"",calories:"",proteins:"",category:btn.getAttribute("data-cat")};
      this._aiResult=null;this._aiError=null;this._render();
    }));

    this.shadowRoot.getElementById("closeAddBtn")?.addEventListener("click",()=>{this._addPopupCategory=null;this._aiResult=null;this._render();});
    this.shadowRoot.getElementById("addOverlay")?.addEventListener("click",(e)=>{if(e.target.id==="addOverlay"){this._addPopupCategory=null;this._saveToLibraryData=null;this._aiResult=null;this._render();}});

    this.shadowRoot.getElementById("templateId")?.addEventListener("change",(e)=>{
      this._foodForm.templateId=e.target.value;
      const food=(this._profile?.foods||[]).find(f=>f.id===e.target.value);
      this._foodForm.name=food?food.name:"";this._foodForm.quantity="";this._render();
    });
    this.shadowRoot.getElementById("foodName")?.addEventListener("input",(e)=>{this._foodForm.name=e.target.value;});
    this.shadowRoot.getElementById("quantity")?.addEventListener("input",(e)=>{
      this._foodForm.quantity=e.target.value;
      const cEl=this.shadowRoot.getElementById("computedCalories");
      const pEl=this.shadowRoot.getElementById("computedProteins");
      if(cEl)cEl.value=this._getComputedCalories();
      if(pEl)pEl.value=this._getComputedProteins();
    });
    this.shadowRoot.getElementById("manualCalories")?.addEventListener("input",(e)=>{this._foodForm.calories=e.target.value;});
    this.shadowRoot.getElementById("manualProteins")?.addEventListener("input",(e)=>{this._foodForm.proteins=e.target.value;});
    this.shadowRoot.getElementById("category")?.addEventListener("change",(e)=>{this._foodForm.category=e.target.value;});
    this.shadowRoot.getElementById("addEntryBtn")?.addEventListener("click",()=>this._addEntry());
    this.shadowRoot.getElementById("aiPhotoInput")?.addEventListener("change",(e)=>{if(e.target.files?.[0])this._analyzePhoto(e.target.files[0]);});
    this.shadowRoot.getElementById("confirmAiBtn")?.addEventListener("click",()=>this._confirmAiResult());
    this.shadowRoot.getElementById("cancelAiBtn")?.addEventListener("click",()=>{this._aiResult=null;this._aiError=null;this._render();});
    this.shadowRoot.getElementById("confirmLibBtn")?.addEventListener("click",()=>this._confirmSaveToLibrary());
    this.shadowRoot.getElementById("cancelLibBtn")?.addEventListener("click",()=>{this._saveToLibraryData=null;this._addPopupCategory=null;this._render();});
  }

  connectedCallback() { this._render(); }
}

customElements.define("suivi-alimentation-card", SuiviAlimentationCard);
window.customCards = window.customCards || [];
window.customCards.push({
  type: "suivi-alimentation-card",
  name: "Suivi Alimentation",
  description: "Carte de suivi journalier — multi-profils — v0.20",
  preview: false,
});

(() => {
  let webMenuB64 = "";
  let lastB64 = "";
  let micStream = null;
  let recording = null;
  let lang = "pl";
  let photoSeq = 0;
  // zdjęcia zapisane w Cache, zanim zapis się skończy (do odczytu przy poprawkach)
  const pendingPhotos = {};
  const PHOTOS = "mealquo-photos";
  // klucz publiczny VAPID serwera (cloudflare/wrangler.jsonc)
  const VAPID_PUBLIC = "BN9SOak0eunKcNQJinf3C_5GwdoZLgowNbDkACBo9Ujnbd_u9yjZnPF9MeYYaeTx9j97u9eV8bvDoPuCOOs2mIE";
  let reminderCfg = null;
  let photoReady = Promise.resolve();

  function hintLang() {
    if (lang === "uk") return "українською";
    if (lang === "en") return "in English";
    return "po polsku";
  }

  function pack(text) {
    return btoa(unescape(encodeURIComponent(text || "{\"items\":[]}")));
  }

  // Gemini przez serwer (/api/ai); przy błędzie komunikat i pusty wynik zamiast wiecznego czekania
  async function ask(body, path) {
    let kind = "offline";
    try {
      const res = await fetch(path || "/api/ai", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok && data.text) return data.text;
      kind = data.error || (res.status === 429 ? "quota" : "server");
    } catch (_) {
      kind = navigator.onLine === false ? "offline" : "timeout";
    }
    if (window.onAiError) window.onAiError(kind);
    return "{\"items\":[]}";
  }

  // zdjęcie z iPhone'a ma kilka MB; do AI i do zapisu wystarczy 1280 px
  function shrink(dataUrl, max) {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => {
        const w = img.naturalWidth || img.width;
        const h = img.naturalHeight || img.height;
        const scale = Math.min(1, max / Math.max(w, h));
        const canvas = document.createElement("canvas");
        canvas.width = Math.max(1, Math.round(w * scale));
        canvas.height = Math.max(1, Math.round(h * scale));
        canvas.getContext("2d").drawImage(img, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL("image/jpeg", 0.8));
      };
      img.onerror = () => resolve(dataUrl);
      img.src = dataUrl;
    });
  }

  // photos/… z Cache albo data: -> czyste base64
  async function b64Of(image) {
    const s = String(image || "");
    if (!s) return "";
    if (s.indexOf("photos/") === 0) {
      if (pendingPhotos[s]) return pendingPhotos[s].split(",")[1] || "";
      try {
        const cache = await caches.open(PHOTOS);
        const hit = await cache.match(s);
        if (!hit) return "";
        const blob = await hit.blob();
        const url = await new Promise((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result || ""));
          reader.readAsDataURL(blob);
        });
        return url.split(",")[1] || "";
      } catch (_) {
        return "";
      }
    }
    const comma = s.indexOf(",");
    return s.indexOf("data:") === 0 && comma > 0 ? s.slice(comma + 1) : s;
  }

  function platePrompt(comment) {
    const extra = (comment || "").trim()
      ? " Użytkownik poprawia rozpoznanie. To jest ważniejsze niż samo zdjęcie: " + comment + ". "
        + "Jeśli zjadł tylko część, grams i kcal to ta część (połowa = 50% kcal). "
        + "Jeśli dopowiada sól, oliwę, sos albo składnik, dopisz to do nazwy i dolicz kcal. "
        + "reason: jedno zdanie " + hintLang() + ", konkretnie co się zmieniło po komentarzu. Nie zostawiaj reason pustego. "
      : "";
    return "Na zdjęciu jest jedzenie albo napój." + extra
      + " Zwróć wyłącznie JSON: {\"reason\":\"\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"count\":1,\"grams\":150,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}."
      + " Najbliższe duże danie z przodu jest obowiązkowe. Napój z przodu też. Pomiń tło. Max 3."
      + " Nazwa maksymalnie konkretna, nigdy jednym słowem. Zakazane same: mięso, danie, sałatka, zupa, ryba, kurczak, napój, makaron."
      + " Podaj rodzaj, część, kolor, sposób i dodatek albo sos. Dobrze: pierś z kurczaka grillowana z ryżem i brokułem; zupa pomidorowa z ryżem; jabłko zielone."
      + " Markę z etykiety wpisz, gdy da się ją odczytać. name_pl, name_uk i name_en tak samo szczegółowe.";
  }

  function toast(text) {
    const pop = document.createElement("div");
    pop.className = "saved-pop";
    pop.textContent = text;
    document.body.appendChild(pop);
    setTimeout(() => pop.remove(), 2200);
  }

  function filePhoto(capture) {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "image/*";
    if (capture) input.setAttribute("capture", "environment");
    input.onchange = () => {
      const file = input.files && input.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => finishPhoto(String(reader.result || ""));
      reader.readAsDataURL(file);
    };
    input.click();
  }

  async function finishPhoto(original) {
    const dataUrl = await shrink(original, 1280);
    const raw = dataUrl.includes(",") ? dataUrl.split(",")[1] : dataUrl;
    lastB64 = raw;
    const json = await ask({ image: raw, prompt: platePrompt("") });
    if (window.onPlateReady) window.onPlateReady(dataUrl, pack(json));
  }

  function openLiveCamera() {
    const sheet = document.createElement("div");
    sheet.className = "cam-sheet";
    sheet.innerHTML = `<video playsinline autoplay muted></video>
      <div class="cam-bar">
        <button type="button" id="camx">Anuluj</button>
        <button type="button" class="cam-shutter" id="camgo"><i></i></button>
        <button type="button" id="camroll">Rolka</button>
      </div>`;
    document.body.appendChild(sheet);
    const video = sheet.querySelector("video");
    let stream = null;
    const close = () => {
      if (stream) stream.getTracks().forEach((t) => t.stop());
      sheet.remove();
    };
    sheet.querySelector("#camx").onclick = close;
    sheet.querySelector("#camroll").onclick = () => { close(); filePhoto(false); };
    sheet.querySelector("#camgo").onclick = () => {
      const canvas = document.createElement("canvas");
      canvas.width = video.videoWidth || 720;
      canvas.height = video.videoHeight || 1280;
      canvas.getContext("2d").drawImage(video, 0, 0);
      const url = canvas.toDataURL("image/jpeg", 0.82);
      close();
      finishPhoto(url);
    };
    navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false })
      .then((s) => { stream = s; video.srcObject = s; })
      .catch(() => { close(); filePhoto(true); });
  }

  // nagranie jako WAV 16 kHz mono (Safari nagrywa MediaRecorderem audio/mp4, którego Gemini nie przyjmuje)
  async function listen() {
    if (window.onVoiceStart) window.onVoiceStart();
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx || !navigator.mediaDevices) { if (window.onVoiceFail) window.onVoiceFail(); return; }
    // AudioContext jeszcze w geście dotknięcia, inaczej iOS zostawia go wstrzymanego
    const ctx = new Ctx();
    try {
      ctx.resume();
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (_) {
      ctx.close();
      if (window.onVoiceFail) window.onVoiceFail();
      return;
    }
    const source = ctx.createMediaStreamSource(micStream);
    const proc = ctx.createScriptProcessor(4096, 1, 1);
    const buffers = [];
    const started = Date.now();
    let spoke = false;
    let lastVoice = started;
    let finished = false;
    const finish = async (cancel) => {
      if (finished) return;
      finished = true;
      recording = null;
      proc.disconnect();
      source.disconnect();
      micStream.getTracks().forEach((t) => t.stop());
      micStream = null;
      const rate = ctx.sampleRate;
      ctx.close();
      if (cancel) return;
      if (!spoke) { if (window.onVoiceFail) window.onVoiceFail(); return; }
      const json = await ask({ audio: wavBase64(buffers, rate, 16000), mime: "audio/wav",
        prompt: "Na nagraniu mówi jedna osoba — ta, która zaczęła pierwsza. Przepisz wyłącznie jej słowa dokładnie w języku, w którym mówi (np. ukraiński, polski, angielski, rosyjski), jego alfabetem, bez tłumaczenia. Pomiń drugą osobę, która włącza się w trakcie, oraz radio, telewizor, muzykę i rozmowy obok. Zwróć wyłącznie JSON {\"text\":\"\"}." });
      let text = "";
      try { text = (JSON.parse(json).text || "").trim(); } catch (_) {}
      if (text && window.onVoice) window.onVoice(text);
      else if (window.onVoiceFail) window.onVoiceFail();
    };
    proc.onaudioprocess = (e) => {
      if (finished) return;
      const data = new Float32Array(e.inputBuffer.getChannelData(0));
      buffers.push(data);
      let energy = 0;
      for (let i = 0; i < data.length; i++) energy += Math.abs(data[i]);
      if (energy / data.length > 0.012) { spoke = true; lastVoice = Date.now(); }
      if ((spoke && Date.now() - lastVoice > 800) || Date.now() - started > 8000) finish(false);
    };
    source.connect(proc);
    proc.connect(ctx.destination);
    recording = finish;
  }

  function wavBase64(buffers, from, to) {
    let total = 0;
    buffers.forEach((b) => { total += b.length; });
    const ratio = from / to;
    const n = Math.floor(total / ratio);
    const pcm = new DataView(new ArrayBuffer(44 + n * 2));
    const text = (o, t) => { for (let i = 0; i < t.length; i++) pcm.setUint8(o + i, t.charCodeAt(i)); };
    text(0, "RIFF"); pcm.setUint32(4, 36 + n * 2, true); text(8, "WAVE"); text(12, "fmt ");
    pcm.setUint32(16, 16, true); pcm.setUint16(20, 1, true); pcm.setUint16(22, 1, true);
    pcm.setUint32(24, to, true); pcm.setUint32(28, to * 2, true); pcm.setUint16(32, 2, true); pcm.setUint16(34, 16, true);
    text(36, "data"); pcm.setUint32(40, n * 2, true);
    // proste uśrednianie próbek przy zmianie częstotliwości (48 kHz -> 16 kHz)
    const flat = new Float32Array(total);
    let at = 0;
    buffers.forEach((b) => { flat.set(b, at); at += b.length; });
    for (let i = 0; i < n; i++) {
      const a = Math.floor(i * ratio);
      const b = Math.min(total, Math.floor((i + 1) * ratio));
      let sum = 0;
      for (let j = a; j < b; j++) sum += flat[j];
      const v = Math.max(-1, Math.min(1, sum / Math.max(1, b - a)));
      pcm.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
    }
    const bytes = new Uint8Array(pcm.buffer);
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  window.MealquoNative = {
    setLang(code) { if (code) lang = code; },
    takePhoto() { openLiveCamera(); },
    // kadr ze skanera na żywo: ta sama dokładna analiza co zdjęcie
    analyzePhoto(dataUrl) {
      if (window.onNativePhoto) window.onNativePhoto(dataUrl);
      finishPhoto(dataUrl);
    },
    pickGallery() { filePhoto(false); },
    stopListen() {
      if (recording) recording(true);
    },
    listen() { listen(); },
    // poprawki po komentarzu: /api/fix (Claude Sonnet 5.5, zapas Gemini)
    async refine(comment) {
      await photoReady;
      if (!lastB64) return;
      const json = await ask({ image: lastB64, comment: comment || "", lang }, "/api/fix");
      if (window.onAiResult) window.onAiResult(pack(json));
    },
    async rescan(id, comment, image) {
      const raw = await b64Of(image);
      const use = raw || lastB64;
      const json = use
        ? await ask({ image: use, comment: comment || "", lang }, "/api/fix")
        : await ask({ prompt: "Użytkownik poprawia danie: " + (comment || "") + ". Zwróć wyłącznie JSON {\"reason\":\"\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"count\":1,\"grams\":150,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}." });
      if (window.onMealFix) window.onMealFix(id, pack(json));
    },
    async lookupFood(name) {
      const json = await ask({
        prompt: "Użytkownik wpisał produkt: " + name + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":100,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}. Jedna pozycja. grams to typowa porcja. Bez markdown.",
      });
      if (window.onFoodHit) window.onFoodHit(name, pack(json));
    },
    dishPhoto(key, query) {
      const q = encodeURIComponent((query || key) + " food dish");
      fetch("https://commons.wikimedia.org/w/api.php?origin=*&action=query&format=json&generator=search&gsrsearch=" + q + "&gsrnamespace=6&gsrlimit=4&prop=pageimages&piprop=thumbnail&pithumbsize=240")
        .then((r) => r.json())
        .then((data) => {
          const pages = (data.query && data.query.pages) || {};
          let url = "";
          Object.keys(pages).forEach((id) => {
            const thumb = pages[id].thumbnail;
            if (!url && thumb && thumb.source) url = thumb.source;
          });
          if (window.onDishPhoto) window.onDishPhoto(key, url);
        })
        .catch(() => { if (window.onDishPhoto) window.onDishPhoto(key, ""); });
    },
    recipeFor(id, title, kcal) {
      const langName = hintLang();
      if (window.onRecipeProgress) window.onRecipeProgress(id, 30, "Piszę składniki…");
      ask({
        prompt: "Napisz krótki przepis domowy na: " + title + ". Porcja około " + kcal + " kcal. Język: " + langName + ". "
          + "Zwróć wyłącznie JSON {\"intro\":\"jedno zdanie\",\"minutes\":25,\"ingredients\":[\"200 g ryby\"],\"steps\":[\"Rozgrzej patelnię.\"]}. "
          + "4 do 8 składników z gramaturą. 4 do 7 krótkich kroków. Bez markdown.",
      }).then((json) => {
        if (window.onRecipe) window.onRecipe(id, pack(json));
      }).catch(() => {
        if (window.onRecipe) window.onRecipe(id, pack("{\"intro\":\"\",\"minutes\":20,\"ingredients\":[],\"steps\":[]}"));
      });
    },
    speak(text) {
      if (!window.speechSynthesis) { if (window.onSpeakEnd) window.onSpeakEnd(); return; }
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text || "");
      u.lang = lang === "uk" ? "uk-UA" : lang === "en" ? "en-US" : "pl-PL";
      u.onboundary = (ev) => {
        const pct = text ? Math.min(100, Math.round(((ev.charIndex || 0) * 100) / text.length)) : 0;
        if (window.onSpeakProgress) window.onSpeakProgress(pct);
      };
      u.onend = () => { if (window.onSpeakEnd) window.onSpeakEnd(); };
      u.onerror = () => { if (window.onSpeakEnd) window.onSpeakEnd(); };
      window.speechSynthesis.speak(u);
    },
    stopSpeak() {
      if (window.speechSynthesis) window.speechSynthesis.cancel();
      if (window.onSpeakEnd) window.onSpeakEnd();
    },
    whereAmI() {
      if (!navigator.geolocation) {
        if (window.onHere) window.onHere(0, 0);
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (pos) => { if (window.onHere) window.onHere(pos.coords.latitude, pos.coords.longitude); },
        () => { if (window.onHere) window.onHere(0, 0); },
        { enableHighAccuracy: true, maximumAge: 60000, timeout: 12000 }
      );
    },
    async placesNear(lat, lon, skip) {
      const avoid = skip ? " Pomiń te lokale: " + skip + "." : "";
      const json = await ask({
        search: true,
        prompt: "Użytkownik jest w punkcie " + lat + ", " + lon + ". Znajdź 3 najbliższe restauracje, kawiarnie albo bary, w których może właśnie siedzieć. Od najbliższej."
          + avoid
          + " Zwróć wyłącznie JSON {\"items\":[{\"name\":\"\",\"address\":\"\",\"maps\":\"https://www.google.com/maps/search/?api=1&query=\",\"meters\":40}]}. maps to link Google Maps. Bez markdown.",
      });
      if (window.onNearby) window.onNearby(pack(json));
    },
    async menuFromLink(url, kcal, p, c, diet, skip) {
      const avoid = skip ? " Nie proponuj tych dań: " + skip + "." : "";
      const json = await ask({
        search: true,
        prompt: "Link restauracji z Google Maps: " + url
          + ". Zostało " + kcal + " kcal, białko " + p + " g, węgle " + c + " g. Dieta: " + (diet || "normal") + ". "
          + "Znajdź tę konkretną restaurację i jej kartę. Zwróć wyłącznie JSON {\"place\":\"nazwa\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":280,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}."
          + " Dokładnie 3 inne dania z tej karty, które mieszczą się w pozostałych kaloriach. Prawdziwe pozycje z menu." + avoid + " Bez markdown.",
      });
      if (window.onMapMenu) window.onMapMenu(pack(json));
    },
    pickMenuShot(kcal, p, c, diet) {
      const input = document.createElement("input");
      input.type = "file";
      input.accept = "image/*";
      input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) {
          if (window.onMenuShot) window.onMenuShot("");
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          const data = String(reader.result || "");
          const comma = data.indexOf(",");
          this.readMenu(comma >= 0 ? data.slice(comma + 1) : data, kcal, p, c, diet, "");
        };
        reader.readAsDataURL(file);
      };
      input.click();
    },
    async readMenu(image, kcal, p, c, diet, skip) {
      const raw = image || webMenuB64;
      if (raw) webMenuB64 = raw;
      const avoid = skip ? " Nie proponuj: " + skip + "." : "";
      const json = await ask({
        image: raw,
        prompt: "To jest zrzut ekranu karty restauracji albo listy dań."
          + " Zostało " + kcal + " kcal, białko " + p + " g, węgle " + c + " g. Dieta: " + (diet || "normal") + "."
          + " Wybierz dokładnie 3 pozycje WIDOCZNE na zrzucie, które mieszczą się w pozostałych kaloriach."
          + " Nie wymyślaj dań spoza zdjęcia." + avoid
          + " Zwróć wyłącznie JSON {\"place\":\"nazwa miejsca albo pusto\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":280,\"kcal\":0,\"p\":0,\"c\":0,\"f\":0}]}. Bez markdown.",
      });
      if (window.onMenuShot) window.onMenuShot(pack(json));
    },
    async describeMeal(text) {
      const json = await ask({
        prompt: "Użytkownik powiedział, co zjadł: " + text + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"grams\":150,\"kcal\":200,\"p\":10,\"c\":20,\"f\":8}]}. Max 6. Bez markdown.",
      });
      if (window.onAiResult) window.onAiResult(pack(json));
    },
    async suggestExercise(query) {
      const token = (window.__exTok = (window.__exTok || 0) + 1);
      const json = await ask({
        prompt: "Użytkownik wpisał początek ćwiczenia: " + query + ". Zwróć wyłącznie JSON {\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"kcal\":200}]}. Do 6. kcal za około 30 minut. Bez markdown.",
      });
      if (window.onExerciseHints) window.onExerciseHints(token, pack(json));
    },
    async suggestTypes(name) {
      const token = (window.__tyTok = (window.__tyTok || 0) + 1);
      const json = await ask({
        prompt: "Wybrane ćwiczenie: " + name + ". Zwróć wyłącznie JSON {\"question_pl\":\"Jaki to był trening?\",\"question_uk\":\"Яке саме тренування?\",\"question_en\":\"Which workout was it?\",\"items\":[{\"name_pl\":\"\",\"name_uk\":\"\",\"name_en\":\"\",\"kcal\":220}]}. 5 wariantów, różne kcal. Bez markdown.",
      });
      if (window.onExerciseTypes) window.onExerciseTypes(token, pack(json));
    },
    async burn(name, amount, unit, weight) {
      const token = (window.__burnTok = (window.__burnTok || 0) + 1);
      const json = await ask({
        prompt: "Policz kcal dla wariantu: " + name + ". Ilość: " + amount + " " + (unit === "km" ? "km" : "min") + ". Waga: " + weight + " kg. Ten sam sport i inny wariant = inne kcal. Zwróć wyłącznie JSON {\"kcal\":280,\"note\":\"\"}. note " + hintLang() + ".",
      });
      if (window.onBurn) window.onBurn(token, pack(json));
    },
    async foodNote(id, name, kcal, p, c, f, grams, count) {
      const json = await ask({
        prompt: "Zwróć wyłącznie JSON {\"note\":\"\",\"better\":\"\"}. note: dwa zdania " + hintLang() + " o tym daniu. Pierwsze: rodzaj, kolor, ile, gramy. Drugie: jedna cecha. Danie: " + name + ". Sztuk: " + count + ". Gramów: " + grams + ". " + kcal + " kcal, białko " + p + ", węgle " + c + ", tłuszcz " + f + ".",
      });
      if (window.onFoodNote) window.onFoodNote(id, pack(json));
    },
    readWatch() {
      if (window.onWatchStatus) window.onWatchStatus("missing");
    },
    createJournal() {
      const raw = localStorage.getItem("talerz") || "{}";
      const blob = new Blob([JSON.stringify({ rev: Date.now(), state: JSON.parse(raw), accounts: JSON.parse(localStorage.getItem("mealquo_accounts") || "[]") })], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "mealquo.json";
      a.click();
      if (window.onSyncLinked) window.onSyncLinked("new");
    },
    openJournal() {
      const input = document.createElement("input");
      input.type = "file";
      input.accept = "application/json,.json";
      input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => {
          if (window.onSyncLinked) window.onSyncLinked("open");
          if (window.onJournal) window.onJournal(pack(String(reader.result || "")));
        };
        reader.readAsText(file);
      };
      input.click();
    },
    pushJournal() {},
    pullJournal() {
      if (window.onSyncStatus) window.onSyncStatus("off");
    },
    // przypomnienia: ustawienia z app.js idą na serwer, który wysyła Web Push (iOS 16.4+, aplikacja z ekranu początkowego)
    setReminders(json) {
      try { reminderCfg = JSON.parse(json); } catch (_) { return; }
      pushSync(false);
    },
    askNotify() {
      if (pushState() === "install") {
        toast(lang === "uk" ? "Спершу додай Mealquo на початковий екран." : lang === "en" ? "First add Mealquo to the Home Screen." : "Najpierw dodaj Mealquo do ekranu początkowego.");
        return;
      }
      if (pushState() !== "ask") { pushSync(false); return; }
      // requestPermission musi być wywołane od razu w geście dotknięcia
      Notification.requestPermission().then((perm) => {
        if (perm === "granted") pushSync(true);
        else if (typeof render === "function") render();
      });
    },
    pushState() { return pushState(); },
    appVersion() { return "web"; },
    // zdjęcie posiłku do Cache (service worker podaje je pod photos/…); localStorage w Safari mieści ~5 MB
    savePhoto(dataUrl) {
      if (!window.caches || String(dataUrl).indexOf("data:") !== 0) return "";
      const url = "photos/" + Date.now() + "_" + (++photoSeq) + ".jpg";
      pendingPhotos[url] = dataUrl;
      fetch(dataUrl).then((r) => r.blob())
        .then((blob) => caches.open(PHOTOS).then((c) => c.put(url, new Response(blob, { headers: { "Content-Type": "image/jpeg" } }))))
        .catch(() => {})
        .finally(() => { delete pendingPhotos[url]; });
      return url;
    },
    prunePhotos(keep) {
      if (!window.caches) return;
      let names = [];
      try { names = JSON.parse(keep || "[]"); } catch (_) {}
      const used = new Set(names);
      caches.open(PHOTOS).then((cache) => cache.keys().then((keys) => keys.forEach((req) => {
        const name = new URL(req.url).pathname.split("/").pop();
        const at = +(name.split("_")[0]) || 0;
        if (!used.has(name) && Date.now() - at > 10 * 60 * 1000) cache.delete(req);
      }))).catch(() => {});
    },
    // base64 zdjęcia posiłku (photos/… z Cache) do wysyłki na serwer
    photoB64(path) { return b64Of(path); },
    usePhoto(image) {
      photoReady = b64Of(image).then((b64) => { if (b64) lastB64 = b64; });
    },
  };

  function pushState() {
    const standalone = window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone;
    if (!("serviceWorker" in navigator)) return "unsupported";
    if (!("PushManager" in window) || !("Notification" in window)) return standalone ? "unsupported" : "install";
    if (Notification.permission === "denied") return "denied";
    if (Notification.permission === "granted") return "on";
    return "ask";
  }

  function vapidKey() {
    const s = VAPID_PUBLIC.replace(/-/g, "+").replace(/_/g, "/");
    return Uint8Array.from(atob(s + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
  }

  // subskrypcja + aktualne ustawienia na serwer; fresh: zaraz po zgodzie (odświeża profil)
  async function pushSync(fresh) {
    if (pushState() !== "on" || !reminderCfg) return;
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = (await reg.pushManager.getSubscription())
        || await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: vapidKey() });
      await fetch("/api/push", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ subscription: sub.toJSON(), cfg: reminderCfg, tz: Intl.DateTimeFormat().resolvedOptions().timeZone }),
      });
      if (fresh) {
        toast(lang === "uk" ? "Нагадування увімкнено." : lang === "en" ? "Reminders are on." : "Przypomnienia włączone.");
        if (typeof render === "function") render();
      }
    } catch (_) {}
  }

  // PWA: offline i zdjęcia z Cache; trwałe miejsce, żeby Safari nie czyściło danych
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});

  function tip() {
    const standalone = window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone;
    // tylko iPhone/iPad w Safari (nie komputer, nie Android, nie aplikacja iOS z własnym mostkiem)
    const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    const nativeApp = !!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.mealquo);
    if (!ios || nativeApp || standalone || sessionStorage.getItem("mealquo-tip")) return;
    const bar = document.createElement("div");
    bar.className = "ios-tip";
    bar.textContent = "Na iPhonie: Udostępnij, potem Dodaj do ekranu początkowego.";
    const phone = document.getElementById("phone");
    if (phone) phone.prepend(bar);
    bar.onclick = () => { bar.remove(); sessionStorage.setItem("mealquo-tip", "1"); };
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", tip);
  else tip();
})();

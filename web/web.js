(() => {
  let webMenuB64 = "";
  let lastB64 = "";
  let micStream = null;
  let recorder = null;
  let chunks = [];

  function hintLang() {
    if (lang === "uk") return "українською";
    if (lang === "en") return "in English";
    return "po polsku";
  }

  function pack(text) {
    return btoa(unescape(encodeURIComponent(text || "{\"items\":[]}")));
  }

  async function ask(body) {
    const res = await fetch("/api/ai", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error("ai");
    const data = await res.json();
    return data.text || "{\"items\":[]}";
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

  async function finishPhoto(dataUrl) {
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

  async function listen() {
    if (window.onVoiceStart) window.onVoiceStart();
    try {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mime = MediaRecorder.isTypeSupported("audio/mp4") ? "audio/mp4" : "audio/webm";
      chunks = [];
      recorder = new MediaRecorder(micStream, { mimeType: mime });
      recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
      recorder.onstop = async () => {
        const blob = new Blob(chunks, { type: mime });
        micStream.getTracks().forEach((t) => t.stop());
        micStream = null;
        const data = await blob.arrayBuffer();
        const bytes = new Uint8Array(data);
        let bin = "";
        for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        const audio = btoa(bin);
        try {
          const json = await ask({
            audio,
            mime,
            prompt: "Na nagraniu mówi jedna osoba — ta, która zaczęła pierwsza. Przepisz wyłącznie jej słowa, " + hintLang() + ". Pomiń drugą osobę, która włącza się w trakcie, oraz radio, telewizor, muzykę i rozmowy obok. Zwróć wyłącznie JSON {\"text\":\"\"}.",
          });
          const parsed = JSON.parse(json);
          if (window.onVoice) window.onVoice(parsed.text || "");
        } catch (_) {
          if (window.onVoiceFail) window.onVoiceFail();
        }
      };
      recorder.start();
      const audioCtx = new AudioContext();
      const source = audioCtx.createMediaStreamSource(micStream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      const wave = new Uint8Array(analyser.fftSize);
      const started = Date.now();
      let spoke = false;
      let lastVoice = started;
      const watch = setInterval(() => {
        if (!recorder || recorder.state !== "recording") { clearInterval(watch); audioCtx.close(); return; }
        analyser.getByteTimeDomainData(wave);
        let energy = 0;
        for (let i = 0; i < wave.length; i++) energy += Math.abs(wave[i] - 128);
        if (energy / wave.length > 5) { spoke = true; lastVoice = Date.now(); }
        const quiet = spoke && Date.now() - lastVoice > 700;
        const tooLong = Date.now() - started > 8000;
        if (quiet || tooLong) {
          clearInterval(watch);
          audioCtx.close();
          recorder.stop();
        }
      }, 80);
    } catch (_) {
      if (window.onVoiceFail) window.onVoiceFail();
    }
  }

  window.MealquoNative = {
    setLang(code) { if (code) lang = code; },
    takePhoto() { openLiveCamera(); },
    pickGallery() { filePhoto(false); },
    stopListen() {
      if (recorder && recorder.state === "recording") recorder.stop();
    },
    listen() { listen(); },
    async refine(comment) {
      if (!lastB64) return;
      const json = await ask({ image: lastB64, prompt: platePrompt(comment || "") });
      if (window.onAiResult) window.onAiResult(pack(json));
    },
    async rescan(id, comment, image) {
      let raw = image || "";
      const comma = raw.indexOf(",");
      if (raw.startsWith("data:") && comma > 0) raw = raw.slice(comma + 1);
      const use = raw || lastB64;
      const json = use
        ? await ask({ image: use, prompt: platePrompt(comment || "") })
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
    pickGoogle() {
      toast(lang === "uk" ? "На iPhone увійди поштою нижче." : lang === "en" ? "On iPhone, use email below." : "Na iPhonie zaloguj się e-mailem poniżej.");
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
    setReminders() {},
    askNotify() {},
  };

  function tip() {
    const standalone = window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone;
    if (standalone || sessionStorage.getItem("mealquo-tip")) return;
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

// Mealquo PWA: aplikacja działa offline, zdjęcia posiłków są w Cache (photos/…), przypomnienia przez Web Push
const SHELL = "mealquo-shell-v2";
const PHOTOS = "mealquo-photos";
const FILES = ["./", "index.html", "styles.css", "ios.css", "app.js", "web.js", "foods.json", "manifest.webmanifest", "img/icon.png", "img/icon-192.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith("mealquo-shell-") && k !== SHELL).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  if (url.pathname.startsWith("/photos/")) {
    e.respondWith(photo(req));
    return;
  }
  // najpierw sieć (nowa wersja od razu), bez sieci kopia
  e.respondWith(fetch(req).then((res) => {
    if (res.ok) {
      const copy = res.clone();
      caches.open(SHELL).then((c) => c.put(req, copy));
    }
    return res;
  }).catch(() => caches.match(req, { ignoreSearch: true }).then((hit) => hit || caches.match("index.html"))));
});

// zdjęcie mogło być zapisane przed chwilą – kilka krótkich prób
async function photo(req) {
  const cache = await caches.open(PHOTOS);
  for (let i = 0; i < 6; i++) {
    const hit = await cache.match(req, { ignoreSearch: true });
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, 250));
  }
  return new Response("", { status: 404 });
}

// przypomnienie z serwera (cloudflare/src/remind.js)
self.addEventListener("push", (e) => {
  let data = {};
  try { data = e.data ? e.data.json() : {}; } catch (_) {}
  e.waitUntil(self.registration.showNotification(data.title || "Mealquo", {
    body: data.body || "",
    tag: data.tag || "mealquo",
    icon: "img/icon-192.png",
    badge: "img/icon-192.png",
  }));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
    const open = list.find((c) => "focus" in c);
    return open ? open.focus() : self.clients.openWindow("./");
  }));
});

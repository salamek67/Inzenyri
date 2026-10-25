/* Only the public app shell and a validated encrypted database are cached. */
const SHELL_CACHE = "inzenyri-shell-16",
  DATA_CACHE = "inzenyri-data-1";
const assetPaths = [
  "./",
  "index.html",
  "css/styles.css",
  "js/app.js",
  "js/web-settings.js",
  "js/web-qr.js",
  "js/web-offline.js",
  "js/web-formatting.js",
  "js/practice-progress.js",
  "js/quiz-fill.js",
  "js/quiz-map.js",
  "css/quiz-map.css",
  "maps/world.json",
  "vendor/zxing-0.21.3.min.js",
  "vendor/marked-18.1.1.js",
  "vendor/dompurify-3.4.17.min.js",
  "vendor/katex-0.19.0.min.js",
  "vendor/katex-0.19.0.min.css",
  "icons/favicon.ico",
  "icons/favicon.png",
  "icons/apple-touch-icon.png",
  "icons/ai/chatgpt.svg",
  "icons/ai/claude.svg",
  "icons/ai/gemini.svg",
  "icons/ai/deepseek.svg",
  "vendor/fonts/KaTeX_AMS-Regular.woff2",
  "vendor/fonts/KaTeX_Caligraphic-Bold.woff2",
  "vendor/fonts/KaTeX_Caligraphic-Regular.woff2",
  "vendor/fonts/KaTeX_Fraktur-Bold.woff2",
  "vendor/fonts/KaTeX_Fraktur-Regular.woff2",
  "vendor/fonts/KaTeX_Main-Bold.woff2",
  "vendor/fonts/KaTeX_Main-BoldItalic.woff2",
  "vendor/fonts/KaTeX_Main-Italic.woff2",
  "vendor/fonts/KaTeX_Main-Regular.woff2",
  "vendor/fonts/KaTeX_Math-BoldItalic.woff2",
  "vendor/fonts/KaTeX_Math-Italic.woff2",
  "vendor/fonts/KaTeX_SansSerif-Bold.woff2",
  "vendor/fonts/KaTeX_SansSerif-Italic.woff2",
  "vendor/fonts/KaTeX_SansSerif-Regular.woff2",
  "vendor/fonts/KaTeX_Script-Regular.woff2",
  "vendor/fonts/KaTeX_Size1-Regular.woff2",
  "vendor/fonts/KaTeX_Size2-Regular.woff2",
  "vendor/fonts/KaTeX_Size3-Regular.woff2",
  "vendor/fonts/KaTeX_Size4-Regular.woff2",
  "vendor/fonts/KaTeX_Typewriter-Regular.woff2",
];
const assets = assetPaths.map(
  (path) => new URL(path, self.registration.scope).href,
);
const databaseUrl = new URL("data.enc.json", self.registration.scope).href;
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) => cache.addAll(assets)),
  );
});
self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (
          (name.startsWith("inzenyri-shell-") ||
            name.startsWith("inzenyri-data-")) &&
          ![SHELL_CACHE, DATA_CACHE].includes(name)
        )
          await caches.delete(name);
      }
      await self.clients.claim();
    })(),
  );
});
self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  const url = new URL(event.request.url);
  url.search = "";
  url.hash = "";
  if (url.href === databaseUrl) {
    event.respondWith(
      (async () => {
        try {
          return await fetch(event.request);
        } catch {
          const cached = await (
            await caches.open(DATA_CACHE)
          ).match(databaseUrl);
          if (!cached)
            return new Response("Offline databáze není uložená.", {
              status: 503,
            });
          const headers = new Headers(cached.headers);
          headers.set("X-Inzenyri-Offline", "1");
          return new Response(cached.body, { headers });
        }
      })(),
    );
  } else if (assets.includes(url.href)) {
    event.respondWith(
      (async () => {
        const cache = await caches.open(SHELL_CACHE).catch(() => null);
        let response;
        try {
          response = await fetch(event.request);
          if (!response.ok) throw new Error("Asset download failed");
        } catch {
          return (
            (await cache?.match(url.href)) ||
            new Response("Stránka není dostupná offline.", { status: 503 })
          );
        }
        // A storage quota error must not break a successful online request.
        try {
          await cache?.put(url.href, response.clone());
        } catch {
          /* Optional offline storage. */
        }
        return response;
      })(),
    );
  }
});
let databaseWrites = Promise.resolve();
self.addEventListener("message", (event) => {
  if (!event.source?.url?.startsWith(self.registration.scope)) return;
  databaseWrites = databaseWrites
    .catch(() => {})
    .then(async () => {
      const cache = await caches.open(DATA_CACHE);
      if (event.data?.type === "CLEAR_DATABASE")
        await cache.delete(databaseUrl);
      if (event.data?.type === "CACHE_DATABASE") {
        const value = event.data.envelope;
        if (
          value?.format !== "inzenyri-encrypted-data" ||
          value.version !== 1 ||
          typeof value.ciphertext !== "string"
        )
          return;
        // Do not store the caller's other fields (password, keyMaterial or plaintext).
        const envelope = {
          format: value.format,
          version: value.version,
          dbVersion: value.dbVersion,
          kdf: {
            name: value.kdf.name,
            hash: value.kdf.hash,
            iterations: value.kdf.iterations,
            salt: value.kdf.salt,
          },
          cipher: {
            name: value.cipher.name,
            keyLength: value.cipher.keyLength,
            tagLength: value.cipher.tagLength,
            iv: value.cipher.iv,
          },
          ciphertext: value.ciphertext,
        };
        await cache.put(
          databaseUrl,
          new Response(JSON.stringify(envelope), {
            headers: { "Content-Type": "application/json" },
          }),
        );
      }
      event.ports[0]?.postMessage({ ok: true });
    })
    .catch(() => {
      event.ports[0]?.postMessage({ ok: false });
    });
  event.waitUntil(databaseWrites);
});

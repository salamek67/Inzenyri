let offlineDatabaseEnabled = readPreference("offline-database", true);
const offlineToggle = document.getElementById("settingsOffline");
offlineToggle.checked = offlineDatabaseEnabled;
const offlineSupported =
  "serviceWorker" in navigator &&
  window.isSecureContext &&
  location.protocol !== "file:";
const offlineStatus = document.getElementById("offlineStatus");
const offlineWorker = offlineSupported
  ? navigator.serviceWorker
      .register("sw.js")
      .then(() => {
        return new Promise((resolve, reject) => {
          const timeout = setTimeout(
            () => reject(new Error("Offline installation timed out")),
            15000,
          );
          navigator.serviceWorker.ready.then((registration) => {
            clearTimeout(timeout);
            resolve(registration);
          });
        });
      })
      .catch(() => {
        offlineStatus.textContent =
          "Offline režim se nepodařilo připravit. Online přístup funguje dál.";
        return null;
      })
  : Promise.resolve(null);
if (!offlineSupported) {
  offlineToggle.disabled = true;
  offlineStatus.textContent =
    "Offline režim vyžaduje HTTPS a prohlížeč s podporou service workerů.";
}
async function offlineMessage(type, envelope) {
  const registration = await offlineWorker;
  if (!registration?.active) return false;
  return new Promise((resolve) => {
    const channel = new MessageChannel();
    const timeout = setTimeout(() => {
      channel.port1.close();
      resolve(false);
    }, 10000);
    channel.port1.onmessage = (event) => {
      clearTimeout(timeout);
      channel.port1.close();
      resolve(event.data?.ok === true);
    };
    registration.active.postMessage({ type, envelope }, [channel.port2]);
  });
}
async function persistOfflineDatabase(envelope) {
  if (!offlineDatabaseEnabled) return;
  try {
    const registration = await offlineWorker;
    if (!registration?.active || !offlineDatabaseEnabled) return;
    offlineStatus.textContent =
      "Připravuji šifrovanou databázi pro offline použití…";
    const saved = await offlineMessage("CACHE_DATABASE", envelope);
    if (offlineDatabaseEnabled)
      offlineStatus.textContent = saved
        ? `Připraveno offline · DB ${envelope.dbVersion || "bez verze"}`
        : "Databázi se nepodařilo uložit offline. Online přístup funguje dál.";
  } catch {
    offlineStatus.textContent =
      "Offline úložiště není dostupné. Online přístup funguje dál.";
  }
}

offlineToggle.addEventListener("change", async (event) => {
  offlineDatabaseEnabled = event.target.checked;
  writePreference("offline-database", offlineDatabaseEnabled);
  if (offlineDatabaseEnabled && unlockedData?.encryptedEnvelope)
    persistOfflineDatabase(unlockedData.encryptedEnvelope);
  else {
    try {
      const cleared = await offlineMessage("CLEAR_DATABASE");
      offlineStatus.textContent = cleared
        ? "Offline databáze byla odstraněna."
        : "Offline databázi se nepodařilo odstranit. Zkus to znovu.";
    } catch {
      offlineStatus.textContent = "Offline úložiště není dostupné.";
    }
  }
});
if (!offlineDatabaseEnabled)
  offlineWorker.then((registration) =>
    registration?.active?.postMessage({ type: "CLEAR_DATABASE" }),
  );

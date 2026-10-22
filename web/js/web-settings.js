function setCalendarExpanded(expanded) {
  document.getElementById("calendarBody").hidden = !expanded;
  document
    .querySelector(".calendar")
    .classList.toggle("is-collapsed", !expanded);
  const button = document.getElementById("calendarToggle");
  button.setAttribute("aria-expanded", String(expanded));
  button.textContent = `${expanded ? "⌄" : "›"} Kalendář`;
  document.getElementById("settingsCalendar").checked = expanded;
  writePreference("calendar-expanded", expanded);
}
function setAndroidPromo(visible) {
  document.getElementById("androidPromo").hidden = !visible;
  document.getElementById("settingsAndroidPromo").checked = visible;
  writePreference("android-promo", visible);
}
const settingsDialog = document.getElementById("settingsDialog");
document.getElementById("openSettings").addEventListener("click", () => {
  document.getElementById("settingsDarkMode").checked =
    document.body.classList.contains("dark");
  document.getElementById("settingsAutoLogin").checked = readPreference(
    "auto-login",
    true,
  );
  settingsDialog.showModal();
});
document
  .getElementById("closeSettings")
  .addEventListener("click", () => settingsDialog.close());
document
  .getElementById("settingsDarkMode")
  .addEventListener("change", (event) => setDarkMode(event.target.checked));
document
  .getElementById("settingsCalendar")
  .addEventListener("change", (event) =>
    setCalendarExpanded(event.target.checked),
  );
document
  .getElementById("calendarToggle")
  .addEventListener("click", () =>
    setCalendarExpanded(document.getElementById("calendarBody").hidden),
  );
document.getElementById("settingsAutoLogin").checked = readPreference(
  "auto-login",
  true,
);
document
  .getElementById("settingsAutoLogin")
  .addEventListener("change", (event) =>
    writePreference("auto-login", event.target.checked),
  );
document
  .getElementById("settingsAndroidPromo")
  .addEventListener("change", (event) => setAndroidPromo(event.target.checked));
document
  .getElementById("dismissAndroidPromo")
  .addEventListener("click", () => setAndroidPromo(false));
setCalendarExpanded(readPreference("calendar-expanded", true));
setAndroidPromo(readPreference("android-promo", true));
let refreshingDatabase = false;
async function refreshDatabase() {
  const snapshot = unlockedData;
  if (!snapshot?.keyMaterial || refreshingDatabase) return;
  refreshingDatabase = true;
  const button = document.getElementById("refreshDatabase"),
    status = document.getElementById("refreshStatus");
  button.disabled = true;
  status.textContent = "Obnovuji databázi…";
  try {
    const response = await fetch(ENCRYPTED_DATA_URL, {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error("Novou databázi se nepodařilo stáhnout.");
    const updated = await decryptEnvelope(
      await response.json(),
      snapshot.keyMaterial,
    );
    if (unlockedData !== snapshot) return; // A lock or another login took place while downloading.
    unlockedData = updated;
    persistOfflineDatabase(updated.encryptedEnvelope);
    restoreDoneItems(updated.tasks, true);
    render();
    document.getElementById("dbVersion").textContent =
      `DB ${updated.dbVersion}`;
    status.textContent =
      response.headers.get("X-Inzenyri-Offline") === "1"
        ? "Jsi offline · používáš poslední uloženou databázi."
        : "Databáze byla aktualizována.";
  } catch (error) {
    if (unlockedData === snapshot)
      status.textContent =
        error instanceof DOMException && error.name === "OperationError"
          ? "Databázi nelze odemknout stávajícím heslem. Původní data zůstala zachována; pokud se heslo změnilo, zamkni stránku a přihlas se novým."
          : `Obnova selhala; původní data zůstala zachována. ${error.message || ""}`;
  } finally {
    button.disabled = false;
    refreshingDatabase = false;
  }
}
document
  .getElementById("refreshDatabase")
  .addEventListener("click", refreshDatabase);

// A vertical, single-finger gesture beginning at the top; horizontal cards retain their gestures.
let pullStart = null,
  pullDistance = 0;
const pullIndicator = document.getElementById("pullRefreshIndicator");
document.body.classList.add("custom-pull-refresh");
document.addEventListener(
  "touchstart",
  (event) => {
    pullDistance = 0;
    pullStart = null;
    if (
      event.touches.length !== 1 ||
      window.scrollY > 0 ||
      !unlockedData ||
      quizState ||
      refreshingDatabase ||
      document.querySelector("dialog[open]") ||
      event.target.closest("input,textarea,button,a,label,.items")
    )
      return;
    pullStart = { x: event.touches[0].clientX, y: event.touches[0].clientY };
  },
  { passive: true },
);
document.addEventListener(
  "touchmove",
  (event) => {
    if (!pullStart || event.touches.length !== 1) return;
    const dx = event.touches[0].clientX - pullStart.x,
      dy = event.touches[0].clientY - pullStart.y;
    if (Math.abs(dx) > 24 && Math.abs(dx) > Math.abs(dy)) {
      pullStart = null;
      pullIndicator.hidden = true;
      return;
    }
    if (dy <= 0 || window.scrollY > 0) {
      pullStart = null;
      pullIndicator.hidden = true;
      return;
    }
    pullDistance = dy;
    if (dy > 18 && dy > Math.abs(dx) * 1.3) {
      if (event.cancelable) event.preventDefault();
      pullIndicator.hidden = false;
      pullIndicator.textContent =
        dy >= 88 ? "↻ Pusť pro obnovení" : "↓ Táhni pro obnovení";
    }
  },
  { passive: false },
);
document.addEventListener(
  "touchend",
  async () => {
    const refresh = pullStart && pullDistance >= 88;
    pullStart = null;
    pullDistance = 0;
    if (!refresh) {
      pullIndicator.hidden = true;
      return;
    }
    pullIndicator.textContent = "↻ Obnovuji databázi…";
    try {
      await refreshDatabase();
    } finally {
      pullIndicator.hidden = true;
    }
  },
  { passive: true },
);
document.addEventListener("touchcancel", () => {
  pullStart = null;
  pullDistance = 0;
  pullIndicator.hidden = true;
});

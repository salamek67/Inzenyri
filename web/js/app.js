// Shared reader preferences; no password or encryption key is persisted here.
function readPreference(name, fallback = false) {
  try { const value = localStorage.getItem(`inzenyri:${name}`); return value === null ? fallback : value === "true"; }
  catch { return fallback; }
}
function writePreference(name, value) {
  try { localStorage.setItem(`inzenyri:${name}`, String(value)); } catch { /* Storage may be disabled. */ }
}

const ENCRYPTED_DATA_URL = "data.enc.json";
const PAGE_VERSION = "2.6.1";
const FORMAT_NAME = "inzenyri-encrypted-data";
const FORMAT_VERSION = 1;
const DONE_STORAGE_KEY = "inzenyri:done-items:v1";
const AAD = new TextEncoder().encode("inzenyri-data:v1");
const CATEGORIES = {
  test: ["testsList", "testsCount"],
  task: ["tasksList", "tasksCount"],
  event: ["eventsList", "eventsCount"],
};
const CAROUSEL_LISTS = [
  ...Object.values(CATEGORIES).map(([listId]) => listId),
  "completedList",
];

let unlockedData = null;
let passwordManagerAutoLoginAllowed = true;
const doneItems = new Set();
let visibleCalendarMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
let selectedCalendarDay = localDateKey(new Date());

function localDateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function decodeBase64(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9+/]*={0,2}$/.test(value))
    throw new Error("Neplatné kódování dat.");
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function validateEnvelope(value) {
  if (
    !value ||
    value.format !== FORMAT_NAME ||
    value.version !== FORMAT_VERSION
  )
    throw new Error("Nepodporovaný formát šifrovaného souboru.");
  if (
    value.kdf?.name !== "PBKDF2" ||
    value.kdf?.hash !== "SHA-256" ||
    !Number.isInteger(value.kdf?.iterations) ||
    value.kdf.iterations < 100000
  )
    throw new Error("Neplatné parametry odvození klíče.");
  if (value.cipher?.name !== "AES-GCM" || value.cipher?.tagLength !== 128)
    throw new Error("Nepodporovaná šifra.");
  const salt = decodeBase64(value.kdf.salt),
    iv = decodeBase64(value.cipher.iv),
    ciphertext = decodeBase64(value.ciphertext);
  if (salt.length < 16 || iv.length !== 12 || ciphertext.length < 16)
    throw new Error("Šifrovaný soubor je neúplný.");
  return { salt, iv, ciphertext };
}

async function decryptEnvelope(envelope, password) {
  const { salt, iv, ciphertext } = validateEnvelope(envelope);
  const material = typeof password === "string" ? await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveKey"],
  ) : password;
  const key = await crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt,
      iterations: envelope.kdf.iterations,
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv, additionalData: AAD, tagLength: 128 },
    key,
    ciphertext,
  );
  const parsed = JSON.parse(
    new TextDecoder("utf-8", { fatal: true }).decode(plaintext),
  );
  if (!parsed || !Array.isArray(parsed.tasks))
    throw new Error("Rozšifrovaná data mají neplatnou strukturu.");
  for (const item of parsed.tasks)
    for (const quiz of [item?.quiz, ...(Array.isArray(item?.quizzes) ? item.quizzes : [])])
      for (const question of Array.isArray(quiz?.questions) ? quiz.questions : [])
        if (question?.type === "binary") question.type = "choice";
  return {
    keyMaterial: material,
    encryptedEnvelope: envelope,
    tasks: parsed.tasks.filter((item) => item && typeof item === "object"),
    dbVersion:
      typeof envelope.dbVersion === "string" ? envelope.dbVersion : "neuvedena",
  };
}

function parseDate(value) {
  const raw = String(value ?? "").trim();
  let day, month, year;
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(raw),
    czech = /^(\d{1,2})\.(\d{1,2})\.(\d{2}|\d{4})$/.exec(raw);
  if (iso) [, year, month, day] = iso.map(Number);
  else if (czech) {
    [, day, month, year] = czech.map(Number);
    if (year < 100) year += year < 70 ? 2000 : 1900;
  } else return null;
  const parsed = new Date(year, month - 1, day);
  if (
    !day ||
    !month ||
    !year ||
    parsed.getFullYear() !== year ||
    parsed.getMonth() !== month - 1 ||
    parsed.getDate() !== day
  )
    return null;
  parsed.setHours(0, 0, 0, 0);
  return parsed;
}

function itemType(item) {
  if (["task", "test", "event"].includes(item.type)) return item.type;
  const text = `${item.name ?? ""} ${item.task ?? ""}`.toLocaleLowerCase("cs");
  if (/\b(test|písem|zkoušen|prověrk)/.test(text)) return "test";
  if (/\b(akce|výlet|exkur|vysvědčen|divad|koncert)/.test(text)) return "event";
  return "task";
}

function stableItemHash(value) {
  let first = 0xdeadbeef,
    second = 0x41c6ce57;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    first = Math.imul(first ^ code, 2654435761);
    second = Math.imul(second ^ code, 1597334677);
  }
  first = Math.imul(first ^ (first >>> 16), 2246822507) ^
    Math.imul(second ^ (second >>> 13), 3266489909);
  second = Math.imul(second ^ (second >>> 16), 2246822507) ^
    Math.imul(first ^ (first >>> 13), 3266489909);
  return `${(second >>> 0).toString(36)}${(first >>> 0).toString(36)}`;
}

function legacyItemKey(item) {
  const identity = JSON.stringify([
    itemType(item),
    String(item.name ?? ""),
    String(item.date ?? ""),
    String(item.task ?? ""),
  ]);
  return `v1:${stableItemHash(identity)}`;
}

function itemKey(item) {
  const id = typeof item.id === "string" ? item.id.trim() : "";
  return id ? `id:${id}` : legacyItemKey(item);
}

function saveDoneItems() {
  try {
    localStorage.setItem(DONE_STORAGE_KEY, JSON.stringify([...doneItems]));
  } catch {
    /* Soukromý režim nebo nastavení prohlížeče může úložiště zakázat. */
  }
}

function restoreDoneItems(tasks, preserveCurrent = false) {
  const validKeys = new Set(),
    legacyToStableKey = new Map();
  for (const item of tasks) {
    const key = itemKey(item);
    validKeys.add(key);
    if (key !== legacyItemKey(item)) legacyToStableKey.set(legacyItemKey(item), key);
  }
  let saved = [];
  try {
    const parsed = JSON.parse(localStorage.getItem(DONE_STORAGE_KEY) || "[]");
    if (Array.isArray(parsed))
      saved = parsed
        .filter((key) => typeof key === "string")
        .map((key) => legacyToStableKey.get(key) || key)
        .filter((key) => validKeys.has(key));
  } catch {
    saved = [];
  }
  if (preserveCurrent) {
    saved = [...new Set([...saved, ...doneItems].map(key => legacyToStableKey.get(key) || key))]
      .filter(key => validKeys.has(key));
  }
  doneItems.clear();
  saved.forEach((key) => doneItems.add(key));
  saveDoneItems();
}

function renderFormattedText(target, value) {
  RichFormatting.render(target, value);
}

let aiReviewAnchor = null;
function closeAiReviewBubble() {
  document.getElementById("aiReviewBubble").hidden = true;
  aiReviewAnchor?.removeAttribute("aria-describedby");
  aiReviewAnchor = null;
}

// Delegation also handles the carousel's cloned cards.
document.addEventListener("click", (event) => {
  const badge = event.target.closest?.(".ai-review-badge");
  if (!badge || badge === aiReviewAnchor) { closeAiReviewBubble(); return; }
  const name = badge.dataset.aiName;
  if (!["ChatGPT", "Claude", "Gemini", "DeepSeek"].includes(name)) return;
  closeAiReviewBubble();
  const bubble = document.getElementById("aiReviewBubble");
  bubble.textContent = `Úkol byl zkontrolován pomocí ${name}.`;
  bubble.hidden = false;
  const anchor = badge.getBoundingClientRect(), bounds = bubble.getBoundingClientRect();
  const center = anchor.left + anchor.width / 2;
  const left = Math.max(12, Math.min(center - bounds.width / 2, window.innerWidth - bounds.width - 12));
  const below = anchor.top < bounds.height + 20;
  bubble.classList.toggle("is-below", below);
  bubble.style.left = `${left}px`;
  bubble.style.top = `${below ? anchor.bottom + 12 : anchor.top - bounds.height - 12}px`;
  bubble.style.setProperty("--pointer-x", `${Math.max(18, Math.min(center - left, bounds.width - 18))}px`);
  badge.setAttribute("aria-describedby", bubble.id);
  aiReviewAnchor = badge;
});
document.addEventListener("scroll", closeAiReviewBubble, { capture: true, passive: true });
window.addEventListener("resize", closeAiReviewBubble);
document.addEventListener("keydown", event => { if (event.key === "Escape") closeAiReviewBubble(); });
document.addEventListener("focusin", event => { if (aiReviewAnchor && !aiReviewAnchor.contains(event.target)) closeAiReviewBubble(); });
document.addEventListener("visibilitychange", () => { if (document.hidden) closeAiReviewBubble(); });

function createItem(item, index) {
  const key = itemKey(item, index),
    article = document.createElement("article");
  article.className = `item${doneItems.has(key) ? " is-done" : ""}`;
  article.dataset.itemKey = key;
  article.dataset.itemIndex = String(index);
  article.dataset.type = itemType(item);
  const top = document.createElement("div"),
    checkbox = document.createElement("input"),
    main = document.createElement("div"),
    title = document.createElement("h3"),
    date = document.createElement("time");
  top.className = "item-top";
  checkbox.className = "check";
  checkbox.type = "checkbox";
  checkbox.checked = doneItems.has(key);
  checkbox.setAttribute(
    "aria-label",
    checkbox.checked
      ? `Vrátit „${item.name || "položku"}“ mezi aktivní`
      : `Označit „${item.name || "položku"}“ jako hotové`,
  );
  main.className = "item-main";
  title.className = "item-name";
  title.textContent = item.name || "Bez názvu";
  date.className = "date";
  date.textContent = item.date || "Bez data";
  const parsedDate = parseDate(item.date);
  if (parsedDate) date.dateTime = parsedDate.toISOString().slice(0, 10);
  main.append(title, date);
  top.append(checkbox, main);
  article.append(top);
  if (item.task) {
    const text = document.createElement("div");
    text.className = "item-text";
    renderFormattedText(text, item.task);
    article.append(text);
  }
  if (String(item.solution ?? "").trim()) {
    const button = document.createElement("button"),
      solution = document.createElement("div"),
      isPractice = itemType(item) === "test",
      showLabel = isPractice ? "Zobrazit procvičování" : "Zobrazit řešení",
      hideLabel = isPractice ? "Skrýt procvičování" : "Skrýt řešení";
    button.className = "solution-toggle";
    button.type = "button";
    button.textContent = showLabel;
    button.dataset.showLabel = showLabel;
    button.dataset.hideLabel = hideLabel;
    button.setAttribute("aria-expanded", "false");
    solution.className = "solution";
    solution.hidden = true;
    renderFormattedText(solution, item.solution);
    if (itemType(item) === "task" && Array.isArray(item.aiReviews)) {
      const names = { chatgpt: "ChatGPT", claude: "Claude", gemini: "Gemini", deepseek: "DeepSeek" };
      const badges = document.createElement("div");
      badges.className = "ai-reviews";
      badges.setAttribute("aria-label", "AI kontrola označená maintainerem");
      Object.entries(names).forEach(([key, name]) => {
        if (!item.aiReviews.includes(key)) return;
        const badge = document.createElement("button");
        badge.type = "button";
        badge.className = "ai-review-badge";
        badge.dataset.aiName = name;
        badge.setAttribute("aria-label", `Úkol byl zkontrolován pomocí ${name}`);
        const logo = document.createElement("img");
        logo.src = `icons/ai/${key}.svg`;
        logo.alt = "";
        logo.width = 24;
        logo.height = 24;
        logo.setAttribute("aria-hidden", "true");
        badge.append(logo);
        badges.append(badge);
      });
      if (badges.childElementCount) {
        solution.prepend(badges);
      }
    }

    button.addEventListener("click", () => {
      solution.hidden = !solution.hidden;
      button.textContent = solution.hidden ? showLabel : hideLabel;
      button.setAttribute("aria-expanded", String(!solution.hidden));
    });
    article.append(button, solution);
  }
  if (itemType(item) === "test") {
    const quizButton = document.createElement("button");
    quizButton.type = "button";
    quizButton.className = "quiz-start";
    quizButton.textContent = "Vybrat procvičování →";
    quizButton.addEventListener("click", () => openQuiz(index));
    article.append(quizButton);
  }
  checkbox.addEventListener("change", () => {
    if (checkbox.checked) doneItems.add(key); else doneItems.delete(key);
    saveDoneItems();
    const carousel = article.closest(".items");
    if (carousel?.completeItem) {
      checkbox.disabled = true;
      carousel.completeItem(key);
    } else render();
  });
  return article;
}

function render() {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const active = (unlockedData?.tasks || [])
    .map((item, index) => ({ item, index, date: parseDate(item.date) }))
    .filter(({ date }) => !date || date >= today)
    .sort(
      (a, b) =>
        (a.date ?? Infinity) - (b.date ?? Infinity) || a.index - b.index,
    );
  renderCalendar(active);
  for (const [type, [listId, countId]] of Object.entries(CATEGORIES)) {
    const list = document.getElementById(listId),
      section = list.closest(".category"),
      items = active.filter(
        ({ item, index }) =>
          itemType(item) === type && !doneItems.has(itemKey(item, index)),
      );
    section.hidden = items.length === 0;
    document.getElementById(countId).textContent = String(items.length);
    if (!list.restoreItemKey)
      list.restoreItemKey =
        list.querySelector(".item.is-active")?.dataset.itemKey || "";
    list.replaceChildren();
    items.sort(
      (a, b) =>
        Number(doneItems.has(itemKey(a.item, a.index))) -
        Number(doneItems.has(itemKey(b.item, b.index))),
    );
    for (const entry of items) list.append(createItem(entry.item, entry.index));
    list.refreshCarousel?.();
  }
  const completedList = document.getElementById("completedList"),
    completedSection = completedList.closest(".category"),
    completed = active.filter(({ item, index }) =>
      doneItems.has(itemKey(item, index)),
    );
  completedSection.hidden = completed.length === 0;
  document.getElementById("completedCount").textContent = String(
    completed.length,
  );
  if (!completedList.restoreItemKey)
    completedList.restoreItemKey =
      completedList.querySelector(".item.is-active")?.dataset.itemKey || "";
  completedList.replaceChildren();
  for (const entry of completed)
    completedList.append(createItem(entry.item, entry.index));
  completedList.refreshCarousel?.();
}

function renderCalendar(active) {
  const grid = document.getElementById("calendarGrid"),
    agenda = document.getElementById("calendarAgenda"),
    monthLabel = document.getElementById("calendarMonth");
  if (!grid || !agenda || !monthLabel) return;
  const year = visibleCalendarMonth.getFullYear(),
    month = visibleCalendarMonth.getMonth(),
    firstDay = new Date(year, month, 1),
    daysInMonth = new Date(year, month + 1, 0).getDate(),
    offset = (firstDay.getDay() + 6) % 7,
    todayKey = localDateKey(new Date());
  monthLabel.textContent = firstDay.toLocaleDateString("cs-CZ", {
    month: "long",
    year: "numeric",
  });
  grid.replaceChildren();
  const cellCount = Math.ceil((offset + daysInMonth) / 7) * 7;
  for (let index = 0; index < cellCount; index++) {
    const dayNumber = index - offset + 1,
      button = document.createElement("button");
    button.type = "button";
    button.className = "calendar-day";
    if (dayNumber < 1 || dayNumber > daysInMonth) {
      button.disabled = true;
      button.setAttribute("aria-hidden", "true");
      grid.append(button);
      continue;
    }
    const date = new Date(year, month, dayNumber),
      key = localDateKey(date),
      entries = active.filter(({ date: due }) => due && localDateKey(due) === key),
      dots = document.createElement("span");
    button.classList.toggle("is-selected", key === selectedCalendarDay);
    button.classList.toggle("is-today", key === todayKey);
    button.setAttribute(
      "aria-label",
      `${date.toLocaleDateString("cs-CZ", { day: "numeric", month: "long" })}, ${entries.length} položek`,
    );
    button.append(document.createTextNode(String(dayNumber)));
    dots.className = "calendar-dots";
    [...new Set(entries.map(({ item }) => itemType(item)))].slice(0, 3).forEach((type) => {
      const dot = document.createElement("span");
      dot.className = `calendar-dot ${type}`;
      dots.append(dot);
    });
    button.append(dots);
    button.addEventListener("click", () => {
      selectedCalendarDay = key;
      renderCalendar(active);
    });
    grid.append(button);
  }

  agenda.replaceChildren();
  const selectedDate = new Date(`${selectedCalendarDay}T00:00:00`),
    title = document.createElement("h3"),
    list = document.createElement("div");
  title.textContent = selectedDate.toLocaleDateString("cs-CZ", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
  list.className = "calendar-agenda-list";
  const selectedEntries = active.filter(
    ({ date }) => date && localDateKey(date) === selectedCalendarDay,
  );
  if (selectedEntries.length) {
    selectedEntries.forEach(({ item, index }) => list.append(createItem(item, index)));
  } else {
    const empty = document.createElement("p");
    empty.className = "calendar-empty";
    empty.textContent = "Na tento den zatím nic není.";
    list.append(empty);
  }
  agenda.append(title, list);

  const undated = active.filter(({ date }) => !date);
  if (undated.length) {
    const undatedTitle = document.createElement("h3"),
      undatedList = document.createElement("div");
    undatedTitle.textContent = "Bez data";
    undatedList.className = "calendar-agenda-list";
    undated.forEach(({ item, index }) => undatedList.append(createItem(item, index)));
    agenda.append(undatedTitle, undatedList);
  }
}

function initCarousels() {
  for (const listId of CAROUSEL_LISTS) {
    const list = document.getElementById(listId),
      header = list.closest(".category").querySelector(".category-head"),
      controls = document.createElement("div"),
      position = document.createElement("span");
    controls.className = "carousel-controls";
    position.className = "carousel-position";
    const cards = () => [...list.querySelectorAll(".item")];
    const realCards = () => [...list.querySelectorAll(".item:not(.is-clone)")];
    const step = () => {
      const card = cards()[0],
        styles = getComputedStyle(list);
      return (
        (card?.offsetWidth || list.clientWidth) +
        (Number.parseFloat(styles.columnGap || styles.gap) || 0)
      );
    };
    const distanceFromCenter = (card) => {
      const listBox = list.getBoundingClientRect(),
        cardBox = card.getBoundingClientRect();
      return cardBox.left + cardBox.width / 2 - (listBox.left + listBox.width / 2);
    };
    const index = () => {
      const allCards = cards();
      if (!allCards.length) return 0;
      return allCards.reduce(
        (closest, card, cardIndex) => {
          const distance = Math.abs(distanceFromCenter(card));
          return distance < closest.distance
            ? { index: cardIndex, distance }
            : closest;
        },
        { index: 0, distance: Infinity },
      ).index;
    };
    const goTo = (target, behavior = "smooth") => {
      const allCards = cards(),
        card = allCards[Math.max(0, Math.min(allCards.length - 1, target))];
      if (!card) return;
      const left = list.scrollLeft + distanceFromCenter(card);
      if (behavior === "instant") {
        list.style.scrollBehavior = "auto";
        list.scrollLeft = left;
        requestAnimationFrame(() =>
          list.style.removeProperty("scroll-behavior"),
        );
      } else list.scrollTo({ left, behavior });
    };
    const arrow = (direction) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "carousel-arrow";
      button.textContent = direction < 0 ? "‹" : "›";
      button.setAttribute(
        "aria-label",
        direction < 0 ? "Předchozí karta" : "Další karta",
      );
      button.addEventListener("click", () => goTo(index() + direction));
      return button;
    };
    const previous = arrow(-1),
      next = arrow(1);
    controls.append(previous, position, next);
    header.append(controls);
    const update = () => {
      const allCards = cards(),
        current = index(),
        visibleCards = realCards().filter(
          (card) => card.dataset.itemKey !== list.completingKey,
        ),
        total = visibleCards.length,
        currentKey = allCards[current]?.dataset.itemKey,
        activeKey =
          currentKey === list.completingKey ? list.restoreItemKey : currentKey,
        visibleIndex = visibleCards.findIndex(
          (card) => card.dataset.itemKey === activeKey,
        ),
        logical = visibleIndex >= 0 ? visibleIndex : 0;
      position.textContent = total ? `${logical + 1} / ${total}` : "";
      previous.disabled = total < 2;
      next.disabled = total < 2;
      allCards.forEach((card, cardIndex) => {
        const active = cardIndex === current;
        card.classList.toggle("is-active", active);
        if (card.classList.contains("is-clone")) {
          card.inert = !active;
          active
            ? card.removeAttribute("aria-hidden")
            : card.setAttribute("aria-hidden", "true");
        }
      });
    };
    list.refreshCarousel = () => {
      list.classList.add("is-refreshing");
      list.querySelectorAll(".is-clone").forEach((clone) => clone.remove());
      const originals = realCards();
      list.classList.toggle("is-single", originals.length === 1);
      delete list.completingKey;
      if (originals.length > 1) {
        const clone = (card) => {
          const copy = card.cloneNode(true),
            checkbox = copy.querySelector(".check"),
            solutionButton = copy.querySelector(".solution-toggle"),
            solution = copy.querySelector(".solution"),
            quizButton = copy.querySelector(".quiz-start");
          copy.classList.add("is-clone");
          copy.classList.remove("is-active");
          copy.removeAttribute("style");
          copy.setAttribute("aria-hidden", "true");
          copy.inert = true;
          if (checkbox)
            checkbox.addEventListener("change", () => {
              if (checkbox.checked) {
                doneItems.add(copy.dataset.itemKey);
                saveDoneItems();
                checkbox.disabled = true;
                list.completeItem(copy.dataset.itemKey);
              } else {
                doneItems.delete(copy.dataset.itemKey);
                saveDoneItems();
                checkbox.disabled = true;
                list.completeItem(copy.dataset.itemKey);
              }
            });
          if (solutionButton && solution)
            solutionButton.addEventListener("click", () => {
              solution.hidden = !solution.hidden;
              solutionButton.textContent = solution.hidden
                ? solutionButton.dataset.showLabel
                : solutionButton.dataset.hideLabel;
              solutionButton.setAttribute(
                "aria-expanded",
                String(!solution.hidden),
              );
            });
          if (quizButton)
            quizButton.addEventListener("click", () =>
              openQuiz(Number(copy.dataset.itemIndex)),
            );
          return copy;
        };
        const group = () => {
          const fragment = document.createDocumentFragment();
          originals.forEach((card) => fragment.append(clone(card)));
          return fragment;
        };
        list.prepend(group(), group());
        list.append(group(), group());
        list.makeCloneGroup = group;
        const restoredIndex = Math.max(
          0,
          originals.findIndex(
            (card) => card.dataset.itemKey === list.restoreItemKey,
          ),
        );
        delete list.restoreItemKey;
        const restorePosition = () => {
          goTo(originals.length * 2 + restoredIndex, "instant");
          update();
        };
        if (list.clientWidth) restorePosition();
        else requestAnimationFrame(restorePosition);
      } else {
        delete list.restoreItemKey;
        goTo(0, "instant");
        update();
      }
      requestAnimationFrame(() => list.classList.remove("is-refreshing"));
    };
    list.completeItem = (key) => {
      const allCards = cards(),
        current = index(),
        total = realCards().length,
        next =
          total > 1
            ? allCards
                .slice(current + 1)
                .find((card) => card.dataset.itemKey !== key)
            : null,
        nextIndex = next ? allCards.indexOf(next) : current;
      list.completingKey = key;
      list.restoreItemKey = next?.dataset.itemKey || "";
      allCards
        .filter((card) => card.dataset.itemKey === key)
        .forEach((card) => {
          card.classList.add("is-completing");
          card.classList.toggle("is-last-completing", total === 1);
        });
      const count = list.closest(".category").querySelector(".count");
      count.textContent = String(Math.max(0, total - 1));
      update();
      clearTimeout(list.completionTimer);
      let finished = false;
      const finish = () => {
        if (finished) return;
        finished = true;
        list.removeEventListener("scrollend", finish);
        clearTimeout(list.completionTimer);
        render();
      };
      if (next) {
        list.addEventListener("scrollend", finish, { once: true });
        goTo(nextIndex);
        list.completionTimer = setTimeout(finish, 700);
      } else {
        const activeCard = allCards[current];
        activeCard?.addEventListener("transitionend", finish, { once: true });
        list.completionTimer = setTimeout(finish, 350);
      }
    };
    const ensureBuffer = () => {
      const total = realCards().length;
      if (list.completingKey) return;
      if (total < 2 || !list.makeCloneGroup) return;
      const current = index(),
        all = cards().length;
      if (current >= all - total * 2) list.append(list.makeCloneGroup());
      if (current < total * 2) {
        const previousLeft = list.scrollLeft,
          distance = total * step();
        list.style.scrollBehavior = "auto";
        list.prepend(list.makeCloneGroup());
        list.scrollLeft = previousLeft + distance;
        requestAnimationFrame(() =>
          list.style.removeProperty("scroll-behavior"),
        );
      }
      update();
    };
    let dragging = false,
      startX = 0,
      startScroll = 0;
    list.addEventListener("pointerdown", (event) => {
      if (
        event.pointerType === "touch" ||
        event.target.closest("a,button,input,select,textarea,label")
      )
        return;
      dragging = true;
      startX = event.clientX;
      startScroll = list.scrollLeft;
      list.classList.add("is-dragging");
      list.setPointerCapture(event.pointerId);
    });
    list.addEventListener("pointermove", (event) => {
      if (dragging) list.scrollLeft = startScroll - (event.clientX - startX);
    });
    const stop = (event) => {
      if (!dragging) return;
      dragging = false;
      list.classList.remove("is-dragging");
      if (list.hasPointerCapture(event.pointerId))
        list.releasePointerCapture(event.pointerId);
      goTo(index());
    };
    let bufferTimer;
    list.addEventListener("pointerup", stop);
    list.addEventListener("pointercancel", stop);
    list.addEventListener(
      "scroll",
      () => {
        update();
        clearTimeout(bufferTimer);
        bufferTimer = setTimeout(ensureBuffer, 90);
      },
      { passive: true },
    );
    new ResizeObserver(update).observe(list);
    list.refreshCarousel();
  }
}

let quizState = null;
function normalizeWrittenAnswer(value, ignoreCase = false) {
  const normalized = String(value ?? "").trim();
  return ignoreCase ? normalized.toLocaleLowerCase("cs") : normalized;
}

function normalizePunctuationAnswer(value, ignoreCase = false) {
  return normalizeWrittenAnswer(value, ignoreCase)
    .replace(/\s+/g, " ")
    .replace(/\s*,\s*/g, ",");
}

function shuffledQuestions(questions) {
  const shuffled = [...questions];
  for (let index = shuffled.length - 1; index > 0; index--) {
    const swap = Math.floor(Math.random() * (index + 1));
    [shuffled[index], shuffled[swap]] = [shuffled[swap], shuffled[index]];
  }
  return shuffled;
}

function practiceSets(item) {
  return [item?.quiz, ...(Array.isArray(item?.quizzes) ? item.quizzes : [])]
    .filter(quiz => Array.isArray(quiz?.questions) && quiz.questions.some(q => q && typeof q === "object" && !Array.isArray(q)));
}
function quizAction(title, action) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "quiz-primary";
  button.textContent = title;
  button.addEventListener("click", action);
  return button;
}
async function openQuiz(itemIndex, pushHistory = true) {
  const item = unlockedData?.tasks?.[itemIndex];
  if (!item) return;
  const selection = { item, itemIndex, selecting: true };
  quizState = selection;
  document.getElementById("app").hidden = true;
  document.getElementById("quizView").hidden = false;
  if (pushHistory) history.pushState({ quiz: true }, "", "#zkouseni");
  document.getElementById("quizBack").textContent = "← Zpět na přehled";
  const root = document.getElementById("quizContent");
  root.replaceChildren();
  const heading = document.createElement("h1");
  heading.textContent = item.name || "Procvičování";
  root.append(heading);
  const sets = practiceSets(item);
  if (!sets.length) {
    const empty = document.createElement("p");
    empty.textContent = "Pro tento test zatím nejsou připravené žádné kvízy.";
    root.append(empty);
  }
  const identities = await Promise.all(sets.map((quiz, index) => PracticeProgress.identity(itemKey(item), quiz, index, sets)));
  if (quizState !== selection || !unlockedData) return;
  sets.forEach((quiz, index) => {
    const title = String(quiz.title || (index ? `Kvíz ${index + 1}` : "Základní procvičování"));
    const card = document.createElement("section");
    card.className = "practice-set";
    const label = document.createElement("h2"), count = document.createElement("p");
    label.textContent = title;
    const identity = identities[index], saved = PracticeProgress.read(identity), summary = PracticeProgress.summary(saved);
    count.textContent = `${summary.answered}/${identity.size} zodpovězeno`;
    card.append(label, summary.complete ? practiceRing(summary.percent) : count);
    card.append(quizAction(saved && saved.cursor < saved.order.length ? "Pokračovat →" : summary.complete ? "Zobrazit výsledek" : saved ? "Pokračovat →" : "Spustit kvíz →", () => {
      const allQuestions = quiz.questions.filter(q => q && typeof q === "object" && !Array.isArray(q));
      const attempt = PracticeProgress.resume(identity, quiz.shuffle === true);
      quizState = { item, itemIndex, title, identity, attempt, allQuestions, shuffle: quiz.shuffle,
        questions: attempt.order.map(index => allQuestions[index]),
        current: attempt.cursor, score: attempt.order.slice(0, attempt.cursor).filter(index => attempt.answers[index] === 1).length,
        answered: false };
      document.getElementById("quizBack").textContent = "← Vybrat jiný kvíz";
      renderQuizQuestion();
    }));
    root.append(card);
  });
  if (!PracticeProgress.persistent) {
    const notice = document.createElement("p"); notice.textContent = "Prohlížeč nepovoluje ukládání průběhu. Průběh zůstane jen po dobu otevření stránky.";
    root.append(notice);
  }
}
function closeQuiz(useHistory = true) {
  quizState = null;
  document.getElementById("quizContent").replaceChildren();
  document.getElementById("quizView").hidden = true;
  document.getElementById("app").hidden = !unlockedData;
  if (useHistory && location.hash === "#zkouseni") history.back();
}
function renderQuizQuestion() {
  const root = document.getElementById("quizContent"), state = quizState;
  root.replaceChildren();
  if (!state || state.selecting) return;
  state.answered = false;
  if (state.current >= state.questions.length) {
    const title = document.createElement("h1"), score = document.createElement("div");
    title.textContent = "Výsledek procvičování";
    score.className = "quiz-score";
    score.textContent = `${state.score} / ${state.questions.length}`;
    const summary = PracticeProgress.summary(state.attempt);
    const percentage = summary.complete ? practiceRing(summary.percent) : document.createElement("p");
    if (!summary.complete) percentage.textContent = `${summary.answered}/${state.identity.size} zodpovězeno · přeskočené otázky můžeš doplnit při pokračování.`;
    function restart(questions, reset = false) {
      state.attempt.order = questions.map(question => state.allQuestions.indexOf(question));
      state.attempt.cursor = 0;
      if (reset) state.attempt.answers.fill(-1);
      PracticeProgress.write(state.identity, state.attempt);
      state.questions = [...questions]; state.current = 0; state.score = 0;
      renderQuizQuestion();
    }
    root.append(title, score, percentage);
    const failed = state.allQuestions.filter((question, index) => state.attempt.answers[index] !== 1);
    if (failed.length) root.append(quizAction(`Zopakovat chybné a přeskočené (${failed.length})`, () => restart(failed)));
    root.append(quizAction("Zkusit celý kvíz znovu", () => restart(state.shuffle ? shuffledQuestions(state.allQuestions) : state.allQuestions, true)),
      quizAction("Vybrat další kvíz", () => openQuiz(state.itemIndex, false)));
    return;
  }
  const question = state.questions[state.current], progress = document.createElement("p"),
    heading = document.createElement("h1"), form = document.createElement("form"),
    answers = document.createElement("div"), actions = document.createElement("div");
  progress.className = "quiz-progress";
  progress.textContent = `${state.title} · ${state.current + 1} / ${state.questions.length}`;
  heading.className = "quiz-question";
  const bar = document.createElement("progress");
  bar.className = "quiz-progress-bar";
  bar.max = state.questions.length; bar.value = state.current;
  bar.setAttribute("aria-label", `Dokončeno ${state.current} z ${state.questions.length} otázek`);
  root.append(bar);
  renderFormattedText(heading, question.type === "punctuation" ? question.sentence : question.prompt || "Otázka");
  answers.className = "quiz-options"; actions.className = "quiz-actions";
  const next = () => { state.current++; renderQuizQuestion(); };
  function saveOutcome(outcome) {
    state.attempt.answers[state.attempt.order[state.current]] = outcome;
    state.attempt.cursor = state.current + 1;
    PracticeProgress.write(state.identity, state.attempt);
  }
  const skip = quizAction("Přeskočit", () => { if (state.answered) return; saveOutcome(2); next(); });
  const valid = ["choice", "text", "punctuation"].includes(question.type) &&
    (question.type !== "choice" || (Array.isArray(question.options) && question.options.length >= 2 &&
      Number.isInteger(question.answer) && question.answer >= 0 && question.answer < question.options.length));
  if (!valid) { root.append(progress, heading, document.createTextNode("Neplatné zadání otázky."), skip); return; }
  if (question.type === "choice") {
    question.options.forEach((option, index) => {
      const label = document.createElement("label"), radio = document.createElement("input"), text = document.createElement("span");
      label.className = "quiz-option"; radio.type = "radio"; radio.name = "answer"; radio.value = String(index); radio.required = true;
      radio.addEventListener("change", () => { if (!state.answered) form.requestSubmit(); });
      renderFormattedText(text, String(option)); label.append(radio, text); answers.append(label);
    });
  } else {
    const input = document.createElement("textarea"); input.className = "quiz-text"; input.rows = 3;
    input.name = "answerText"; input.required = true; input.autocomplete = "off";
    input.setAttribute("aria-label", "Odpověď"); input.placeholder = "Napiš odpověď…";
    if (question.type === "punctuation") input.value = String(question.sentence || "");
    answers.append(input);
  }
  const submit = document.createElement("button"); submit.type = "submit"; submit.className = "quiz-primary"; submit.textContent = "Vyhodnotit";
  actions.append(submit, skip); form.append(answers, actions); root.append(progress, heading, form);
  form.addEventListener("submit", event => {
    event.preventDefault(); if (state.answered) return; state.answered = true;
    const data = new FormData(form), written = question.type !== "choice",
      value = written ? data.get("answerText") : Number(data.get("answer")),
      expected = Array.isArray(question.answers) ? question.answers : [question.answer],
      normalize = question.type === "punctuation" ? normalizePunctuationAnswer : normalizeWrittenAnswer,
      accepted = written ? expected.some(answer => normalize(value, question.ignoreCase === true) === normalize(answer, question.ignoreCase === true)) : value === question.answer;
    if (accepted) state.score++;
    saveOutcome(accepted ? 1 : 0);
    form.querySelectorAll("input,textarea,button").forEach(input => input.disabled = true);
    const feedback = document.createElement("div"); feedback.className = `quiz-feedback ${accepted ? "correct" : "wrong"}`;
    const result = document.createElement("p"); result.textContent = accepted ? "Správně!" : "Tentokrát to nevyšlo.";
    feedback.append(result);
    if (!accepted) {
      const correct = document.createElement("p");
      renderFormattedText(correct, `Správná odpověď: ${written ? expected.join(" / ") : question.options[question.answer]}`);
      feedback.append(correct);
    }
    if (question.explanation) { const text = document.createElement("p"); renderFormattedText(text, question.explanation); feedback.append(text); }
    root.append(feedback, quizAction(state.current + 1 === state.questions.length ? "Zobrazit výsledek" : "Další otázka", next));
    root.lastElementChild.focus({ preventScroll: true });
  });
}

function showLocked() {
  passwordManagerAutoLoginAllowed = false;
  LoginQrSecret.clear();
  closeLoginQr();
  document.getElementById("calendarGrid").replaceChildren();
  document.getElementById("calendarAgenda").replaceChildren();
  document.getElementById("calendarMonth").textContent = "";
  document.getElementById("refreshStatus").textContent = "";
  document.getElementById("settingsDialog").close();
  closeAiReviewBubble();
  unlockedData = null;
  doneItems.clear();
  for (const [listId, countId] of Object.values(CATEGORIES)) {
    document.getElementById(listId).replaceChildren();
    document.getElementById(countId).textContent = "0";
  }
  document.getElementById("completedList").replaceChildren();
  document.getElementById("completedCount").textContent = "0";
  document.getElementById("pageVersion").textContent = "";
  document.getElementById("dbVersion").textContent = "";
  document.getElementById("quizView").hidden = true;
  document.getElementById("quizContent").replaceChildren();
  quizState = null;
  document.getElementById("app").hidden = true;
  document.getElementById("authView").hidden = false;
  document.getElementById("password").value = "";
  document.getElementById("password").focus();
}

const unlockForm = document.getElementById("unlockForm"),
  passwordInput = document.getElementById("password"),
  unlockButton = document.getElementById("unlockButton"),
  authError = document.getElementById("authError");
unlockForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  authError.textContent = "";
  unlockButton.disabled = true;
  let password = passwordInput.value;
  try {
    if (!window.crypto?.subtle)
      throw new Error(
        "Tento prohlížeč nepodporuje potřebné bezpečné dešifrování.",
      );
    const response = await fetch(ENCRYPTED_DATA_URL, {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok)
      throw new Error(
        response.status === 404
          ? "Šifrovaný datový soubor zatím nebyl publikován."
          : "Šifrovaný soubor se nepodařilo stáhnout.",
      );
    let envelope;
    try {
      envelope = await response.json();
    } catch {
      throw new Error("Šifrovaný datový soubor není platný JSON.");
    }
    unlockedData = await decryptEnvelope(envelope, password);
    LoginQrSecret.remember(password);
    persistOfflineDatabase(envelope);
    restoreDoneItems(unlockedData.tasks);
    render();
    document.getElementById("pageVersion").textContent = `Web ${PAGE_VERSION}`;
    document.getElementById("dbVersion").textContent =
      `DB ${unlockedData.dbVersion}`;
    document.getElementById("authView").hidden = true;
    document.getElementById("app").hidden = false;
    if (response.headers.get("X-Inzenyri-Offline") === "1") document.getElementById("refreshStatus").textContent = "Jsi offline · používáš poslední uloženou databázi.";
    if (window.PasswordCredential && navigator.credentials?.store) {
      try {
        await navigator.credentials.store(new PasswordCredential(unlockForm));
      } catch {
        /* Uložení může uživatel nebo prohlížeč odmítnout. */
      }
    }
  } catch (error) {
    LoginQrSecret.clear();
    unlockedData = null;
    authError.textContent =
      error instanceof DOMException && error.name === "OperationError"
        ? "Nesprávné heslo nebo poškozený šifrovaný soubor."
        : error.message || "Obsah se nepodařilo odemknout.";
    passwordInput.focus();
  } finally {
    passwordInput.value = "";
    password = "";
    unlockButton.disabled = false;
  }
});

// Autologin používá pouze bezpečné automatické doplnění správce hesel.
// Aplikace samotná heslo ani odvozený klíč neukládá.
passwordInput.addEventListener("change", () => {
  if (passwordManagerAutoLoginAllowed && readPreference("auto-login", true) && passwordInput.value && !unlockButton.disabled) unlockForm.requestSubmit();
});
passwordInput.addEventListener("animationstart", (event) => {
  if (
    passwordManagerAutoLoginAllowed && readPreference("auto-login", true) &&
    event.animationName === "password-autofill" &&
    passwordInput.value &&
    !unlockButton.disabled
  )
    unlockForm.requestSubmit();
});

async function tryPasswordManagerLogin() {
  if (!passwordManagerAutoLoginAllowed || !readPreference("auto-login", true)) return;
  if (
    !window.PasswordCredential ||
    !navigator.credentials?.get ||
    location.protocol === "file:"
  )
    return;
  try {
    const credential = await navigator.credentials.get({
      password: true,
      mediation: "optional",
    });
    if (credential?.password && !unlockButton.disabled) {
      passwordInput.value = credential.password;
      unlockForm.requestSubmit();
    }
  } catch {
    /* Správce hesel není dostupný nebo přístup odmítl. */
  }
}

document.getElementById("lockButton").addEventListener("click", showLocked);
document
  .getElementById("quizBack")
  .addEventListener("click", () => {
    if (quizState && !quizState.selecting) openQuiz(quizState.itemIndex, false);
    else closeQuiz();
  });
window.addEventListener("popstate", () => {
  if (quizState && location.hash !== "#zkouseni") closeQuiz(false);
});
const darkModeToggle = document.getElementById("darkModeToggle");
function setDarkMode(enabled) {
  document.body.classList.toggle("dark", enabled);
  try { localStorage.setItem("darkMode", String(enabled)); } catch { /* Storage may be disabled. */ }
  darkModeToggle.textContent = enabled ? "☀" : "☾";
}
darkModeToggle.addEventListener("click", () =>
  setDarkMode(!document.body.classList.contains("dark")),
);
document.getElementById("calendarPrevious").addEventListener("click", () => {
  visibleCalendarMonth = new Date(
    visibleCalendarMonth.getFullYear(),
    visibleCalendarMonth.getMonth() - 1,
    1,
  );
  selectedCalendarDay = localDateKey(visibleCalendarMonth);
  render();
});
document.getElementById("calendarNext").addEventListener("click", () => {
  visibleCalendarMonth = new Date(
    visibleCalendarMonth.getFullYear(),
    visibleCalendarMonth.getMonth() + 1,
    1,
  );
  selectedCalendarDay = localDateKey(visibleCalendarMonth);
  render();
});
try { setDarkMode(localStorage.getItem("darkMode") === "true"); } catch { setDarkMode(false); }
initCarousels();
window.addEventListener("DOMContentLoaded", tryPasswordManagerLogin);

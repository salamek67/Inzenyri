const ENCRYPTED_DATA_URL = "data.enc.json";
const PAGE_VERSION = "2.1.1";
const FORMAT_NAME = "inzenyri-encrypted-data";
const FORMAT_VERSION = 1;
const AAD = new TextEncoder().encode("inzenyri-data:v1");
const CATEGORIES = {
  test: ["testsList", "testsCount"],
  task: ["tasksList", "tasksCount"],
  event: ["eventsList", "eventsCount"],
};

let unlockedData = null;
const doneItems = new Set();

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
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
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
    for (const question of item?.quiz?.questions || [])
      if (question?.type === "binary") question.type = "choice";
  return {
    tasks: parsed.tasks.filter((item) => item && typeof item === "object"),
    dbVersion:
      typeof envelope.dbVersion === "string" ? envelope.dbVersion : "neuvedena",
  };
}

function parseDate(value) {
  const [day, month, year] = String(value ?? "")
    .split(".")
    .map(Number);
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

function itemKey(item, index) {
  return `${index}:${itemType(item)}:${item.date ?? ""}`;
}

function renderFormattedText(target, value) {
  const fragment = document.createDocumentFragment();
  const lines = String(value ?? "")
    .replace(/\\n/g, "\n")
    .split("\n");
  lines.forEach((line, lineIndex) => {
    for (const part of line.split(/(\*\*[^*]+\*\*)/g)) {
      if (part.startsWith("**") && part.endsWith("**")) {
        const strong = document.createElement("strong");
        strong.textContent = part.slice(2, -2);
        fragment.append(strong);
      } else fragment.append(document.createTextNode(part));
    }
    if (lineIndex < lines.length - 1)
      fragment.append(document.createElement("br"));
  });
  target.replaceChildren(fragment);
}

function createItem(item, index) {
  const key = itemKey(item, index),
    article = document.createElement("article");
  article.className = `item${doneItems.has(key) ? " is-done" : ""}`;
  article.dataset.itemKey = key;
  article.dataset.itemIndex = String(index);
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
    `Označit „${item.name || "položku"}“ jako hotové`,
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
    const text = document.createElement("p");
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
    button.addEventListener("click", () => {
      solution.hidden = !solution.hidden;
      button.textContent = solution.hidden ? showLabel : hideLabel;
      button.setAttribute("aria-expanded", String(!solution.hidden));
    });
    article.append(button, solution);
  }
  if (Array.isArray(item.quiz?.questions) && item.quiz.questions.length) {
    const quizButton = document.createElement("button");
    quizButton.type = "button";
    quizButton.className = "quiz-start";
    quizButton.textContent = "Spustit procvičování →";
    quizButton.addEventListener("click", () => openQuiz(index));
    article.append(quizButton);
  }
  checkbox.addEventListener("change", () => {
    checkbox.checked ? doneItems.add(key) : doneItems.delete(key);
    render();
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
  for (const [type, [listId, countId]] of Object.entries(CATEGORIES)) {
    const list = document.getElementById(listId),
      section = list.closest(".category"),
      items = active.filter(({ item }) => itemType(item) === type);
    section.hidden = items.length === 0;
    document.getElementById(countId).textContent = String(items.length);
    list.replaceChildren();
    items.sort(
      (a, b) =>
        Number(doneItems.has(itemKey(a.item, a.index))) -
        Number(doneItems.has(itemKey(b.item, b.index))),
    );
    for (const entry of items) list.append(createItem(entry.item, entry.index));
    list.refreshCarousel?.();
  }
}

function initCarousels() {
  for (const [listId] of Object.values(CATEGORIES)) {
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
        total = realCards().length,
        current = index(),
        logical = total ? current % total : 0;
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
      list.querySelectorAll(".is-clone").forEach((clone) => clone.remove());
      const originals = realCards();
      list.classList.toggle("is-single", originals.length === 1);
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
              checkbox.checked
                ? doneItems.add(copy.dataset.itemKey)
                : doneItems.delete(copy.dataset.itemKey);
              render();
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
        requestAnimationFrame(() => {
          goTo(originals.length * 2, "instant");
          update();
        });
      } else {
        goTo(0, "instant");
        update();
      }
    };
    const ensureBuffer = () => {
      const total = realCards().length;
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
      if (event.pointerType === "touch" || event.target.closest("button,input"))
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
function normalizePunctuationAnswer(value) {
  return String(value ?? "")
    .trim()
    .toLocaleLowerCase("cs")
    .replace(/\s+/g, " ")
    .replace(/\s*,\s*/g, ",");
}

function openQuiz(itemIndex) {
  const item = unlockedData?.tasks?.[itemIndex],
    questions = item?.quiz?.questions;
  if (!Array.isArray(questions) || !questions.length) return;
  quizState = { item, questions, current: 0, score: 0, answered: false };
  document.getElementById("app").hidden = true;
  document.getElementById("quizView").hidden = false;
  history.pushState({ quiz: true }, "", "#zkouseni");
  renderQuizQuestion();
}
function closeQuiz(useHistory = true) {
  quizState = null;
  document.getElementById("quizContent").replaceChildren();
  document.getElementById("quizView").hidden = true;
  document.getElementById("app").hidden = false;
  if (useHistory && location.hash === "#zkouseni") history.back();
}
function renderQuizQuestion() {
  const root = document.getElementById("quizContent"),
    state = quizState;
  root.replaceChildren();
  if (!state) return;
  if (state.current >= state.questions.length) {
    const title = document.createElement("h1"),
      score = document.createElement("div"),
      again = document.createElement("button");
    title.textContent = "Výsledek procvičování";
    score.className = "quiz-score";
    score.textContent = `${state.score} / ${state.questions.length}`;
    again.className = "quiz-primary";
    again.type = "button";
    again.textContent = "Zkusit znovu";
    again.addEventListener("click", () => {
      state.current = 0;
      state.score = 0;
      renderQuizQuestion();
    });
    root.append(title, score, again);
    return;
  }
  const question = state.questions[state.current],
    progress = document.createElement("div"),
    heading = document.createElement("h1"),
    form = document.createElement("form"),
    answers = document.createElement("div"),
    actions = document.createElement("div"),
    submit = document.createElement("button");
  if (
    !question ||
    typeof question !== "object" ||
    !["choice", "text", "punctuation"].includes(question.type)
  ) {
    heading.className = "quiz-question";
    heading.textContent = "Tuto otázku nelze načíst.";
    const skip = document.createElement("button");
    skip.type = "button";
    skip.className = "quiz-primary";
    skip.textContent = "Přeskočit otázku";
    skip.addEventListener("click", () => {
      state.current++;
      renderQuizQuestion();
    });
    root.append(heading, skip);
    return;
  }
  progress.className = "quiz-progress";
  progress.textContent = `${state.item.name || "Test"} · ${state.current + 1} / ${state.questions.length}`;
  heading.className = "quiz-question";
  heading.textContent = String(question.prompt || "Otázka");
  answers.className = "quiz-options";
  if (question.type === "choice") {
    const options = Array.isArray(question.options)
      ? question.options.filter((option) => String(option).trim())
      : [];
    if (
      options.length < 2 ||
      !Number.isInteger(question.answer) ||
      question.answer < 0 ||
      question.answer >= options.length
    ) {
      heading.textContent = "Výběrová otázka má neplatné možnosti.";
      const skip = document.createElement("button");
      skip.type = "button";
      skip.className = "quiz-primary";
      skip.textContent = "Přeskočit otázku";
      skip.addEventListener("click", () => {
        state.current++;
        renderQuizQuestion();
      });
      root.append(progress, heading, skip);
      return;
    }
    options.forEach((option, optionIndex) => {
      const label = document.createElement("label"),
        input = document.createElement("input"),
        text = document.createElement("span");
      label.className = "quiz-option";
      input.type = "radio";
      input.name = "answer";
      input.value = String(optionIndex);
      input.required = true;
      input.addEventListener(
        "change",
        () =>
          queueMicrotask(() => {
            if (!input.disabled) form.requestSubmit();
          }),
        { once: true },
      );
      text.textContent = String(option);
      label.append(input, text);
      answers.append(label);
    });
  } else {
    const input = document.createElement("input");
    input.className = "quiz-text";
    input.name = "answerText";
    input.required = true;
    input.autocomplete = "off";
    if (question.type === "punctuation") {
      const answers = Array.isArray(question.answers)
        ? question.answers.filter((answer) => String(answer).trim())
        : [];
      if (!String(question.sentence ?? "").trim() || !answers.length) {
        heading.textContent = "Otázka na doplnění čárek má neplatná data.";
        const skip = document.createElement("button");
        skip.type = "button";
        skip.className = "quiz-primary";
        skip.textContent = "Přeskočit otázku";
        skip.addEventListener("click", () => {
          state.current++;
          renderQuizQuestion();
        });
        root.append(progress, heading, skip);
        return;
      }
      input.value = String(question.sentence);
      input.setAttribute("aria-label", "Věta k doplnění čárek");
    } else input.placeholder = "Napiš odpověď…";
    answers.append(input);
  }
  actions.className = "quiz-actions";
  submit.className = "quiz-primary";
  submit.type = "submit";
  submit.textContent = "Vyhodnotit";
  actions.append(submit);
  form.append(answers, actions);
  root.append(progress, heading, form);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const isWritten = ["text", "punctuation"].includes(question.type),
      value = isWritten
        ? new FormData(form).get("answerText")
        : Number(new FormData(form).get("answer"));
    const accepted =
      question.type === "punctuation"
        ? (question.answers || [])
            .map(normalizePunctuationAnswer)
            .includes(normalizePunctuationAnswer(value))
        : question.type === "text"
          ? (question.answers || [question.answer])
              .map((answer) => String(answer).trim().toLocaleLowerCase("cs"))
              .includes(String(value).trim().toLocaleLowerCase("cs"))
        : value === Number(question.answer);
    if (accepted) state.score++;
    const feedback = document.createElement("div");
    feedback.className = `quiz-feedback ${accepted ? "correct" : "wrong"}`;
    const correctPunctuation =
      question.type === "punctuation" && question.answers?.[0]
        ? ` Správná varianta: ${question.answers[0]}`
        : "";
    feedback.textContent = accepted
      ? "Správně!"
      : `Špatně.${correctPunctuation}${question.explanation ? ` ${question.explanation}` : ""}`;
    form.querySelectorAll("input").forEach((input) => (input.disabled = true));
    submit.type = "button";
    submit.textContent =
      state.current + 1 === state.questions.length
        ? "Zobrazit výsledek"
        : "Další otázka";
    submit.replaceWith(submit.cloneNode(true));
    const next = actions.querySelector("button");
    next.addEventListener("click", () => {
      state.current++;
      renderQuizQuestion();
    });
    root.insertBefore(feedback, actions.parentElement.nextSibling);
  });
}

function showLocked() {
  unlockedData = null;
  doneItems.clear();
  for (const [listId, countId] of Object.values(CATEGORIES)) {
    document.getElementById(listId).replaceChildren();
    document.getElementById(countId).textContent = "0";
  }
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
    render();
    document.getElementById("pageVersion").textContent = `Web ${PAGE_VERSION}`;
    document.getElementById("dbVersion").textContent =
      `DB ${unlockedData.dbVersion}`;
    document.getElementById("authView").hidden = true;
    document.getElementById("app").hidden = false;
    if (window.PasswordCredential && navigator.credentials?.store) {
      try {
        await navigator.credentials.store(new PasswordCredential(unlockForm));
      } catch {
        /* Uložení může uživatel nebo prohlížeč odmítnout. */
      }
    }
  } catch (error) {
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
  if (passwordInput.value && !unlockButton.disabled) unlockForm.requestSubmit();
});
passwordInput.addEventListener("animationstart", (event) => {
  if (
    event.animationName === "password-autofill" &&
    passwordInput.value &&
    !unlockButton.disabled
  )
    unlockForm.requestSubmit();
});

async function tryPasswordManagerLogin() {
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
  .addEventListener("click", () => closeQuiz());
window.addEventListener("popstate", () => {
  if (quizState && location.hash !== "#zkouseni") closeQuiz(false);
});
const darkModeToggle = document.getElementById("darkModeToggle");
function setDarkMode(enabled) {
  document.body.classList.toggle("dark", enabled);
  localStorage.setItem("darkMode", String(enabled));
  darkModeToggle.textContent = enabled ? "☀" : "☾";
}
darkModeToggle.addEventListener("click", () =>
  setDarkMode(!document.body.classList.contains("dark")),
);
setDarkMode(localStorage.getItem("darkMode") === "true");
initCarousels();
tryPasswordManagerLogin();

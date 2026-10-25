"use strict";
const $ = (id) => document.getElementById(id);
const types = {
  task: ["Úkol", "Úkoly", "▤"],
  test: ["Test", "Testy", "✎"],
  event: ["Akce", "Akce", "◇"],
};
const questionTypes = {
  text: "Textová odpověď",
  choice: "Výběr možností",
  fill: "Doplňování",
  punctuation: "Čárky ve větě",
  flashcard: "Kartička",
  map: "Zeměpisná mapa",
};
const state = {
  tasks: [],
  revision: "",
  filter: "all",
  draft: null,
  id: null,
  dirty: false,
  busy: false,
  imageLoads: 0,
  tab: "edit",
};
let token =
  location.hash.slice(1) ||
  sessionStorage.getItem("inzenyri-editor-token") ||
  "";
if (token) {
  sessionStorage.setItem("inzenyri-editor-token", token);
  history.replaceState(null, "", location.pathname);
}
document.body.classList.toggle(
  "dark",
  localStorage.getItem("inzenyri-editor-theme") === "dark",
);
let toastTimer,
  fieldIndex = 0;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function button(text, action, className = "secondary") {
  const node = el("button", className, text);
  node.type = "button";
  node.addEventListener("click", action);
  return node;
}
function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
function counted(number, one, few, many) {
  return `${number} ${number === 1 ? one : number >= 2 && number <= 4 ? few : many}`;
}
function lines(value) {
  return value
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}
function isoDate(value) {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  const parts = /^(\d{1,2})\.(\d{1,2})\.(\d{2}|\d{4})$/.exec(value || "");
  return parts
    ? `${parts[3].length === 2 ? (Number(parts[3]) >= 69 ? "19" : "20") : ""}${parts[3]}-${parts[2].padStart(2, "0")}-${parts[1].padStart(2, "0")}`
    : "";
}
function dbDate(value) {
  return value ? value.split("-").reverse().join(".") : "";
}
function kind(item) {
  return types[item.type]
    ? item.type
    : /test|písem|zkoušen|prověrk/i.test(`${item.name} ${item.task}`)
      ? "test"
      : /akce|výlet|exkur|divad|koncert/i.test(`${item.name} ${item.task}`)
        ? "event"
        : "task";
}
function quizzes(item) {
  return [...(item.quiz ? [item.quiz] : []), ...(item.quizzes || [])];
}
function setQuizzes(sets) {
  delete state.draft.quiz;
  delete state.draft.quizzes;
  if (sets.length) state.draft.quiz = sets[0];
  if (sets.length > 1) state.draft.quizzes = sets.slice(1);
}
function notify(message, error = false) {
  clearTimeout(toastTimer);
  $("toast").textContent = message;
  $("toast").classList.toggle("error", error);
  $("toast").hidden = false;
  toastTimer = setTimeout(
    () => ($("toast").hidden = true),
    error ? 10000 : 4500,
  );
}
async function api(path, data) {
  const response = await fetch(path, {
    method: data ? "POST" : "GET",
    headers: {
      "X-Editor-Token": token,
      ...(data ? { "Content-Type": "application/json" } : {}),
    },
    ...(data ? { body: JSON.stringify(data) } : {}),
    cache: "no-store",
  });
  const result = await response.json();
  if (result.serverVersion) {
    $("server-version").textContent =
      `Lokální editor · server ${result.serverVersion}`;
  }
  if (!response.ok) {
    const error = new Error(result.error || "Server neodpověděl správně.");
    error.status = response.status;
    throw error;
  }
  return result;
}
function confirmAction(title, message, yes = "Pokračovat") {
  return new Promise((resolve) => {
    const dialog = $("confirm");
    $("confirm-title").textContent = title;
    $("confirm-message").textContent = message;
    $("confirm-yes").textContent = yes;
    dialog.returnValue = "cancel";
    dialog.addEventListener(
      "close",
      () => resolve(dialog.returnValue === "yes"),
      { once: true },
    );
    dialog.showModal();
  });
}
async function canLeave() {
  if (state.imageLoads > 0) {
    notify("Počkej na zpracování obrázku.");
    return false;
  }
  return (
    !state.dirty ||
    confirmAction(
      "Zahodit rozepsané změny?",
      "Položka obsahuje neuložené úpravy. Po opuštění formuláře se zahodí.",
      "Zahodit změny",
    )
  );
}
function markDirty() {
  state.dirty = true;
  updateSaveState();
}
function updateSaveState() {
  $("save-state").textContent = state.busy
    ? "Ukládám…"
    : state.dirty
      ? "Neuložené změny"
      : state.id
        ? "Vše je uložené"
        : "Nová položka";
  $("save-detail").textContent = state.busy
    ? "Šifruji a ukládám databázi."
    : "Uložení zahrnuje i šifrovanou databázi.";
  $("save").disabled = state.busy || state.imageLoads > 0;
  for (const id of ["delete", "duplicate", "new", "reload"])
    $(id).disabled = state.busy || state.imageLoads > 0;
  // Prevent edits during an in-flight save from being overwritten by its response.
  $("edit-panel")
    .querySelectorAll("input,textarea,select,button")
    .forEach((node) => (node.disabled = state.busy || state.imageLoads > 0));
}
function applyData(result) {
  state.tasks = result.tasks;
  state.revision = result.revision;
  $("connection").textContent = `DB ${result.version} · připojeno`;
  $("connection").title = `Verze DB ${result.version}`;
  $("app").hidden = false;
  $("gate").hidden = true;
  renderList();
}
async function load() {
  try {
    applyData(await api("/api/state"));
  } catch (error) {
    $("app").hidden = true;
    $("gate").hidden = false;
    $("gate-title").textContent =
      error.status === 401
        ? "Odemknout databázi"
        : "Editor zatím není připravený";
    $("gate-message").textContent = error.message;
    $("unlock-form").hidden = error.status !== 401;
    $("gate-retry").hidden = error.status === 401;
    $("connection").textContent = "Čekám na databázi";
  }
}
function renderFilters() {
  $("filters").replaceChildren();
  for (const [key, label] of [
    ["all", "Vše"],
    ...Object.entries(types).map(([k, value]) => [k, value[1]]),
  ]) {
    const node = button(
      label,
      () => {
        state.filter = key;
        renderList();
      },
      state.filter === key ? "active" : "",
    );
    node.setAttribute("aria-pressed", String(state.filter === key));
    $("filters").append(node);
  }
}
function renderList() {
  renderFilters();
  $("total").textContent = state.tasks.length;
  const query = $("search").value.trim().toLocaleLowerCase("cs");
  const period = $("period").value;
  const items = state.tasks
    .filter((item) => {
      const date = isoDate(item.date);
      return (
        (state.filter === "all" || kind(item) === state.filter) &&
        (period === "all" ||
          (period === "past"
            ? date && date < today()
            : !date || date >= today())) &&
        (!query ||
          `${item.name || ""} ${item.task || ""} ${item.date || ""}`
            .toLocaleLowerCase("cs")
            .includes(query))
      );
    })
    .sort(
      (a, b) =>
        (isoDate(a.date) || "9999").localeCompare(isoDate(b.date) || "9999") ||
        (a.time || "").localeCompare(b.time || ""),
    );
  $("list-count").textContent = `${items.length} z ${state.tasks.length}`;
  $("items").replaceChildren();
  if (!items.length)
    $("items").append(
      el(
        "p",
        "list-empty",
        query
          ? "Nic jsme nenašli. Zkus jiné hledání."
          : "Tady je zatím volno. Přidej položku nebo změň období.",
      ),
    );
  for (const item of items) {
    const type = kind(item),
      row = button(
        "",
        async () => {
          if (!state.busy && (await canLeave())) openItem(item);
        },
        `item-row${state.id === item.id ? " selected" : ""}`,
      );
    row.append(
      el("span", `badge ${type}`, types[type][0]),
      el("strong", "", item.name || "Bez názvu"),
      el(
        "small",
        "",
        `${item.date || "Bez data"} · ${item.time || "Celý den"}${quizzes(item).length ? ` · ${counted(quizzes(item).length, "sada", "sady", "sad")}` : ""}`,
      ),
    );
    row.setAttribute("aria-current", String(state.id === item.id));
    $("items").append(row);
  }
}
function renderType() {
  $("type-picker").replaceChildren();
  for (const [key, value] of Object.entries(types)) {
    const node = button(
      `${value[2]}  ${value[0]}`,
      () => {
        state.draft.type = key;
        markDirty();
        renderType();
      },
      state.draft.type === key ? "active" : "",
    );
    node.setAttribute("aria-pressed", String(state.draft.type === key));
    $("type-picker").append(node);
  }
  $("task-fields").hidden = state.draft.type !== "task";
  document.querySelector(".practice-panel").hidden =
    state.draft.type !== "test" && !quizzes(state.draft).length;
  $("practice-type-note").hidden = state.draft.type === "test";
}
function openItem(item, duplicate = false) {
  state.draft = item
    ? structuredClone(item)
    : {
        type: state.filter === "all" ? "task" : state.filter,
        name: "",
        date: dbDate(today()),
        task: "",
        solution: "",
      };
  state.id = item && !duplicate ? item.id : null;
  if (duplicate) {
    delete state.draft.id;
    state.draft.name = `${item.name} (kopie)`;
  }
  state.draft.type = kind(state.draft);
  state.dirty = duplicate;
  $("empty").hidden = true;
  $("editor").hidden = false;
  $("editor-caption").textContent = state.id
    ? "UPRAVIT POLOŽKU"
    : duplicate
      ? "KOPIE POLOŽKY"
      : "NOVÁ POLOŽKA";
  $("editor-title").textContent = state.draft.name || "Co přidáme?";
  $("delete").hidden = !state.id;
  $("duplicate").hidden = !state.id;
  for (const [id, key] of [
    ["name", "name"],
    ["description", "task"],
    ["solution", "solution"],
    ["time", "time"],
  ])
    $(id).value = state.draft[key] || "";
  $("date").value = isoDate(state.draft.date);
  $("ai-options").replaceChildren();
  for (const [key, title] of Object.entries({
    chatgpt: "ChatGPT",
    claude: "Claude",
    gemini: "Gemini",
    deepseek: "DeepSeek",
  })) {
    const label = el("label"),
      input = el("input");
    input.type = "checkbox";
    input.checked = state.draft.aiReviews?.includes(key) || false;
    input.addEventListener("change", () => {
      const selected = new Set(state.draft.aiReviews || []);
      input.checked ? selected.add(key) : selected.delete(key);
      state.draft.aiReviews = [...selected];
      markDirty();
    });
    label.append(input, document.createTextNode(title));
    $("ai-options").append(label);
  }
  renderType();
  renderQuizzes();
  showTab("edit");
  updateSaveState();
  renderList();
  $("name").focus({ preventScroll: true });
}
function field(parent, title, value, update, options = {}) {
  const label = el("label", "field"),
    caption = el("span", "", title),
    input = el(options.single ? "input" : "textarea");
  input.id = `dynamic-field-${++fieldIndex}`;
  input.value = value ?? "";
  if (!options.single) input.rows = options.rows || 2;
  if (options.placeholder) input.placeholder = options.placeholder;
  input.addEventListener("input", () => {
    update(input.value);
    markDirty();
  });
  label.append(caption, input);
  parent.append(label);
  return input;
}
function check(parent, title, value, update) {
  const label = el("label", "inline-check"),
    input = el("input");
  input.type = "checkbox";
  input.checked = Boolean(value);
  input.addEventListener("change", () => {
    update(input.checked);
    markDirty();
  });
  label.append(input, document.createTextNode(title));
  parent.append(label);
}
function newQuestion(type = "text") {
  if (type === "map")
    return { type, prompt: "", map: "europe", mode: "regions", targets: [] };
  return {
    type,
    ...(type === "punctuation"
      ? { sentence: "", answers: [""] }
      : { prompt: "" }),
    ...(type === "choice"
      ? { options: ["", ""], answer: 0 }
      : type === "flashcard"
        ? { answer: "" }
        : type !== "punctuation"
          ? { answers: [""] }
          : {}),
    ...(type === "fill" ? { template: "", numeric: false } : {}),
    ...(["text", "fill"].includes(type) ? { ignoreCase: true } : {}),
  };
}
function renderQuizzes() {
  const root = $("quizzes"),
    sets = quizzes(state.draft);
  const collapsed = new Set(
    Array.from(
      root.querySelectorAll(".quiz-set:not([open])"),
      (node) => node.dataset.quizKey,
    ),
  );
  root.replaceChildren();
  if (!sets.length)
    root.append(
      el(
        "div",
        "practice-empty",
        "Zatím bez procvičování. Přidej sadu, až budeš chtít.",
      ),
    );
  sets.forEach((quiz, quizIndex) => {
    const details = el("details", "quiz-set"),
      summary = el("summary"),
      content = el("div", "quiz-set-content");
    details.dataset.quizKey = `${state.id || "new"}:${quiz.id || quizIndex}`;
    details.open = !collapsed.has(details.dataset.quizKey);
    summary.append(
      el("span", "", quiz.title || `Sada ${quizIndex + 1}`),
      el(
        "small",
        "",
        counted(quiz.questions.length, "otázka", "otázky", "otázek"),
      ),
    );
    field(
      content,
      "Název sady",
      quiz.title,
      (value) => {
        quiz.title = value;
        summary.firstChild.textContent = value || `Sada ${quizIndex + 1}`;
      },
      { single: true, placeholder: "Např. Slovíčka · lekce 1" },
    );
    check(
      content,
      "Náhodně promíchat otázky",
      quiz.shuffle,
      (value) => (quiz.shuffle = value),
    );
    const questionsRoot = el("div");
    quiz.questions.forEach((question, questionIndex) => {
      const q = question,
        card = el("div", "question"),
        head = el("div", "question-head"),
        select = el("select");
      if (q.type === "binary") q.type = "choice";
      for (const [value, title] of Object.entries(questionTypes)) {
        const option = el("option", "", title);
        option.value = value;
        select.append(option);
      }
      select.value = q.type;
      select.setAttribute("aria-label", `Typ otázky ${questionIndex + 1}`);
      select.addEventListener("change", async () => {
        const nextType = select.value;
        const hasAnswers = Boolean(
          (typeof q.answer === "string" && q.answer.trim()) ||
            q.template?.trim() ||
            q.options?.some((value) => value.trim()) ||
            q.answers?.some((value) => value.trim()),
        );
        if (
          !hasAnswers ||
          (await confirmAction(
            "Změnit typ otázky?",
            "Zadání se zachová, pole odpovědí se připraví pro nový typ.",
            "Změnit typ",
          ))
        ) {
          const next = newQuestion(nextType);
          next[nextType === "punctuation" ? "sentence" : "prompt"] =
            q.prompt || q.sentence || "";
          if (q.explanation) next.explanation = q.explanation;
          if (q.image) next.image = q.image;
          quiz.questions[questionIndex] = next;
          markDirty();
          renderQuizzes();
        } else select.value = q.type;
      });
      head.append(
        el("strong", "", `${questionIndex + 1}.`),
        select,
        button(
          "Odstranit",
          async () => {
            if (
              await confirmAction(
                "Odstranit otázku?",
                "Po uložení už otázka nebude součástí této sady.",
                "Odstranit",
              )
            ) {
              quiz.questions.splice(questionIndex, 1);
              markDirty();
              renderQuizzes();
            }
          },
          "",
        ),
      );
      card.append(head);
      const imageLabel = el("label", "field"),
        upload = el("input");
      upload.type = "file";
      upload.accept = "image/png,image/jpeg";
      imageLabel.append(
        el("span", "", "Obrázek k otázce · PNG/JPEG (volitelné)"),
        upload,
      );
      card.append(imageLabel);
      upload.addEventListener("change", async () => {
        const draft = state.draft;
        state.imageLoads++;
        updateSaveState();
        upload.disabled = true;
        try {
          const image = await QuizImage.importFile(upload.files[0]);
          if (state.draft !== draft || !quiz.questions.includes(q)) return;
          q.image = image;
          markDirty();
          renderQuizzes();
        } catch (error) {
          notify(error.message, true);
        } finally {
          state.imageLoads--;
          updateSaveState();
          upload.disabled = false;
          upload.value = "";
        }
      });
      if (q.image) {
        const preview = QuizImage.create(q.image);
        if (preview) card.append(preview);
        field(
          card,
          "Popis obrázku pro čtečku obrazovky",
          q.image.alt,
          (value) => (q.image.alt = value),
          { single: true },
        ).maxLength = 1000;
        card.append(
          button("Odstranit obrázek", () => {
            delete q.image;
            markDirty();
            renderQuizzes();
          }),
        );
      }
      field(
        card,
        q.type === "flashcard"
          ? "Přední strana"
          : q.type === "punctuation"
            ? "Věta bez čárek"
            : "Zadání otázky",
        q.type === "punctuation" ? q.sentence : q.prompt,
        (value) =>
          (q[q.type === "punctuation" ? "sentence" : "prompt"] = value),
      );
      if (q.type === "map") {
        const mapRoot = el("div"),
          selection = el("p", "field-note");
        function drawMap() {
          const map = MapQuiz.create(q, {
            editor: true,
            onChange: (answer) => {
              if (q.mode === "regions") q.targets = answer.targets;
              else q.target = answer.target;
              markDirty();
              refreshSelection();
            },
          });
          mapRoot.replaceChildren(
            map ? map.element : el("p", "error", "Zvol platnou mapu a režim."),
          );
          refreshSelection();
        }
        function refreshSelection() {
          if (q.mode === "pin")
            selection.textContent = q.target
              ? `Cíl: ${q.target[1].toFixed(3)}° š., ${q.target[0].toFixed(3)}° d.`
              : "Klikni do mapy pro zadání správného místa.";
          else
            MapQuiz.countries()
              .then((values) => {
                selection.textContent = `Správné oblasti: ${
                  values
                    .filter((c) => q.targets?.includes(c.id))
                    .map((c) => `${MapQuiz.name(c)} (${c.id})`)
                    .join(", ") || "zatím žádné — vyber je na mapě"
                }`;
              })
              .catch(
                () => (selection.textContent = "Podklad se nepodařilo načíst."),
              );
        }
        function selectField(title, values, current, onChange) {
          const label = el("label", "field"),
            select = el("select");
          for (const [id, name] of Object.entries(values)) {
            const option = el("option", "", name);
            option.value = id;
            select.append(option);
          }
          select.value = current;
          select.addEventListener("change", () => {
            onChange(select.value);
            markDirty();
            drawMap();
          });
          label.append(el("span", "", title), select);
          card.append(label);
        }
        selectField(
          "Mapový podklad",
          Object.fromEntries(
            Object.entries(MapQuiz.views).map(([id, value]) => [
              id,
              value.name,
            ]),
          ),
          q.map,
          (value) => {
            q.map = value;
            q.targets = [];
            q.target = null;
          },
        );
        selectField(
          "Režim",
          { regions: "Vyznačení oblastí", pin: "Špendlík do mapy" },
          q.mode,
          (value) => {
            q.mode = value;
            delete q.targets;
            delete q.target;
            if (value === "regions") q.targets = [];
            else {
              q.target = null;
              q.toleranceKm = 100;
            }
            renderQuizzes();
          },
        );
        if (q.mode === "pin") {
          const tolerance = field(
            card,
            "Tolerance vzdálenosti v km (1–2000)",
            q.toleranceKm,
            (value) => (q.toleranceKm = Number(value)),
            { single: true },
          );
          tolerance.type = "number";
          tolerance.min = "1";
          tolerance.max = "2000";
          tolerance.step = "any";
        }
        card.append(mapRoot, selection);
        drawMap();
      } else if (q.type === "flashcard")
        field(card, "Zadní strana", q.answer, (value) => (q.answer = value));
      else if (q.type === "choice") {
        const answerLabel = el("label", "field"),
          answerSelect = el("select");
        function renderOptions() {
          answerSelect.replaceChildren();
          (q.options || []).forEach((option, index) => {
            const node = el(
              "option",
              "",
              `${index + 1}. ${option || "Prázdná možnost"}`,
            );
            node.value = index;
            answerSelect.append(node);
          });
          q.answer = Math.min(
            Math.max(Number(q.answer) || 0, 0),
            Math.max(q.options.length - 1, 0),
          );
          answerSelect.value = q.answer;
        }
        field(
          card,
          "Možnosti · každá na nový řádek",
          (q.options || []).join("\n"),
          (value) => {
            q.options = lines(value);
            renderOptions();
          },
          { rows: 3 },
        );
        renderOptions();
        answerSelect.addEventListener("change", () => {
          q.answer = Number(answerSelect.value);
          markDirty();
        });
        answerLabel.append(el("span", "", "Správná možnost"), answerSelect);
        card.append(answerLabel);
      } else {
        if (q.type === "fill")
          field(
            card,
            "Text s políčky {{1}}, {{2}}, …",
            q.template,
            (value) => (q.template = value),
            { placeholder: "{{1}} H₂ + {{2}} O₂ → {{3}} H₂O" },
          );
        const accepted = Array.isArray(q.answers)
          ? q.answers
          : typeof q.answer === "string"
            ? [q.answer]
            : [];
        field(
          card,
          q.type === "fill"
            ? "Hodnoty políček v pořadí · každá na nový řádek"
            : "Správné odpovědi · každá alternativa na nový řádek",
          accepted.join("\n"),
          (value) => (q.answers = lines(value)),
          { rows: 3 },
        );
        if (["text", "fill"].includes(q.type))
          check(
            card,
            "Ignorovat velikost písmen",
            q.ignoreCase,
            (value) => (q.ignoreCase = value),
          );
        if (q.type === "fill")
          check(
            card,
            "Pouze celá nezáporná čísla",
            q.numeric,
            (value) => (q.numeric = value),
          );
      }
      field(
        card,
        "Vysvětlení · volitelné",
        q.explanation,
        (value) => (q.explanation = value),
      );
      questionsRoot.append(card);
    });
    content.append(questionsRoot);
    const actions = el("div", "quiz-actions");
    actions.append(
      button("＋ Přidat otázku", () => {
        quiz.questions.push(newQuestion(quiz.questions.at(-1)?.type || "text"));
        markDirty();
        renderQuizzes();
      }),
      button(
        "Odstranit sadu",
        async () => {
          if (
            await confirmAction(
              "Odstranit celou sadu?",
              `Sada „${quiz.title || "Bez názvu"}“ a všechny její otázky budou odstraněny při uložení.`,
              "Odstranit sadu",
            )
          ) {
            sets.splice(quizIndex, 1);
            setQuizzes(sets);
            markDirty();
            renderQuizzes();
          }
        },
        "danger",
      ),
    );
    content.append(actions);
    details.append(summary, content);
    root.append(details);
  });
}
function markdown(target, text) {
  target.innerHTML = DOMPurify.sanitize(marked.parse(String(text || "")), {
    FORBID_TAGS: ["input", "form", "button", "iframe"],
    FORBID_ATTR: ["style", "id", "name"],
  });
}
function renderPreview() {
  const item = state.draft,
    root = $("preview");
  root.replaceChildren();
  root.append(
    el("span", `badge ${item.type}`, types[item.type][0]),
    el("h2", "", item.name || "Bez názvu"),
    el(
      "p",
      "field-note",
      `${item.date || "Bez data"} · ${item.time || "Celý den"}`,
    ),
  );
  const text = el("div");
  markdown(text, item.task);
  root.append(text);
  if (item.type === "task" && item.solution) {
    const solution = el("div");
    markdown(solution, item.solution);
    root.append(el("hr"), el("h3", "", "Řešení"), solution);
  }
  for (const quiz of quizzes(item)) {
    root.append(el("hr"), el("h3", "", quiz.title || "Procvičování"));
    quiz.questions.forEach((q, index) => {
      const card = el("div", "preview-question"),
        heading = el("div");
      card.append(
        el("small", "", `${index + 1}. ${questionTypes[q.type] || q.type}`),
      );
      markdown(heading, q.prompt || q.sentence);
      card.append(heading);
      const image = QuizImage.create(q.image);
      if (image) card.append(image);
      if (q.template) {
        const template = el("p");
        template.textContent = q.template;
        card.append(template);
      }
      if (q.type === "map") {
        const map = MapQuiz.create(q, { editor: true, readOnly: true });
        if (map) card.append(map.element);
      } else if (q.options) {
        const list = el("ol");
        q.options.forEach((option, i) =>
          list.append(el("li", "", `${i === q.answer ? "✓ " : ""}${option}`)),
        );
        card.append(list);
      } else {
        const answer = el("div", "answer");
        markdown(
          answer,
          q.type === "flashcard"
            ? q.answer
            : Array.isArray(q.answers)
              ? q.answers.join(" / ")
              : q.answer || "",
        );
        card.append(answer);
      }
      if (q.explanation) {
        const explanation = el("div");
        markdown(explanation, q.explanation);
        card.append(explanation);
      }
      root.append(card);
    });
  }
}
function showTab(tab) {
  state.tab = tab;
  $("edit-panel").hidden = tab !== "edit";
  $("preview").hidden = tab !== "preview";
  $("edit-tab").setAttribute("aria-selected", String(tab === "edit"));
  $("preview-tab").setAttribute("aria-selected", String(tab === "preview"));
  if (tab === "preview") renderPreview();
}
async function save(event) {
  event?.preventDefault();
  if (state.imageLoads > 0) {
    notify("Počkej na zpracování obrázku.");
    return;
  }
  if (!state.draft || state.busy) return;
  if (!$("item-form").checkValidity()) {
    showTab("edit");
    $("item-form").reportValidity();
    return;
  }
  state.busy = true;
  updateSaveState();
  try {
    const adding = state.id === null;
    const item = structuredClone(state.draft);
    if (!item.time) delete item.time;
    const result = await api("/api/items", {
      revision: state.revision,
      id: state.id,
      item,
    });
    applyData(result);
    state.busy = false;
    if (adding) {
      state.draft = null;
      state.id = null;
      state.dirty = false;
      $("editor").hidden = true;
      $("empty").hidden = false;
      renderList();
      $("empty-new").focus({ preventScroll: true });
    } else {
      openItem(result.tasks.find((entry) => entry.id === result.saved_id));
    }
    notify("Uloženo. Soukromá i šifrovaná databáze jsou aktuální.");
  } catch (error) {
    notify(error.message, true);
    $("connection").textContent =
      error.status === 409
        ? "Změna databáze · načti aktuální data"
        : "Uložení se nezdařilo";
  } finally {
    state.busy = false;
    updateSaveState();
  }
}

$("theme").addEventListener("click", () => {
  document.body.classList.toggle("dark");
  localStorage.setItem(
    "inzenyri-editor-theme",
    document.body.classList.contains("dark") ? "dark" : "light",
  );
});
for (const id of ["new", "empty-new"])
  $(id).addEventListener("click", async () => {
    if (!state.busy && (await canLeave())) openItem();
  });
$("search").addEventListener("input", renderList);
$("period").addEventListener("change", renderList);
$("reload").addEventListener("click", async () => {
  if (state.busy || !(await canLeave())) return;
  try {
    const result = await api("/api/state");
    applyData(result);
    state.draft = null;
    state.id = null;
    state.dirty = false;
    $("editor").hidden = true;
    $("empty").hidden = false;
    renderList();
    notify("Načtena aktuální databáze.");
  } catch (error) {
    notify(error.message, true);
  }
});
$("duplicate").addEventListener("click", async () => {
  if (!state.busy && (await canLeave()))
    openItem(
      state.tasks.find((item) => item.id === state.id),
      true,
    );
});
$("delete").addEventListener("click", async () => {
  if (
    state.busy ||
    !state.id ||
    !(await confirmAction(
      "Smazat položku?",
      `„${state.draft.name || "Bez názvu"}“ se odstraní včetně jejího procvičování.`,
      "Smazat položku",
    ))
  )
    return;
  state.busy = true;
  updateSaveState();
  try {
    applyData(
      await api("/api/delete", { id: state.id, revision: state.revision }),
    );
    state.draft = null;
    state.id = null;
    state.dirty = false;
    $("editor").hidden = true;
    $("empty").hidden = false;
    renderList();
    notify("Položka byla smazaná.");
  } catch (error) {
    notify(error.message, true);
  } finally {
    state.busy = false;
    updateSaveState();
  }
});
for (const [id, key] of [
  ["name", "name"],
  ["description", "task"],
  ["solution", "solution"],
  ["time", "time"],
  ["date", "date"],
]) {
  $(id).addEventListener("input", () => {
    state.draft[key] = id === "date" ? dbDate($(id).value) : $(id).value;
    markDirty();
    if (id === "name")
      $("editor-title").textContent = $(id).value || "Co přidáme?";
  });
}
$("all-day").addEventListener("click", () => {
  $("time").value = "";
  delete state.draft.time;
  markDirty();
});
$("add-quiz").addEventListener("click", () => {
  const sets = quizzes(state.draft);
  sets.push({
    id: crypto.randomUUID(),
    title: "",
    shuffle: false,
    questions: [newQuestion()],
  });
  setQuizzes(sets);
  markDirty();
  renderQuizzes();
});
$("edit-tab").addEventListener("click", () => showTab("edit"));
$("preview-tab").addEventListener("click", () => showTab("preview"));
$("item-form").addEventListener("submit", save);
$("unlock-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = event.submitter;
  submit.disabled = true;
  $("gate-error").textContent = "";
  try {
    applyData(await api("/api/unlock", { password: $("password").value }));
    $("password").value = "";
  } catch (error) {
    $("gate-error").textContent = error.message;
  } finally {
    submit.disabled = false;
  }
});
$("gate-retry").addEventListener("click", load);
document.addEventListener("keydown", (event) => {
  if (!(event.ctrlKey || event.metaKey)) return;
  if (event.key.toLowerCase() === "s") {
    event.preventDefault();
    if (!$("app").hidden && state.draft && !$("confirm").open) save();
  }
  if (event.key.toLowerCase() === "k") {
    event.preventDefault();
    if (!$("app").hidden) $("search").focus();
  }
});
window.addEventListener("beforeunload", (event) => {
  if (state.dirty || state.busy || state.imageLoads > 0) {
    event.preventDefault();
    event.returnValue = "";
  }
});
load();

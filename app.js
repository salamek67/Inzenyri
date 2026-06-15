function parseDate(value) {
    const parts = String(value).split(".");
    if (parts.length !== 3) return null;

    const day = Number(parts[0]);
    const month = Number(parts[1]) - 1;
    const year = Number(parts[2]);
    const date = new Date(year, month, day);

    if (
        Number.isNaN(date.getTime()) ||
        date.getFullYear() !== year ||
        date.getMonth() !== month ||
        date.getDate() !== day
    ) {
        return null;
    }

    date.setHours(0, 0, 0, 0);
    return date;
}

function todayStart() {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return today;
}

function safeData() {
    return Array.isArray(window.data) ? window.data : [];
}

function appendTextNode(target, value) {
    if (!value) return;
    target.appendChild(document.createTextNode(value));
}

function renderFormattedText(target, value, fallbackText = "") {
    target.replaceChildren();

    const source = String(value ?? "");
    if (!source) {
        if (fallbackText) {
            target.textContent = fallbackText;
        }
        return;
    }

    const fragment = document.createDocumentFragment();
    const stack = [];
    let current = fragment;
    let lastIndex = 0;
    const tokenPattern = /<br\s*\/?>|<b\s*>|<\/b\s*>|<b\s*\/\s*>/gi;
    let match;

    while ((match = tokenPattern.exec(source)) !== null) {
        appendTextNode(current, source.slice(lastIndex, match.index));

        const token = match[0].toLowerCase();
        if (token.startsWith("<br")) {
            current.appendChild(document.createElement("br"));
        } else if (token === "<b>") {
            const strong = document.createElement("strong");
            current.appendChild(strong);
            stack.push(current);
            current = strong;
        } else if (stack.length) {
            current = stack.pop();
        }

        lastIndex = tokenPattern.lastIndex;
    }

    appendTextNode(current, source.slice(lastIndex));

    target.appendChild(fragment);
}

function visibleTasks() {
    const today = todayStart();

    return safeData()
        .map((item, index) => ({ item, index, date: parseDate(item.date) }))
        .filter(({ item, date }) => item && date && date >= today)
        .sort((a, b) => a.date - b.date || a.index - b.index);
}

function createTaskBox(entry) {
    const box = document.createElement("article");
    box.className = "box";

    const heading = document.createElement("h2");
    heading.textContent = entry.item.name || "Bez názvu";

    const date = document.createElement("div");
    date.className = "date";
    date.textContent = entry.item.date;

    const task = document.createElement("div");
    task.className = "task";
    renderFormattedText(task, entry.item.task);

    const actions = document.createElement("div");
    actions.className = "actions";

    const button = document.createElement("button");
    button.type = "button";
    button.className = "toggle-solution";
    button.textContent = "Zobrazit řešení";
    button.setAttribute("aria-expanded", "false");

    const solution = document.createElement("div");
    solution.className = "solution";
    solution.hidden = true;

    const solutionText = document.createElement("p");
    renderFormattedText(solutionText, entry.item.solution, "Řešení není vyplněné.");

    solution.appendChild(solutionText);
    actions.appendChild(button);
    box.appendChild(heading);
    box.appendChild(date);
    box.appendChild(task);
    box.appendChild(actions);
    box.appendChild(solution);

    return box;
}

function renderTasks() {
    const list = document.getElementById("taskList");
    if (!list) return;

    list.innerHTML = "";

    const tasks = visibleTasks();

    if (!tasks.length) {
        const empty = document.createElement("div");
        empty.className = "empty";
        empty.textContent = "Žádné aktuální úkoly.";
        list.appendChild(empty);
        return;
    }

    for (const entry of tasks) {
        list.appendChild(createTaskBox(entry));
    }
}

document.addEventListener("click", (event) => {
    const button = event.target.closest(".toggle-solution");
    if (!button) return;

    const box = button.closest(".box");
    if (!box) return;

    const solution = box.querySelector(".solution");
    if (!solution) return;

    const isHidden = solution.hidden;
    solution.hidden = !isHidden;
    button.textContent = isHidden ? "Skrýt řešení" : "Zobrazit řešení";
    button.setAttribute("aria-expanded", String(isHidden));
});

renderTasks();

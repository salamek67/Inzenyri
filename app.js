const DAY_LABELS = {
    1: "Pondělí",
    2: "Úterý",
    3: "Středa",
    4: "Čtvrtek",
    5: "Pátek",
    6: "Sobota",
    7: "Neděle",
};

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

function isoWeekParity(date) {
    const current = new Date(date);
    current.setHours(0, 0, 0, 0);

    const temp = new Date(current);
    temp.setDate(temp.getDate() + 4 - (temp.getDay() || 7));
    const yearStart = new Date(temp.getFullYear(), 0, 1);
    const weekNumber = Math.ceil((((temp - yearStart) / 86400000) + 1) / 7);
    return weekNumber % 2;
}

function startOfWeek(date) {
    const result = new Date(date);
    result.setHours(0, 0, 0, 0);
    const day = result.getDay() || 7;
    result.setDate(result.getDate() - day + 1);
    return result;
}

function addDays(date, days) {
    const result = new Date(date);
    result.setDate(result.getDate() + days);
    return result;
}

function weekRangeLabel(weekOffset) {
    const base = startOfWeek(todayStart());
    const start = addDays(base, weekOffset * 7);
    const end = addDays(start, 6);
    const formatter = new Intl.DateTimeFormat("cs-CZ", {
        day: "numeric",
        month: "numeric",
    });

    const title = weekOffset === -1 ? "Minulý týden" : weekOffset === 1 ? "Příští týden" : "Tento týden";
    return `${title} (${formatter.format(start)} – ${formatter.format(end)})`;
}

function weekParityLabel(parity) {
    return parity === 1 ? "lichý" : "sudý";
}

function safeStore() {
    const raw = window.data;

    if (Array.isArray(raw)) {
        return { tasks: raw, schedule: [] };
    }

    if (raw && typeof raw === "object") {
        return {
            tasks: Array.isArray(raw.tasks) ? raw.tasks : [],
            schedule: Array.isArray(raw.schedule) ? raw.schedule : [],
        };
    }

    return { tasks: [], schedule: [] };
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

    return safeStore()
        .tasks
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

function dayLabel(value) {
    const day = Number(value);
    return DAY_LABELS[day] || String(value || "");
}

function scheduleRank(value) {
    const day = Number(value);
    if (Number.isInteger(day) && day >= 1 && day <= 7) {
        return day;
    }

    const normalized = String(value || "").trim().toLowerCase();
    const mapping = {
        pondeli: 1,
        pondělí: 1,
        utery: 2,
        úterý: 2,
        streda: 3,
        středa: 3,
        ctvrtek: 4,
        čtvrtek: 4,
        patek: 5,
        pátek: 5,
        sobota: 6,
        nedele: 7,
        neděle: 7,
    };

    return mapping[normalized] || 99;
}

function scheduleWeekType(item) {
    const rawType = String(item?.weekType || item?.week_type || "").trim().toLowerCase();
    if (rawType) {
        if (["obě", "obe", "both", "all", "any", "0"].includes(rawType)) return "both";
        if (["lichý", "lichy", "odd", "1", "l"].includes(rawType)) return "odd";
        if (["sudý", "sudy", "even", "2", "s"].includes(rawType)) return "even";
    }

    const legacyWeek = Number(item?.week ?? item?.weekOffset ?? 0);
    if (legacyWeek === -1) return "odd";
    if (legacyWeek === 1) return "even";
    return "both";
}

function normalizeGroupToken(value) {
    return String(value || "")
        .trim()
        .toLowerCase()
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/\s+/g, "");
}

let selectedWeekOffset = null;

function visibleSchedule() {
    const viewAj = document.getElementById("viewAj");
    const viewTv = document.getElementById("viewTv");
    const viewJazyky = document.getElementById("viewJazyky");
    const selectedAj = normalizeGroupToken(viewAj?.value || "all");
    const selectedTv = normalizeGroupToken(viewTv?.value || "all");
    const selectedJazyky = normalizeGroupToken(viewJazyky?.value || "all");
    const targetParity = selectedWeekOffset === null
        ? null
        : isoWeekParity(addDays(todayStart(), selectedWeekOffset * 7));

    const groupAllowed = (groupKey) => {
        if (groupKey === "cela") return true;
        if (groupKey === "aj1" || groupKey === "aj2") {
            return selectedAj === "all" || groupKey === selectedAj;
        }
        if (groupKey === "tvch" || groupKey === "tvd") {
            return selectedTv === "all" || groupKey === selectedTv;
        }
        if (groupKey === "sj" || groupKey === "nj" || groupKey === "fj_t") {
            return selectedJazyky === "all" || groupKey === selectedJazyky;
        }
        return true;
    };

    return safeStore()
        .schedule
        .map((item, index) => ({
            item,
            index,
            day: scheduleRank(item.day),
            hour: Number(item.hour) || 0,
            group: String(item.group || "Celá").trim(),
            groupKey: normalizeGroupToken(item.group || "Celá"),
            weekType: scheduleWeekType(item),
        }))
        .filter(({ item, group, groupKey, weekType }) => {
            if (!item) return false;
            const weekMatches = targetParity === null || weekType === "both" || weekType === (targetParity ? "odd" : "even");
            const groupMatches = groupAllowed(groupKey);
            return weekMatches && groupMatches;
        })
        .sort((a, b) => a.day - b.day || a.hour - b.hour || a.index - b.index);
}

function allScheduleEntries() {
    return safeStore()
        .schedule
        .map((item, index) => ({
            item,
            index,
            day: scheduleRank(item.day),
            hour: Number(item.hour) || 0,
            group: String(item.group || "Celá").trim(),
            groupKey: normalizeGroupToken(item.group || "Celá"),
            weekType: scheduleWeekType(item),
        }))
        .sort((a, b) => a.day - b.day || a.hour - b.hour || a.index - b.index);
}

function scheduleCellContent(entry) {
    const wrapper = document.createElement("div");
    const type = entry.item.type || "";
    wrapper.className = "schedule-entry" + (type ? " is-" + type : "");

    const rawGroup = String(entry.item.group || "").trim();
    const showGroup = normalizeGroupToken(rawGroup) !== "cela" && rawGroup !== "";

    const subject = document.createElement("strong");
    subject.className = "schedule-entry-subject";
    subject.textContent = entry.item.subject || "Bez předmětu";

    const header = document.createElement("div");
    header.className = "schedule-entry-header";
    header.appendChild(subject);
    wrapper.appendChild(header);

    const teacher = document.createElement("span");
    teacher.className = "schedule-entry-teacher";
    teacher.textContent = entry.item.teacher || "";
    wrapper.appendChild(teacher);

    const classroom = document.createElement("span");
    classroom.className = "schedule-entry-classroom";
    classroom.textContent = entry.item.classroom ? `(${entry.item.classroom})` : "";
    wrapper.appendChild(classroom);

    if (showGroup) {
        const group = document.createElement("div");
        group.className = "schedule-entry-group";
        group.textContent = rawGroup;
        wrapper.appendChild(group);
    }

    return wrapper;
}

function fitScheduleEntries(root) {
    const entries = root.querySelectorAll(".schedule-entry");
    for (const entry of entries) {
        entry.classList.remove("is-tight");

        if (entry.scrollWidth > entry.clientWidth || entry.scrollHeight > entry.clientHeight) {
            entry.classList.add("is-tight");
        }
    }
}

function createScheduleTable(entries, allEntries = entries) {
    const days = [1, 2, 3, 4, 5];
    const hours = [...new Set(allEntries.map((entry) => entry.hour).filter((hour) => Number.isFinite(hour) && hour > 0))].sort((a, b) => a - b);
    const entriesByCell = new Map();
    const dayBanners = {};

    for (const entry of entries) {
        const type = entry.item.type || "";
        const isAllDay = !entry.hour || entry.hour === 0;
        if (isAllDay && (type === "holiday" || type === "excursion")) {
            const d = entry.day;
            if (!dayBanners[d]) dayBanners[d] = [];
            dayBanners[d].push(entry);
            continue;
        }
        const key = `${entry.day}:${entry.hour}`;
        const bucket = entriesByCell.get(key);
        if (bucket) {
            bucket.push(entry);
        } else {
            entriesByCell.set(key, [entry]);
        }
    }

    const table = document.createElement("table");
    table.className = "schedule-table";
    table.setAttribute("aria-label", "Rozvrh hodin");

    const colgroup = document.createElement("colgroup");
    const dayCol = document.createElement("col");
    dayCol.style.width = "110px";
    colgroup.appendChild(dayCol);

    const thead = document.createElement("thead");
    thead.innerHTML = `
        <tr>
            <th>Den</th>
            ${hours.map((hour) => `<th>${hour}</th>`).join("")}
        </tr>
    `;

    const tbody = document.createElement("tbody");

    for (const day of days) {
        const dayBannerList = dayBanners[day];
        if (dayBannerList) {
            for (const banner of dayBannerList) {
                const type = banner.item.type || "";
                const row = document.createElement("tr");
                const labelCell = document.createElement("th");
                labelCell.scope = "row";
                labelCell.textContent = dayLabel(day);
                row.appendChild(labelCell);

                const bannerCell = document.createElement("td");
                bannerCell.colSpan = hours.length;
                bannerCell.className = type === "holiday" ? "schedule-holiday" : "schedule-excursion";
                bannerCell.textContent = banner.item.subject || (type === "holiday" ? "Prázdniny" : "Exkurze");
                row.appendChild(bannerCell);
                tbody.appendChild(row);
            }
            continue;
        }

        const row = document.createElement("tr");

        const hourCell = document.createElement("th");
        hourCell.scope = "row";
        hourCell.textContent = dayLabel(day);
        row.appendChild(hourCell);

        for (const hour of hours) {
            const cell = document.createElement("td");

            const bucket = entriesByCell.get(`${day}:${hour}`) || [];
            if (!bucket.length) {
                cell.className = "schedule-empty";
            } else {
                const frame = document.createElement("div");
                frame.className = "schedule-cell-frame";

                const stack = document.createElement("div");
                stack.className = "schedule-cell-stack";
                stack.style.gridTemplateRows = `repeat(${bucket.length}, minmax(0, 1fr))`;
                for (const entry of bucket) {
                    stack.appendChild(scheduleCellContent(entry));
                }
                frame.appendChild(stack);
                cell.appendChild(frame);
            }

            row.appendChild(cell);
        }

        tbody.appendChild(row);
    }

    table.appendChild(colgroup);
    table.appendChild(thead);
    table.appendChild(tbody);
    return table;
}

function renderSchedule(forceAll = false) {
    const list = document.getElementById("scheduleList");
    if (!list) return;

    const savedScroll = list.scrollLeft;
    list.innerHTML = "";

    const allEntries = allScheduleEntries();
    const entries = forceAll ? allEntries : visibleSchedule();
    const renderEntries = forceAll ? allEntries : entries;

    list.appendChild(createScheduleTable(renderEntries, allEntries));
    requestAnimationFrame(() => {
        fitScheduleEntries(list);
        list.scrollLeft = savedScroll;
    });
}

const scheduleToggle = document.getElementById("scheduleToggle");
const scheduleDialog = document.getElementById("scheduleDialog");
const scheduleClose = document.getElementById("scheduleClose");
const weekButtons = Array.from(document.querySelectorAll("[data-week-offset]"));
const weekLabel = document.getElementById("weekLabel");
const scheduleViewSelects = ["viewAj", "viewTv", "viewJazyky"]
    .map((id) => document.getElementById(id))
    .filter(Boolean);

function setWeekState(offset) {
    selectedWeekOffset = offset;

    for (const button of weekButtons) {
        const buttonOffset = Number(button.getAttribute("data-week-offset"));
        const active = selectedWeekOffset !== null && buttonOffset === selectedWeekOffset;
        button.setAttribute("aria-pressed", String(active));
        button.classList.toggle("is-active", active);
    }

    if (weekLabel) {
        weekLabel.textContent = selectedWeekOffset === null
            ? "Všechny týdny"
            : `${weekRangeLabel(selectedWeekOffset)} · ${weekParityLabel(isoWeekParity(addDays(todayStart(), selectedWeekOffset * 7)))} týden`;
    }

    renderSchedule();
}

function setScheduleToggleState(open) {
    if (!scheduleToggle) return;
    scheduleToggle.textContent = "Rozvrh hodin";
    scheduleToggle.setAttribute("aria-expanded", String(open));
}

function resetScheduleFilters() {
    for (const select of scheduleViewSelects) {
        select.value = "all";
    }
}

function openScheduleDialog() {
    if (!scheduleDialog) return;

    if (typeof scheduleDialog.showModal === "function") {
        if (!scheduleDialog.open) {
            scheduleDialog.showModal();
        }
    } else {
        scheduleDialog.setAttribute("open", "");
    }

    resetScheduleFilters();
    setWeekState(0);
    setScheduleToggleState(true);
}

function closeScheduleDialog() {
    if (!scheduleDialog) return;

    if (typeof scheduleDialog.close === "function") {
        if (scheduleDialog.open) {
            scheduleDialog.close();
        }
    } else {
        scheduleDialog.removeAttribute("open");
    }

    setScheduleToggleState(false);
}

if (scheduleToggle) {
    scheduleToggle.addEventListener("click", () => {
        if (scheduleDialog?.open) {
            closeScheduleDialog();
        } else {
            openScheduleDialog();
        }
    });
}

if (scheduleClose) {
    scheduleClose.addEventListener("click", closeScheduleDialog);
}

for (const button of weekButtons) {
    button.addEventListener("click", () => {
        const offset = Number(button.getAttribute("data-week-offset")) || 0;
        setWeekState(offset);
    });
}

for (const select of scheduleViewSelects) {
    select.addEventListener("change", () => renderSchedule());
}

if (scheduleDialog) {
    scheduleDialog.addEventListener("click", (event) => {
        if (event.target === scheduleDialog) {
            closeScheduleDialog();
        }
    });

    scheduleDialog.addEventListener("close", () => {
        setScheduleToggleState(false);
    });
}

document.addEventListener("click", (event) => {
    const solutionButton = event.target.closest(".toggle-solution");
    if (solutionButton) {
        const box = solutionButton.closest(".box");
        if (!box) return;

        const solution = box.querySelector(".solution");
        if (!solution) return;

        const isHidden = solution.hidden;
        solution.hidden = !isHidden;
        solutionButton.textContent = isHidden ? "Skrýt řešení" : "Zobrazit řešení";
        solutionButton.setAttribute("aria-expanded", String(isHidden));
        return;
    }

});

renderTasks();
setWeekState(0);

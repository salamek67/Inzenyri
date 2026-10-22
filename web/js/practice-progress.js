// Only hashes, question indexes and outcome codes are persisted, never question text or typed answers.
const PracticeProgress = (() => {
  const memory = new Map();
  let persistent = true;
  const prefix = "inzenyri:practice:v1:",
    retention = 90 * 24 * 60 * 60 * 1000;
  function remove(key) {
    memory.delete(key);
    try {
      localStorage.removeItem(key);
    } catch {
      persistent = false;
    }
  }
  function cleanup() {
    const now = Date.now(),
      entries = new Map(memory);
    try {
      for (const key of Object.keys(localStorage)) {
        if (!key.startsWith(prefix) || entries.has(key)) continue;
        try {
          entries.set(key, JSON.parse(localStorage.getItem(key)));
        } catch {
          remove(key);
        }
      }
    } catch {
      persistent = false;
    }
    const retained = [];
    for (const [key, saved] of entries) {
      if (!saved || saved.version !== 1) {
        remove(key);
        continue;
      }
      if (saved.updatedAt === undefined) {
        saved.updatedAt = now;
        memory.set(key, saved);
        try {
          localStorage.setItem(key, JSON.stringify(saved));
        } catch {
          persistent = false;
        }
      }
      if (
        !Number.isFinite(saved.updatedAt) ||
        saved.updatedAt <= 0 ||
        saved.updatedAt > now ||
        now - saved.updatedAt > retention
      ) {
        remove(key);
        continue;
      }
      retained.push([key, saved.updatedAt]);
    }
    retained
      .sort((a, b) => b[1] - a[1])
      .slice(200)
      .forEach(([key]) => remove(key));
  }
  cleanup();
  function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
    if (value && typeof value === "object")
      return `{${Object.keys(value)
        .sort()
        .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
        .join(",")}}`;
    return JSON.stringify(value);
  }
  async function hash(value) {
    return [
      ...new Uint8Array(
        await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
      ),
    ]
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");
  }
  async function identity(itemKey, quiz, index, sets) {
    const id =
      typeof quiz.id === "string" &&
      quiz.id.trim() &&
      sets.filter((other) => other.id === quiz.id).length === 1
        ? quiz.id
        : `legacy:${index}`;
    const questions = quiz.questions.filter(
      (question) =>
        question && typeof question === "object" && !Array.isArray(question),
    );
    return {
      key: "inzenyri:practice:v1:" + (await hash(itemKey + "\u001f" + id)),
      fingerprint: await hash(
        canonical({ questions, shuffle: quiz.shuffle === true }),
      ),
      size: questions.length,
    };
  }
  function read(identity) {
    let value;
    try {
      value = localStorage.getItem(identity.key);
    } catch {
      persistent = false;
    }
    try {
      const saved =
        (!persistent && memory.get(identity.key)) ||
        (value ? JSON.parse(value) : memory.get(identity.key));
      if (!saved) return null;
      if (
        saved.version !== 1 ||
        saved.fingerprint !== identity.fingerprint ||
        !Number.isFinite(saved.updatedAt) ||
        Date.now() - saved.updatedAt < 0 ||
        Date.now() - saved.updatedAt > retention ||
        !Array.isArray(saved.answers) ||
        saved.answers.length !== identity.size ||
        identity.size < 1 ||
        !saved.answers.every(
          (code) => Number.isInteger(code) && code >= -1 && code <= 2,
        ) ||
        !Array.isArray(saved.order) ||
        !saved.order.length ||
        new Set(saved.order).size !== saved.order.length ||
        !saved.order.every(
          (index) =>
            Number.isInteger(index) && index >= 0 && index < identity.size,
        ) ||
        !Number.isInteger(saved.cursor) ||
        saved.cursor < 0 ||
        saved.cursor > saved.order.length ||
        saved.order
          .slice(0, saved.cursor)
          .some((index) => saved.answers[index] === -1)
      ) {
        remove(identity.key);
        return null;
      }
      return {
        order: [...saved.order],
        cursor: saved.cursor,
        answers: [...saved.answers],
      };
    } catch {
      remove(identity.key);
      return null;
    }
  }
  function write(identity, attempt) {
    const saved = {
      version: 1,
      updatedAt: Date.now(),
      fingerprint: identity.fingerprint,
      order: [...attempt.order],
      cursor: attempt.cursor,
      answers: [...attempt.answers],
    };
    memory.set(identity.key, saved);
    try {
      localStorage.setItem(identity.key, JSON.stringify(saved));
    } catch {
      persistent = false;
    }
    cleanup();
  }
  function summary(attempt) {
    const answered =
      attempt?.answers.filter((code) => code === 0 || code === 1).length || 0;
    const total = attempt?.answers.length || 0;
    return {
      answered,
      complete: total > 0 && answered === total,
      percent: total
        ? Math.round(
            (attempt.answers.filter((code) => code === 1).length * 100) / total,
          )
        : 0,
    };
  }
  function fresh(identity, shuffle) {
    if (!identity.size) throw new Error("Kvíz neobsahuje otázky.");
    const indexes = Array.from({ length: identity.size }, (_, index) => index);
    return {
      order: shuffle ? shuffledQuestions(indexes) : indexes,
      cursor: 0,
      answers: indexes.map(() => -1),
    };
  }
  function resume(identity, shuffle) {
    const attempt = read(identity) || fresh(identity, shuffle);
    if (attempt.cursor === attempt.order.length && !summary(attempt).complete) {
      attempt.order = attempt.answers.flatMap((code, index) =>
        code === -1 || code === 2 ? [index] : [],
      );
      if (shuffle) attempt.order = shuffledQuestions(attempt.order);
      attempt.cursor = 0;
    }
    write(identity, attempt);
    return attempt;
  }
  return {
    identity,
    read,
    write,
    summary,
    fresh,
    resume,
    get persistent() {
      return persistent;
    },
  };
})();
function practiceRing(percent) {
  const ring = document.createElement("div");
  ring.className = "practice-ring";
  ring.style.setProperty(
    "--progress",
    `${Math.max(0, Math.min(100, percent))}%`,
  );
  ring.setAttribute("role", "img");
  ring.setAttribute("aria-label", `${percent} % správných odpovědí`);
  const label = document.createElement("span");
  label.textContent = `${percent} %`;
  ring.append(label);
  return ring;
}

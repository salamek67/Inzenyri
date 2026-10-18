/* Shared fill-in-text question format: numbered {{1}} placeholders and ordered answers. */
const FillInQuiz = (() => {
  const marker = /\{\{([1-9]\d*)\}\}/g;
  function create(question) {
    if (typeof question.template !== "string" || !Array.isArray(question.answers) ||
        question.answers.length < 1 || question.answers.length > 20 ||
        !question.answers.every(answer => typeof answer === "string" && answer.trim())) return null;
    const matches = [...question.template.matchAll(marker)];
    if (matches.length !== question.answers.length || matches.some((match, i) => Number(match[1]) !== i + 1)) return null;
    const element = document.createElement("div"), fields = [];
    element.className = "quiz-fill";
    let cursor = 0;
    matches.forEach((match, i) => {
      if (match.index > cursor) element.append(document.createTextNode(question.template.slice(cursor, match.index)));
      const token = document.createElement("label"), input = document.createElement("input");
      token.className = "quiz-fill-token";
      input.className = "quiz-fill-input";
      input.name = `blank${i + 1}`;
      input.type = "text";
      input.inputMode = question.numeric === true ? "numeric" : "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      input.required = true;
      input.maxLength = question.numeric === true ? 6 : 80;
      input.placeholder = "?";
      input.setAttribute("aria-label", `${question.numeric === true ? "Koeficient" : "Doplnění"} ${i + 1}`);
      if (question.numeric === true) input.pattern = "[0-9]+";
      token.append(input);
      const next = matches[i + 1]?.index ?? question.template.length;
      token.append(document.createTextNode(question.template.slice(match.index + match[0].length, next)));
      element.append(token);
      fields.push(input);
      cursor = next;
    });
    const normalize = value => {
      const text = String(value).trim().replace(/\s+/g, " ").normalize("NFC");
      if (question.numeric === true && /^\d+$/.test(text)) return text.replace(/^0+(?=\d)/, "");
      return question.ignoreCase === true ? text.toLocaleLowerCase("cs") : text;
    };
    return {
      element,
      complete: () => fields.every(field => field.value.trim()),
      correct: () => fields.every((field, i) => normalize(field.value) === normalize(question.answers[i])),
      solution: question.template.replace(marker, (_, index) => question.answers[Number(index) - 1]),
    };
  }
  return { create };
})();

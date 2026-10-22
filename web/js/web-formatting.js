/* Markdown and math render locally; raw HTML and automatic external image loads are disabled. */
const RichFormatting = (() => {
  const escape = (value) =>
    String(value).replace(
      /[&<>"']/g,
      (char) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[char],
    );
  function normalizeMath(value) {
    let text = value
      .replace(/\/approxeq|\/aproxeq|\/approx/g, "\\approx ")
      .replace(/\/cdot/g, "\\cdot ");
    for (let i = 0; i < 8; i++)
      text = text.replace(
        /(?<!\\)sqrt\(([^()]*)\)/g,
        (_, body) => `\\sqrt{${body}}`,
      );
    text = text.replace(/(?<!\\)sqrt\{/g, "\\sqrt{");
    text = text.replace(
      /(?<=[\p{L}\p{N})}])\s*\*\s*(?=[\p{L}\p{N}({\\])/gu,
      "\\cdot ",
    );
    return text.replace(/\^(-?[\p{L}\p{N}]+(?:[.,][0-9]+)?)/gu, "^{$1}");
  }
  function math(text, displayMode) {
    return katex.renderToString(normalizeMath(text), {
      displayMode,
      throwOnError: false,
      trust: false,
      strict: "ignore",
      maxExpand: 1000,
      maxSize: 10,
      output: "htmlAndMathml",
    });
  }
  const markdown = new marked.Marked({
    gfm: true,
    breaks: true,
    renderer: {
      html: (token) => escape(token.text),
      image: (token) => escape(token.text || ""),
    },
    extensions: [
      {
        name: "mathBlock",
        level: "block",
        start: (source) => source.indexOf("$$"),
        tokenizer(source) {
          const match = source.match(
            /^\$\$[ \t]*\n([\s\S]+?)\n\$\$(?:[ \t]*\n|$)/,
          );
          if (match)
            return { type: "mathBlock", raw: match[0], text: match[1] };
        },
        renderer: (token) =>
          `<div class="math-block">${math(token.text, true)}</div>`,
      },
      {
        name: "mathInline",
        level: "inline",
        start: (source) => source.indexOf("$"),
        tokenizer(source) {
          const match =
            source.match(/^\$\$([^\n]+?)\$\$/) ||
            source.match(/^\$([^$\n]+?)\$(?!\$)/);
          if (match)
            return { type: "mathInline", raw: match[0], text: match[1] };
        },
        renderer: (token) =>
          `<span class="math-expression">${math(token.text, false)}</span>`,
      },
    ],
  });
  function render(target, value) {
    const source = String(value ?? "").replace(/\\n(?![A-Za-z])/g, "\n");
    try {
      const inline = ["SPAN", "P", "H1", "H2", "H3", "BUTTON"].includes(
        target.tagName,
      );
      const html = inline
        ? markdown.parseInline(source)
        : markdown.parse(source);
      const fragment = DOMPurify.sanitize(html, {
        RETURN_DOM_FRAGMENT: true,
        FORBID_TAGS: [
          "script",
          "style",
          "iframe",
          "object",
          "embed",
          "img",
          "video",
          "audio",
          "form",
          "input",
          "button",
        ],
      });
      fragment.querySelectorAll("a").forEach((link) => {
        let url;
        const href = link.getAttribute("href");
        if (!href) return;
        try {
          url = new URL(href, location.href);
        } catch {
          link.removeAttribute("href");
          return;
        }
        if (!["https:", "http:", "mailto:"].includes(url.protocol))
          link.removeAttribute("href");
        else {
          link.href = url.href;
          link.target = "_blank";
          link.rel = "noopener noreferrer";
        }
      });
      target.replaceChildren(fragment);
    } catch {
      target.textContent = source;
    }
  }
  return { render };
})();

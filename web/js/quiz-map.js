/* Shared offline map player and authoring surface. Coordinates are [longitude, latitude]. */
const MapQuiz = (() => {
  const views = {
    world: { name: "Svět", bounds: [-180, -90, 180, 90] },
    europe: { name: "Evropa", bounds: [-25, 32, 45, 72] },
    africa: { name: "Afrika", bounds: [-20, -36, 55, 38] },
    asia: { name: "Asie", bounds: [25, -12, 180, 80] },
    northAmerica: { name: "Severní Amerika", bounds: [-170, 5, -50, 84] },
    southAmerica: { name: "Jižní Amerika", bounds: [-85, -57, -32, 15] },
    oceania: { name: "Oceánie", bounds: [110, -50, 180, 5] },
  };
  const ns = "http://www.w3.org/2000/svg";
  const dataUrl = new URL("../maps/world.json", document.currentScript.src);
  let loading;
  function countries() {
    loading ||= fetch(dataUrl)
      .then((response) => {
        if (!response.ok)
          throw new Error("Mapový podklad se nepodařilo načíst.");
        return response.json();
      })
      .then((data) => data.countries)
      .catch((error) => {
        loading = null;
        throw error;
      });
    return loading;
  }
  function name(country) {
    try {
      if (/^[A-Z]{2}$/.test(country.code))
        return new Intl.DisplayNames(["cs"], { type: "region" }).of(
          country.code,
        );
    } catch (_) {
      /* Fall back to the dataset's name. */
    }
    return country.name;
  }
  function visible(country, bounds) {
    return country.polygons.some((polygon) => {
      const ring = polygon[0],
        xs = ring.map((p) => p[0]),
        ys = ring.map((p) => p[1]);
      return (
        Math.max(...xs) >= bounds[0] &&
        Math.min(...xs) <= bounds[2] &&
        Math.max(...ys) >= bounds[1] &&
        Math.min(...ys) <= bounds[3]
      );
    });
  }
  function valid(q) {
    if (
      !q ||
      typeof q.map !== "string" ||
      !Object.hasOwn(views, q.map) ||
      !["regions", "pin"].includes(q.mode) ||
      typeof q.prompt !== "string" ||
      !q.prompt.trim()
    )
      return false;
    if (q.mode === "regions")
      return (
        Array.isArray(q.targets) &&
        q.targets.length > 0 &&
        q.targets.length <= 200 &&
        new Set(q.targets).size === q.targets.length &&
        q.targets.every(
          (id) => typeof id === "string" && /^[A-Z0-9]{3}$/.test(id),
        )
      );
    const b = views[q.map].bounds;
    return (
      Array.isArray(q.target) &&
      q.target.length === 2 &&
      q.target.every(Number.isFinite) &&
      q.target[0] >= b[0] &&
      q.target[0] <= b[2] &&
      q.target[1] >= b[1] &&
      q.target[1] <= b[3] &&
      Number.isFinite(q.toleranceKm) &&
      q.toleranceKm >= 1 &&
      q.toleranceKm <= 2000
    );
  }
  function distance(a, b) {
    const rad = Math.PI / 180,
      dlat = (b[1] - a[1]) * rad,
      dlon = (b[0] - a[0]) * rad;
    const h =
      Math.sin(dlat / 2) ** 2 +
      Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dlon / 2) ** 2;
    return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
  }
  function svgNode(tag, attributes = {}) {
    const node = document.createElementNS(ns, tag);
    for (const [key, value] of Object.entries(attributes))
      node.setAttribute(key, String(value));
    return node;
  }
  function create(question, options = {}) {
    const editor = options.editor === true;
    if (
      !Object.hasOwn(views, question.map) ||
      !["regions", "pin"].includes(question.mode) ||
      (!editor && !valid(question))
    )
      return null;
    const root = document.createElement("div"),
      controls = document.createElement("div"),
      status = document.createElement("p"),
      credit = document.createElement("small");
    root.className = "quiz-map";
    controls.className = "map-controls";
    status.className = "map-status";
    status.setAttribute("aria-live", "polite");
    status.textContent = "Načítám offline mapu…";
    const b = views[question.map].bounds,
      initial = [b[0], -b[3], b[2] - b[0], b[3] - b[1]];
    let box = [...initial],
      ready = false,
      error = false,
      reviewed = false,
      locked = options.readOnly === true,
      data = [];
    let selected = new Set(
        editor && Array.isArray(question.targets) ? question.targets : [],
      ),
      pin =
        editor &&
        Array.isArray(question.target) &&
        question.target.length === 2 &&
        question.target.every(Number.isFinite)
          ? [...question.target]
          : null;
    const svg = svgNode("svg", {
      width: initial[2],
      height: initial[3],
      viewBox: initial.join(" "),
      tabindex: "0",
      role: "group",
      "aria-label": `${views[question.map].name}: ${question.mode === "pin" ? "umísti špendlík klepnutím nebo šipkami" : "vyber oblasti klepnutím; na oblasti lze přejít tabulátorem a vybrat je Enterem"}`,
    });
    svg.classList.add("map-canvas");
    const land = svgNode("g"),
      markers = svgNode("g");
    markers.setAttribute("pointer-events", "none");
    svg.append(land, markers);
    const buttons = [];
    function button(label, title, action) {
      const node = document.createElement("button");
      node.type = "button";
      node.textContent = label;
      node.title = title;
      node.setAttribute("aria-label", title);
      node.addEventListener("click", action);
      controls.append(node);
      buttons.push(node);
      return node;
    }
    function setBox(next) {
      const factor = Math.max(1 / 16, Math.min(1, next[2] / initial[2]));
      const width = initial[2] * factor,
        height = initial[3] * factor;
      box = [
        Math.max(
          initial[0],
          Math.min(initial[0] + initial[2] - width, next[0]),
        ),
        Math.max(
          initial[1],
          Math.min(initial[1] + initial[3] - height, next[1]),
        ),
        width,
        height,
      ];
      svg.setAttribute("viewBox", box.join(" "));
      drawMarkers();
    }
    function zoom(factor) {
      const [x, y, w, h] = box;
      setBox([
        x + (w * (1 - factor)) / 2,
        y + (h * (1 - factor)) / 2,
        w * factor,
        h * factor,
      ]);
    }
    button("＋", "Přiblížit mapu", () => zoom(0.65));
    button("−", "Oddálit mapu", () => zoom(1 / 0.65));
    button("Celá mapa", "Obnovit mapu", () => setBox([...initial]));
    const clear = button("Vymazat výběr", "Vymazat odpověď", () => {
      if (locked) return;
      selected.clear();
      pin = null;
      update(true);
    });
    clear.disabled = locked;
    credit.textContent =
      "Natural Earth · přehledový podklad 1:110m. Přiblížení: +/−, posun: táhnutí dvěma prsty nebo myší.";
    root.append(controls, svg, status, credit);
    function point(clientX, clientY) {
      const matrix = svg.getScreenCTM();
      if (!matrix) return null;
      const p = new DOMPoint(clientX, clientY).matrixTransform(
        matrix.inverse(),
      );
      return [
        Math.max(b[0], Math.min(b[2], p.x)),
        Math.max(b[1], Math.min(b[3], -p.y)),
      ];
    }
    function marker(position, kind, scale = 1) {
      const radius = (box[2] / 75) * scale;
      markers.append(
        svgNode("circle", {
          cx: position[0],
          cy: -position[1],
          r: radius,
          class: `map-marker ${kind}`,
          "vector-effect": "non-scaling-stroke",
        }),
      );
    }
    function drawMarkers() {
      markers.replaceChildren();
      if (reviewed && question.mode === "pin") {
        marker(question.target, "correct", 1.6);
        if (pin)
          markers.prepend(
            svgNode("line", {
              x1: pin[0],
              y1: -pin[1],
              x2: question.target[0],
              y2: -question.target[1],
              class: "map-link",
              "vector-effect": "non-scaling-stroke",
            }),
          );
      }
      if (pin)
        marker(
          pin,
          reviewed && distance(pin, question.target) > question.toleranceKm
            ? "wrong"
            : "selected",
        );
    }
    function update(changed = false) {
      land.querySelectorAll("path").forEach((path) => {
        const id = path.dataset.region;
        path.classList.toggle("selected", selected.has(id));
        path.classList.toggle(
          "correct",
          Boolean(reviewed && question.targets?.includes(id)),
        );
        path.classList.toggle(
          "wrong",
          reviewed && selected.has(id) && !question.targets?.includes(id),
        );
        path.setAttribute("aria-pressed", String(selected.has(id)));
      });
      drawMarkers();
      if (!ready || error) return;
      if (reviewed) {
        status.textContent =
          question.mode === "pin"
            ? `Vzdálenost od cíle: ${distance(pin, question.target).toFixed(1)} km. Tolerance: ${question.toleranceKm} km. Zeleně je správné místo.`
            : `Správné oblasti: ${data
                .filter((c) => question.targets.includes(c.id))
                .map(name)
                .join(", ")}. Zeleně správné, červeně chybně vybrané.`;
      } else
        status.textContent =
          question.mode === "regions"
            ? `Vybráno oblastí: ${selected.size}${editor ? "" : ` / ${question.targets.length}`}. Klepnutím výběr přidáš nebo zrušíš.`
            : pin
              ? `Špendlík: ${pin[1].toFixed(3)}° š., ${pin[0].toFixed(3)}° d.${editor ? "" : ` · tolerance ${question.toleranceKm} km`}`
              : `Klepni na mapu pro umístění špendlíku.${editor ? "" : ` Tolerance: ${question.toleranceKm} km.`}`;
      if (changed)
        options.onChange?.(
          question.mode === "regions"
            ? { targets: [...selected] }
            : { target: pin ? [...pin] : null },
        );
    }
    function toggle(id) {
      if (locked || !ready) return;
      selected.has(id) ? selected.delete(id) : selected.add(id);
      update(true);
    }
    const pointers = new Map();
    let drag = null,
      pinch = null,
      moved = false;
    svg.addEventListener("pointerdown", (event) => {
      if (!ready) return;
      svg.setPointerCapture(event.pointerId);
      pointers.set(event.pointerId, [event.clientX, event.clientY]);
      if (pointers.size === 1) {
        drag = {
          x: event.clientX,
          y: event.clientY,
          box: [...box],
          id: event.target.dataset.region,
        };
        moved = false;
      } else if (pointers.size === 2) {
        const [a, c] = [...pointers.values()];
        pinch = {
          distance: Math.hypot(a[0] - c[0], a[1] - c[1]),
          box: [...box],
        };
        moved = true;
      }
    });
    svg.addEventListener("pointermove", (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, [event.clientX, event.clientY]);
      if (pointers.size === 2 && pinch) {
        const [a, c] = [...pointers.values()],
          factor =
            pinch.distance / Math.max(1, Math.hypot(a[0] - c[0], a[1] - c[1]));
        const [x, y, w, h] = pinch.box;
        setBox([
          x + (w * (1 - factor)) / 2,
          y + (h * (1 - factor)) / 2,
          w * factor,
          h * factor,
        ]);
      } else if (pointers.size === 1 && drag && !pinch) {
        const dx = event.clientX - drag.x,
          dy = event.clientY - drag.y;
        if (Math.hypot(dx, dy) > 5) moved = true;
        if (moved) {
          const rect = svg.getBoundingClientRect();
          setBox([
            drag.box[0] - (dx / rect.width) * drag.box[2],
            drag.box[1] - (dy / rect.height) * drag.box[3],
            drag.box[2],
            drag.box[3],
          ]);
        }
      }
    });
    function finish(event) {
      const choose =
        pointers.size === 1 &&
        !moved &&
        !pinch &&
        event.type === "pointerup" &&
        !locked;
      if (choose && question.mode === "pin") {
        pin = point(event.clientX, event.clientY);
        update(true);
      }
      if (choose && question.mode === "regions" && drag?.id) toggle(drag.id);
      pointers.delete(event.pointerId);
      if (!pointers.size) {
        drag = null;
        pinch = null;
      }
    }
    svg.addEventListener("pointerup", finish);
    svg.addEventListener("pointercancel", finish);
    svg.addEventListener("keydown", (event) => {
      if (
        locked ||
        !ready ||
        question.mode !== "pin" ||
        !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Enter"].includes(
          event.key,
        )
      )
        return;
      event.preventDefault();
      pin ||= [box[0] + box[2] / 2, -box[1] - box[3] / 2];
      const step = event.shiftKey ? 0.05 : 0.01;
      if (event.key === "ArrowLeft") pin[0] -= box[2] * step;
      if (event.key === "ArrowRight") pin[0] += box[2] * step;
      if (event.key === "ArrowUp") pin[1] += box[3] * step;
      if (event.key === "ArrowDown") pin[1] -= box[3] * step;
      pin = [
        Math.max(b[0], Math.min(b[2], pin[0])),
        Math.max(b[1], Math.min(b[3], pin[1])),
      ];
      update(true);
    });
    countries()
      .then((values) => {
        data = values.filter((country) => visible(country, b));
        if (
          !editor &&
          question.mode === "regions" &&
          question.targets.some((id) => !data.some((c) => c.id === id))
        )
          throw new Error("Neplatná oblast v zadání mapy.");
        data.forEach((country, index) => {
          const d = country.polygons
            .map((polygon) =>
              polygon
                .map(
                  (ring) =>
                    ring
                      .map((p, i) => `${i ? "L" : "M"}${p[0]},${-p[1]}`)
                      .join(" ") + "Z",
                )
                .join(" "),
            )
            .join(" ");
          const path = svgNode("path", {
            d,
            "fill-rule": "evenodd",
            "vector-effect": "non-scaling-stroke",
          });
          path.dataset.region = country.id;
          if (question.mode === "regions") {
            path.setAttribute("role", "button");
            path.setAttribute("tabindex", "0");
            path.setAttribute(
              "aria-label",
              editor ? name(country) : `Oblast ${index + 1}`,
            );
            path.addEventListener("keydown", (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                toggle(country.id);
              }
            });
          }
          if (editor) {
            const title = svgNode("title");
            title.textContent = `${name(country)} (${country.id})`;
            path.append(title);
          }
          land.append(path);
        });
        ready = true;
        update();
      })
      .catch((reason) => {
        error = true;
        status.textContent =
          reason.message + " Otázku můžeš přeskočit nebo načíst znovu.";
      });
    return {
      element: root,
      complete: () =>
        ready &&
        !error &&
        (question.mode === "pin" ? Boolean(pin) : selected.size > 0),
      correct: () =>
        question.mode === "pin"
          ? Boolean(pin) &&
            distance(pin, question.target) <= question.toleranceKm
          : selected.size === question.targets.length &&
            question.targets.every((id) => selected.has(id)),
      review: () => {
        locked = true;
        reviewed = true;
        clear.disabled = true;
        land.querySelectorAll("path").forEach((path) => {
          path.setAttribute("aria-disabled", "true");
          path.setAttribute("tabindex", "-1");
        });
        buttons.slice(0, 3).forEach((node) => (node.disabled = false));
        update();
      },
      solution: () =>
        question.mode === "pin"
          ? `${question.target[1]}° š., ${question.target[0]}° d. (±${question.toleranceKm} km)`
          : data
              .filter((c) => question.targets.includes(c.id))
              .map(name)
              .join(", "),
    };
  }
  return { views, countries, name, visible, valid, distance, create };
})();

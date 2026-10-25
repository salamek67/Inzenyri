/* Images live inside the decrypted question; no attachment URLs or disk cache. */
const QuizImage = (() => {
  const maxBytes = 512 * 1024;
  function valid(image) {
    return (
      image &&
      ["image/png", "image/jpeg"].includes(image.mime) &&
      typeof image.data === "string" &&
      image.data.length > 0 &&
      image.data.length <= Math.ceil(maxBytes / 3) * 4 &&
      (image.data.length * 3) / 4 -
        (image.data.endsWith("==") ? 2 : image.data.endsWith("=") ? 1 : 0) <=
        maxBytes &&
      image.data.length % 4 === 0 &&
      /^[A-Za-z0-9+/]+={0,2}$/.test(image.data) &&
      Number.isInteger(image.width) &&
      image.width > 0 &&
      image.width <= 2048 &&
      Number.isInteger(image.height) &&
      image.height > 0 &&
      image.height <= 2048 &&
      typeof image.alt === "string" &&
      image.alt.length <= 1000
    );
  }
  function create(image) {
    if (!valid(image)) return null;
    const figure = document.createElement("figure"),
      img = document.createElement("img");
    figure.className = "quiz-image";
    img.alt = image.alt || "Obrázek k otázce";
    img.width = image.width;
    img.height = image.height;
    img.decoding = "async";
    img.addEventListener("error", () =>
      figure.replaceChildren(
        document.createTextNode("Obrázek se nepodařilo načíst."),
      ),
    );
    img.src = `data:${image.mime};base64,${image.data}`;
    figure.append(img);
    return figure;
  }
  async function importFile(file) {
    if (
      !file ||
      !["image/png", "image/jpeg"].includes(file.type) ||
      file.size > 10 * 1024 * 1024
    )
      throw new Error("Vyber PNG nebo JPEG do 10 MB.");
    const bytes = new Uint8Array(await file.arrayBuffer());
    const view = new DataView(bytes.buffer);
    let width = 0,
      height = 0;
    if (
      bytes.length >= 24 &&
      bytes[0] === 137 &&
      String.fromCharCode(...bytes.slice(1, 4)) === "PNG"
    ) {
      width = view.getUint32(16);
      height = view.getUint32(20);
    } else if (bytes[0] === 255 && bytes[1] === 216) {
      let offset = 2;
      while (offset + 9 < bytes.length && bytes[offset] === 255) {
        while (bytes[offset] === 255) offset++;
        const marker = bytes[offset++];
        if (marker === 218 || marker === 217) break;
        if (marker === 1 || (marker >= 208 && marker <= 215)) continue;
        if (offset + 2 > bytes.length) break;
        const length = view.getUint16(offset);
        if (length < 2 || offset + length > bytes.length) break;
        if ([192, 193, 194].includes(marker) && length >= 8) {
          height = view.getUint16(offset + 3);
          width = view.getUint16(offset + 5);
          break;
        }
        offset += length;
      }
    }
    if (!width || !height || width * height > 32000000)
      throw new Error(
        "Neplatný nebo příliš velký obrázek (nejvýše 32 megapixelů).",
      );
    const bitmap = await createImageBitmap(file);
    try {
      if (
        !bitmap.width ||
        !bitmap.height ||
        bitmap.width * bitmap.height > 32000000
      )
        throw new Error("Obrázek je příliš velký (nejvýše 32 megapixelů).");
      const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const context = canvas.getContext("2d");
      context.fillStyle = "white";
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      // Re-encode to strip file metadata; progressively compress larger photos.
      for (const quality of [0.9, 0.75, 0.6, 0.45]) {
        const data = canvas.toDataURL("image/jpeg", quality).split(",")[1];
        if (atob(data).length <= maxBytes)
          return {
            mime: "image/jpeg",
            data,
            width: canvas.width,
            height: canvas.height,
            alt: "",
          };
      }
      throw new Error("Obrázek se nevejde do 512 KB. Vyber menší výřez.");
    } finally {
      bitmap.close();
    }
  }
  return { valid, create, importFile };
})();

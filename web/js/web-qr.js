// The login password is retained only in volatile session memory for sharing QR codes.
const LoginQrSecret = (() => {
  let bytes = null;
  return {
    remember(value) {
      bytes?.fill(0);
      bytes = new TextEncoder().encode(value);
    },
    read() {
      return bytes ? new TextDecoder().decode(bytes) : null;
    },
    clear() {
      bytes?.fill(0);
      bytes = null;
    },
  };
})();
// QR data stays on this device. The bundled codec is loaded only when needed.
let qrLibrary;
function loadQrLibrary() {
  if (window.ZXing) return Promise.resolve(window.ZXing);
  if (!qrLibrary)
    qrLibrary = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "vendor/zxing-0.21.3.min.js";
      script.onload = () => resolve(window.ZXing);
      script.onerror = () => {
        script.remove();
        qrLibrary = null;
        reject(new Error("QR knihovnu se nepodařilo načíst."));
      };
      document.head.append(script);
    });
  return qrLibrary;
}
const qrScanDialog = document.getElementById("qrScanDialog"),
  qrShareDialog = document.getElementById("qrShareDialog");
let qrReader = null,
  qrGeneration = 0,
  qrShareGeneration = 0;
function stopQrCamera() {
  qrGeneration++;
  qrReader?.reset();
  qrReader = null;
  const video = document.getElementById("qrVideo");
  video.srcObject?.getTracks().forEach((track) => track.stop());
  video.srcObject = null;
}
function closeLoginQr() {
  stopQrCamera();
  clearSharedQr();
  qrScanDialog.close();
  qrShareDialog.close();
}
function acceptLoginQr(raw) {
  try {
    if (typeof raw !== "string" || raw.length > 32768) throw new Error();
    const payload = JSON.parse(raw);
    if (
      payload.application !== "inzenyri" ||
      payload.purpose !== "database-login" ||
      payload.version !== 1 ||
      typeof payload.password !== "string" ||
      !payload.password.length
    )
      throw new Error();
    if (!qrScanDialog.open || unlockButton.disabled) return;
    qrScanDialog.close();
    stopQrCamera();
    passwordInput.value = payload.password;
    unlockForm.requestSubmit();
  } catch {
    document.getElementById("qrScanStatus").textContent =
      "Tento QR kód není platné přihlášení k Inženýrům.";
  }
}
document.getElementById("qrLoginButton").addEventListener("click", () => {
  document.getElementById("qrScanStatus").textContent =
    "Zapni fotoaparát, nebo vyber obrázek QR kódu.";
  qrScanDialog.showModal();
});
document.getElementById("qrStartCamera").addEventListener("click", async () => {
  stopQrCamera();
  const generation = qrGeneration,
    status = document.getElementById("qrScanStatus");
  let reader;
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error();
    const ZXing = await loadQrLibrary();
    if (!qrScanDialog.open || generation !== qrGeneration) return;
    reader = new ZXing.BrowserQRCodeReader();
    qrReader = reader;
    status.textContent = "Umísti QR kód doprostřed rámečku.";
    await reader.decodeFromConstraints(
      { audio: false, video: { facingMode: { ideal: "environment" } } },
      document.getElementById("qrVideo"),
      (result) => {
        if (result && generation === qrGeneration && qrScanDialog.open)
          acceptLoginQr(result.getText());
      },
    );
    if (!qrScanDialog.open || generation !== qrGeneration) reader.reset();
  } catch {
    reader?.reset();
    if (generation === qrGeneration && qrScanDialog.open)
      status.textContent =
        "Fotoaparát nelze otevřít. Povol mu přístup nebo vyber obrázek QR kódu.";
  }
});
document.getElementById("qrImage").addEventListener("change", async (event) => {
  const file = event.target.files[0];
  event.target.value = "";
  if (!file) return;
  stopQrCamera();
  const generation = qrGeneration;
  let url, reader;
  try {
    if (file.size > 16 * 1024 * 1024) throw new Error();
    const ZXing = await loadQrLibrary();
    if (!qrScanDialog.open || generation !== qrGeneration) return;
    url = URL.createObjectURL(file);
    reader = new ZXing.BrowserQRCodeReader();
    const result = await reader.decodeFromImageUrl(url);
    if (generation === qrGeneration && qrScanDialog.open)
      acceptLoginQr(result.getText());
  } catch {
    if (generation === qrGeneration && qrScanDialog.open)
      document.getElementById("qrScanStatus").textContent =
        "QR kód se nepodařilo přečíst. Vyber ostřejší obrázek do 16 MB.";
  } finally {
    if (url) URL.revokeObjectURL(url);
    reader?.reset();
  }
});
document
  .getElementById("closeQrScan")
  .addEventListener("click", () => qrScanDialog.close());
qrScanDialog.addEventListener("close", stopQrCamera);
function clearSharedQr() {
  qrShareGeneration++;
  const canvas = document.getElementById("sharedQr");
  canvas.width = 0;
  canvas.height = 0;
  canvas.hidden = true;
  document.getElementById("qrShareStatus").textContent = "";
}
document.getElementById("shareLoginQr").addEventListener("click", async () => {
  const snapshot = unlockedData;
  if (!snapshot) return;
  settingsDialog.close();
  clearSharedQr();
  qrShareDialog.showModal();
  const generation = qrShareGeneration,
    status = document.getElementById("qrShareStatus");
  status.textContent = "Připravuji QR kód…";
  let password = LoginQrSecret.read();
  try {
    if (!password) throw new Error("Session unavailable");
    const ZXing = await loadQrLibrary();
    if (
      unlockedData !== snapshot ||
      !qrShareDialog.open ||
      generation !== qrShareGeneration
    )
      return;
    const hints = new Map([
      [ZXing.EncodeHintType.CHARACTER_SET, "UTF-8"],
      [ZXing.EncodeHintType.MARGIN, 3],
    ]);
    const matrix = new ZXing.QRCodeWriter().encode(
      JSON.stringify({
        application: "inzenyri",
        purpose: "database-login",
        version: 1,
        password,
      }),
      ZXing.BarcodeFormat.QR_CODE,
      480,
      480,
      hints,
    );
    const canvas = document.getElementById("sharedQr");
    canvas.width = matrix.getWidth();
    canvas.height = matrix.getHeight();
    const ctx = canvas.getContext("2d"),
      image = ctx.createImageData(canvas.width, canvas.height);
    for (let y = 0; y < canvas.height; y++)
      for (let x = 0; x < canvas.width; x++) {
        const index = (y * canvas.width + x) * 4,
          color = matrix.get(x, y) ? 0 : 255;
        image.data[index] = color;
        image.data[index + 1] = color;
        image.data[index + 2] = color;
        image.data[index + 3] = 255;
      }
    ctx.putImageData(image, 0, 0);
    canvas.hidden = false;
    status.textContent =
      "Naskenuj kód v aplikaci nebo na webu na druhém zařízení.";
  } catch {
    if (
      unlockedData === snapshot &&
      qrShareDialog.open &&
      generation === qrShareGeneration
    )
      status.textContent =
        "QR kód se nepodařilo vytvořit. Zavři okno a zkus to znovu; pokud potíže přetrvají, znovu se přihlas.";
  } finally {
    password = "";
  }
});
document
  .getElementById("closeQrShare")
  .addEventListener("click", () => qrShareDialog.close());
qrShareDialog.addEventListener("close", clearSharedQr);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) closeLoginQr();
});
window.addEventListener("pagehide", (event) => {
  closeLoginQr();
  if (!event.persisted) LoginQrSecret.clear();
});

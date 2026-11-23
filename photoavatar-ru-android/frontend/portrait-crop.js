/**
 * Deterministic photo-to-character framing. No generative changes to identity:
 * it reframes the selected pixels and softly extends existing background.
 */
export const OUTPUT_WIDTH = 1088;
export const OUTPUT_HEIGHT = 704;
const ratio = OUTPUT_WIDTH / OUTPUT_HEIGHT;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

export function calculatePortraitCrop(width, height, face = null, zoom = 1, vertical = 0) {
  if (!(width > 1 && height > 1)) throw new Error('Неверные размеры изображения');
  const z = clamp(Number(zoom) || 1, 0.8, 1.5);
  const offset = clamp(Number(vertical) || 0, -0.35, 0.35);
  const validFace = face && face.detected &&
      face.w > 0.02 && face.h > 0.02 &&
      face.w <= 1 && face.h <= 1 &&
      Number.isFinite(face.x) && Number.isFinite(face.y);
  const faceHeight = validFace ? face.h * height : Math.min(width * 0.7, height * 0.37);
  // Keep hair, neck and shoulders: generous vertical context around face.
  const originalHeight = validFace
    ? Math.max(faceHeight * 1.72, height * 0.48)
    : Math.min(height, Math.max(width / ratio, height * 0.64));
  const cropHeight = clamp(originalHeight / z, Math.min(height, 240), height);
  const cropWidth = Math.min(width, cropHeight * ratio);
  const centerX = validFace ? clamp((face.x + face.w / 2) * width, 0, width) : width / 2;
  const centerY = validFace
    ? clamp((face.y + face.h * 0.67) * height, 0, height)
    : height * 0.50;
  const x = clamp(centerX - cropWidth / 2, 0, width - cropWidth);
  const y = clamp(centerY - cropHeight * 0.48 + offset * cropHeight, 0, height - cropHeight);
  return { x, y, width: cropWidth, height: cropHeight, detected: Boolean(validFace) };
}

export function selectedSource(image, tileIndex = -1) {
  if (!image || !image.naturalWidth || !image.naturalHeight) {
    throw new Error('Фотография ещё не загружена');
  }
  const w = image.naturalWidth;
  const h = image.naturalHeight;
  if (tileIndex === -1) return { x: 0, y: 0, width: w, height: h };
  if (!Number.isInteger(tileIndex) || tileIndex < 0 || tileIndex > 8) {
    throw new Error('Выбери ракурс из девяти фотографий');
  }
  return {
    x: (tileIndex % 3) * w / 3,
    y: Math.floor(tileIndex / 3) * h / 3,
    width: w / 3,
    height: h / 3,
  };
}

export function makeDetectionImage(image, tileIndex = -1) {
  const src = selectedSource(image, tileIndex);
  const scale = Math.min(1, 1024 / Math.max(src.width, src.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(240, Math.round(src.width * scale));
  canvas.height = Math.max(240, Math.round(src.height * scale));
  const ctx = canvas.getContext('2d');
  ctx.drawImage(image, src.x, src.y, src.width, src.height,
    0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.82);
}

export function composePortrait(image, tileIndex = -1, face = null, zoom = 1, vertical = 0) {
  const src = selectedSource(image, tileIndex);
  const plan = calculatePortraitCrop(src.width, src.height, face, zoom, vertical);
  const absolute = {
    x: src.x + plan.x,
    y: src.y + plan.y,
    width: plan.width,
    height: plan.height,
  };
  const canvas = document.createElement('canvas');
  canvas.width = OUTPUT_WIDTH;
  canvas.height = OUTPUT_HEIGHT;
  const ctx = canvas.getContext('2d', { alpha: false });
  ctx.fillStyle = '#777977';
  ctx.fillRect(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT);

  // Low-frequency extension comes from the same photograph, not an AI-invented face.
  ctx.save();
  ctx.filter = 'blur(28px) brightness(.9)';
  ctx.drawImage(image, absolute.x, absolute.y, absolute.width, absolute.height,
    -45, -45, OUTPUT_WIDTH + 90, OUTPUT_HEIGHT + 90);
  ctx.restore();

  // The sharp subject is NEVER stretched: it retains its original pixel ratio.
  const scale = Math.min(OUTPUT_WIDTH / absolute.width, OUTPUT_HEIGHT / absolute.height);
  const drawnW = absolute.width * scale;
  const drawnH = absolute.height * scale;
  const destX = (OUTPUT_WIDTH - drawnW) / 2;
  const destY = (OUTPUT_HEIGHT - drawnH) / 2;

  if (drawnW < OUTPUT_WIDTH - 12) {
    // Feather the left/right edges so a portrait phone photo has no harsh seams.
    const layer = document.createElement('canvas');
    layer.width = OUTPUT_WIDTH; layer.height = OUTPUT_HEIGHT;
    const lc = layer.getContext('2d');
    lc.drawImage(image, absolute.x, absolute.y, absolute.width, absolute.height,
      destX, destY, drawnW, drawnH);
    lc.globalCompositeOperation = 'destination-in';
    const mask = lc.createLinearGradient(destX, 0, destX + drawnW, 0);
    mask.addColorStop(0, 'rgba(0,0,0,0)');
    mask.addColorStop(0.055, 'rgba(0,0,0,1)');
    mask.addColorStop(0.945, 'rgba(0,0,0,1)');
    mask.addColorStop(1, 'rgba(0,0,0,0)');
    lc.fillStyle = mask;
    lc.fillRect(destX, 0, drawnW, OUTPUT_HEIGHT);
    ctx.drawImage(layer, 0, 0);
  } else {
    ctx.drawImage(image, absolute.x, absolute.y, absolute.width, absolute.height,
      destX, destY, drawnW, drawnH);
  }

  return { dataUrl: canvas.toDataURL('image/jpeg', .91), plan, width: OUTPUT_WIDTH, height: OUTPUT_HEIGHT };
}

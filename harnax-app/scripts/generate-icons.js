/**
 * Generate App icon and TabBar icons as PNG files.
 * Uses Node.js built-in zlib to create minimal valid PNGs.
 */
const zlib = require('zlib')
const fs = require('fs')
const path = require('path')

const STATIC_DIR = path.join(__dirname, '..', 'src', 'static')

function createPNG(width, height, pixelFn) {
  // Create raw RGBA data
  const rawData = []
  for (let y = 0; y < height; y++) {
    rawData.push(0) // filter byte
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = pixelFn(x, y, width, height)
      rawData.push(r, g, b, a)
    }
  }

  const raw = Buffer.from(rawData)
  const compressed = zlib.deflateSync(raw)

  // Build PNG
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])

  function chunk(type, data) {
    const typeBuffer = Buffer.from(type)
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const crcData = Buffer.concat([typeBuffer, data])
    const crc = crc32(crcData)
    const crcBuf = Buffer.alloc(4)
    crcBuf.writeUInt32BE(crc)
    return Buffer.concat([length, typeBuffer, data, crcBuf])
  }

  // IHDR
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8  // bit depth
  ihdr[9] = 6  // color type RGBA
  ihdr[10] = 0 // compression
  ihdr[11] = 0 // filter
  ihdr[12] = 0 // interlace

  // IDAT
  const idat = compressed

  // IEND
  const iend = Buffer.alloc(0)

  return Buffer.concat([
    signature,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', iend),
  ])
}

// CRC32 for PNG chunks
function crc32(buf) {
  let crc = 0xffffffff
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i]
    for (let j = 0; j < 8; j++) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0)
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

// --- App Icon (1024x1024) ---
function appIconPixel(x, y, w, h) {
  const cx = w / 2, cy = h / 2
  const nx = (x - cx) / (w / 2), ny = (y - cy) / (h / 2)

  // Rounded rectangle background with gradient
  const rx = 0.22, ry = 0.22
  const inRect = Math.abs(nx) < (1 - rx) || Math.abs(ny) < (1 - ry) ||
    (Math.abs(nx) < 1 && Math.abs(ny) < 1 &&
     Math.pow(Math.max(0, (Math.abs(nx) - (1 - rx)) / rx), 2) +
     Math.pow(Math.max(0, (Math.abs(ny) - (1 - ry)) / ry), 2) < 1)

  if (!inRect) return [0, 0, 0, 0]

  // Gradient from #4f6ef7 to #7c5bf5
  const t = (ny + 1) / 2
  const r = Math.round(79 + (124 - 79) * t)
  const g = Math.round(110 + (91 - 110) * t)
  const b = Math.round(247 + (245 - 247) * t)

  // Chat bubble shape (white, centered)
  const bubbleW = 0.55, bubbleH = 0.40
  const inBubble = Math.abs(nx) < bubbleW * 0.8 && Math.abs(ny + 0.05) < bubbleH * 0.8
  const bubbleRound = Math.pow(nx / bubbleW, 2) + Math.pow((ny + 0.05) / bubbleH, 2) < 1

  // Bubble tail
  const tailCheck = ny > 0.15 && ny < 0.45 && nx > -0.25 && nx < -0.05 &&
    (ny - 0.15) > (nx + 0.25) * 1.5

  if (bubbleRound || tailCheck) {
    return [255, 255, 255, 230]
  }

  // Three dots inside bubble
  const dotR = 0.06
  const dots = [[-0.2, -0.05], [0, -0.05], [0.2, -0.05]]
  for (const [dx, dy] of dots) {
    const dist = Math.sqrt(Math.pow(nx - dx, 2) + Math.pow(ny - dy, 2))
    if (dist < dotR) {
      return [r - 30, g - 30, b - 20, 255]
    }
  }

  return [r, g, b, 255]
}

// --- TabBar Icons (81x81) ---
function makeTabIcon(shape, color) {
  const [cr, cg, cb] = color
  return createPNG(81, 81, (x, y, w, h) => {
    const cx = w / 2, cy = h / 2
    const nx = (x - cx) / (w / 2), ny = (y - cy) / (h / 2)

    if (shape === 'agents') {
      // Robot head outline
      const headW = 0.55, headH = 0.50
      const headRound = Math.pow(nx / headW, 2) + Math.pow((ny + 0.05) / headH, 2)
      const inHead = headRound < 1 && headRound > 0.7
      // Antenna
      const antenna = Math.abs(nx) < 0.04 && ny > -0.75 && ny < -0.45
      const antennaTop = Math.sqrt(nx * nx + (ny + 0.78) * (ny + 0.78)) < 0.08
      // Eyes
      const eyeR = 0.08
      const leftEye = Math.sqrt(Math.pow(nx + 0.2, 2) + Math.pow(ny + 0.1, 2)) < eyeR
      const rightEye = Math.sqrt(Math.pow(nx - 0.2, 2) + Math.pow(ny + 0.1, 2)) < eyeR
      // Mouth
      const mouth = Math.abs(ny - 0.2) < 0.04 && Math.abs(nx) < 0.25

      if (antennaTop) return [cr, cg, cb, 255]
      if (antenna) return [cr, cg, cb, 220]
      if (leftEye || rightEye) return [cr, cg, cb, 255]
      if (mouth) return [cr, cg, cb, 200]
      if (inHead) return [cr, cg, cb, 255]
      return [0, 0, 0, 0]
    }

    if (shape === 'chat') {
      // Chat bubble
      const bw = 0.60, bh = 0.45
      const dist = Math.pow(nx / bw, 2) + Math.pow((ny + 0.08) / bh, 2)
      const inBubble = dist < 1 && dist > 0.65
      // Tail
      const tail = ny > 0.1 && ny < 0.55 && nx > -0.35 && nx < 0.0 &&
        (ny - 0.1) > (nx + 0.35) * 1.2
      const tailInner = ny > 0.15 && ny < 0.50 && nx > -0.30 && nx < -0.02 &&
        (ny - 0.15) > (nx + 0.30) * 1.2

      if (tail && !tailInner) return [cr, cg, cb, 255]
      if (inBubble) return [cr, cg, cb, 255]

      // Three lines inside
      const line1 = Math.abs(ny + 0.15) < 0.03 && Math.abs(nx) < 0.30
      const line2 = Math.abs(ny + 0.0) < 0.03 && Math.abs(nx) < 0.22
      const line3 = Math.abs(ny - 0.15) < 0.03 && Math.abs(nx) < 0.26
      if (line1 || line2 || line3) return [cr, cg, cb, 200]

      return [0, 0, 0, 0]
    }

    if (shape === 'profile') {
      // Person silhouette
      const headR = 0.22
      const headDist = Math.sqrt(nx * nx + (ny + 0.35) * (ny + 0.35))
      const inHead = headDist < headR && headDist > headR - 0.07

      // Body (arc)
      const bodyDist = Math.sqrt(nx * nx + (ny - 0.6) * (ny - 0.6))
      const inBody = bodyDist < 0.65 && bodyDist > 0.58 && ny > 0.0 && ny < 0.55

      // Fill body
      const bodyFill = bodyDist < 0.65 && ny > 0.05 && ny < 0.55 && Math.abs(nx) < 0.55

      if (inHead) return [cr, cg, cb, 255]
      // Head fill
      if (headDist < headR - 0.07) return [cr, cg, cb, 160]
      if (inBody || bodyFill) return [cr, cg, cb, 255]
      return [0, 0, 0, 0]
    }

    return [0, 0, 0, 0]
  })
}

// Generate all icons
console.log('Generating app icon...')
const logo = createPNG(1024, 1024, appIconPixel)
fs.writeFileSync(path.join(STATIC_DIR, 'icon', 'logo.png'), logo)
console.log('  -> src/static/icon/logo.png')

const tabIcons = [
  ['agents', [158, 158, 171]],  // #9e9eab
  ['agents-active', [79, 110, 247]],  // #4f6ef7
  ['chat', [158, 158, 171]],
  ['chat-active', [79, 110, 247]],
  ['profile', [158, 158, 171]],
  ['profile-active', [79, 110, 247]],
]

for (const [name, color] of tabIcons) {
  const shape = name.replace('-active', '')
  console.log(`Generating tab icon: ${name}...`)
  const png = makeTabIcon(shape, color)
  fs.writeFileSync(path.join(STATIC_DIR, 'tabbar', `${name}.png`), png)
  console.log(`  -> src/static/tabbar/${name}.png`)
}

console.log('All icons generated!')

// Reads a recording ZIP (Download ZIP on the server) without any library: the central directory gives each file's
// place, and DecompressionStream inflates deflated files. Plain ZIPs only (no ZIP64, no encryption).
// Used by the viewer (Open files…, also in the standalone file) and the Recordings and Replay pages (Import ZIP…,
// Download ZIP: makeZip below).

// Resolves to a Map of file name -> Blob, for files at the top level of the ZIP
async function readZip(blob) {
  const u32 = (v, o) => v.getUint32(o, true);
  const u16 = (v, o) => v.getUint16(o, true);
  // End of central directory record: in the last 64 KB + 22 bytes (after an optional comment)
  const tailSize = Math.min(blob.size, 65557);
  const tail = new DataView(await blob.slice(blob.size - tailSize).arrayBuffer());
  let eocd = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) {
    if (u32(tail, i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('This is not a ZIP file.');
  const count = u16(tail, eocd + 10);
  const dirSize = u32(tail, eocd + 12);
  const dirOffset = u32(tail, eocd + 16);
  if (dirOffset === 0xffffffff) throw new Error('This ZIP is too large (ZIP64) to open here.');
  const dir = new DataView(await blob.slice(dirOffset, dirOffset + dirSize).arrayBuffer());

  const files = new Map();
  for (let p = 0, i = 0; i < count; i++) {
    if (u32(dir, p) !== 0x02014b50) throw new Error('This ZIP is damaged.');
    const method = u16(dir, p + 10);
    const size = u32(dir, p + 20);
    const nameLen = u16(dir, p + 28);
    const extraLen = u16(dir, p + 30);
    const commentLen = u16(dir, p + 32);
    const local = u32(dir, p + 42);
    const name = new TextDecoder().decode(new Uint8Array(dir.buffer, dir.byteOffset + p + 46, nameLen));
    p += 46 + nameLen + extraLen + commentLen;
    if (name.includes('/')) continue; // only the top level

    // The data starts after the local header, whose name/extra lengths can differ from the central ones
    const head = new DataView(await blob.slice(local, local + 30).arrayBuffer());
    const start = local + 30 + u16(head, 26) + u16(head, 28);
    const data = blob.slice(start, start + size);
    if (method === 0) files.set(name, data);
    else if (method === 8) files.set(name, await new Response(data.stream().pipeThrough(new DecompressionStream('deflate-raw'))).blob());
    else throw new Error(`${name} in the ZIP uses a compression this viewer can't read.`);
  }
  return files;
}

// Makes a ZIP Blob from [{ name, blob, deflate }], the same layout a BlackBox server's Download ZIP uses.
// The video is stored as is (WebM is compressed already); JSON is deflated with CompressionStream.
// The parts are not copied: the result is a Blob made of headers and the original Blobs.
async function makeZip(files) {
  const enc = new TextEncoder();
  const parts = [];
  const central = [];
  let offset = 0;
  const { date, time } = dosTime(new Date());
  for (const f of files) {
    const name = enc.encode(f.name);
    const crc = await crc32(f.blob);
    const data = f.deflate ? await new Response(f.blob.stream().pipeThrough(new CompressionStream('deflate-raw'))).blob() : f.blob;
    const method = f.deflate ? 8 : 0;
    const local = new DataView(new ArrayBuffer(30));
    // signature, version 2.0, flags (bit 11: UTF-8 names), method, time, date, crc, sizes, name length, extra length
    [[0, 0x04034b50, 4], [4, 20, 2], [6, 0x0800, 2], [8, method, 2], [10, time, 2], [12, date, 2], [14, crc, 4],
      [18, data.size, 4], [22, f.blob.size, 4], [26, name.length, 2], [28, 0, 2]].forEach(([o, v, n]) => set(local, o, v, n));
    parts.push(local.buffer, name, data);

    const entry = new DataView(new ArrayBuffer(46));
    [[0, 0x02014b50, 4], [4, 20, 2], [6, 20, 2], [8, 0x0800, 2], [10, method, 2], [12, time, 2], [14, date, 2],
      [16, crc, 4], [20, data.size, 4], [24, f.blob.size, 4], [28, name.length, 2], [42, offset, 4]].forEach(([o, v, n]) => set(entry, o, v, n));
    central.push(entry.buffer, name);
    offset += 30 + name.length + data.size;
  }
  const dirSize = central.reduce((n, p) => n + p.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  [[0, 0x06054b50, 4], [8, files.length, 2], [10, files.length, 2], [12, dirSize, 4], [16, offset, 4]].forEach(([o, v, n]) => set(end, o, v, n));
  return new Blob([...parts, ...central, end.buffer], { type: 'application/zip' });

  function set(view, o, v, n) {
    if (n === 4) view.setUint32(o, v >>> 0, true); else view.setUint16(o, v, true);
  }
}

function dosTime(d) {
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}

let crcTable = null;
// CRC-32 of a Blob, read in chunks so a large video is never in memory at once
async function crc32(blob) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 0xffffffff;
  const reader = blob.stream().getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    for (let i = 0; i < value.length; i++) crc = crcTable[(crc ^ value[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

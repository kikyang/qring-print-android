/**
 * protocol.js —— Qring/BeePrt BY 私有协议（安卓版 QringProtocol.kt 的 JS 翻译）。
 *
 * 58mm 热敏头 384 点宽 = 48 字节/行，MSB first 置 1 = 黑。
 * 非标准 ESC/POS：状态/电量/浓度走私有 10 FF 命令，只有走纸 ESC J 和光栅 GS v 0 是标准。
 * 打印时序（X1 实测）：STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅 → 后走纸 → STOP → 等 ACK(0xAA)
 * 不要 ENABLE2（1F B2 10）——X1 固件不识别，渲染成「固」字乱码。
 */

const WIDTH_DOTS = 384;
const WIDTH_BYTES = 48;
const ACK_PRINT_DONE = 0xaa;
const FAULT_FRAME_HEAD = 0xff;

// ── 打印控制 ──
const CMD_ENABLE = [0x10, 0xff, 0xf1, 0x02];
const CMD_STOP = [0x10, 0xff, 0xf1, 0x45];
const CMD_WAKEUP = new Array(12).fill(0x00);
const CMD_ESC_INIT = [0x1b, 0x40];

// ── 查询 ──
const CMD_STATUS = [0x10, 0xff, 0x40];
const CMD_BATTERY = [0x10, 0xff, 0x50, 0xf1];
const CMD_DEVICE_INFO = [0x10, 0xff, 0x70];

/** 浓度：10 FF 10 00 n（X1 合法 0~2，2 最黑） */
function cmdThickness(level) {
  return [0x10, 0xff, 0x10, 0x00, Math.max(0, Math.min(255, level | 0))];
}

/** 走纸 ESC J n，>255 拆多条 */
function cmdFeed(dots) {
  const cmds = [];
  let remaining = Math.max(0, dots | 0);
  while (remaining > 0) {
    const n = Math.min(255, remaining);
    cmds.push([0x1b, 0x4a, n]);
    remaining -= n;
  }
  return cmds;
}

/** 光栅头 GS v 0 m xL xH yL yH */
function cmdRasterHeader(widthBytes, height, mode) {
  return [
    0x1d, 0x76, 0x30, mode & 0x03,
    widthBytes & 0xff, (widthBytes >> 8) & 0xff,
    height & 0xff, (height >> 8) & 0xff,
  ];
}

/**
 * 把 384 宽二值图（1D 数组，1=黑）编码为光栅字节流（48B/行，MSB first）。
 * @param {Uint8Array|number[]} binary 每像素 1 字节（0/1），长度 = 384*height
 */
function packRaster(binary, height) {
  const out = new Uint8Array(WIDTH_BYTES * height);
  for (let y = 0; y < height; y++) {
    const rowBase = y * WIDTH_DOTS;
    const outBase = y * WIDTH_BYTES;
    for (let x = 0; x < WIDTH_DOTS; x++) {
      if (binary[rowBase + x] === 1) {
        out[outBase + (x >> 3)] |= 0x80 >> (x & 7);
      }
    }
  }
  return out;
}

/** 故障码解析（FF xx 主动上报） */
const FAULT_LABELS = { 0x01: '缺纸', 0x02: '开盖', 0x03: '过热', 0x04: '低电量' };

module.exports = {
  WIDTH_DOTS, WIDTH_BYTES, ACK_PRINT_DONE, FAULT_FRAME_HEAD,
  CMD_ENABLE, CMD_STOP, CMD_WAKEUP, CMD_ESC_INIT,
  CMD_STATUS, CMD_BATTERY, CMD_DEVICE_INFO,
  cmdThickness, cmdFeed, cmdRasterHeader, packRaster, FAULT_LABELS,
};

/**
 * 小印 (Qring / BeePrt BY) 热敏打印机私有协议。
 * 来源:github.com/Thisko/QrintPrint (MIT) - 对 com.zxxk.xiaoyin.App 的逆向整理
 *
 * 注意:这不是标准 ESC/POS 状态协议。
 * 标准 ESC/POS 用 DLE EOT (10 04 n) 查状态、且没有电量指令;
 * Qring 用自己的 10 FF 系列命令,一个状态字节里同时带
 * 打印中/开盖/缺纸/低电压/过热五个位,并且有独立的电量查询。
 * 只有走纸 (ESC J) 和光栅位图 (GS v 0) 两条沿用了 ESC/POS。
 */

/** 58mm 热敏头点数 */
export const WIDTH_DOTS: number = 384;
/** 每行字节数 384/8 = 48,无补位 */
export const WIDTH_BYTES: number = 48;
/** SDK 单次 write 上限,超过要分包 */
export const CHUNK_SIZE: number = 1024;
/** 分包之间的间隔,照搬 SDK 行为 */
export const CHUNK_DELAY_MS: number = 1;

// ── 打印控制 ────────────────────────────────────────────────
export const CMD_ENABLE: ArrayBuffer = bytesOf([0x10, 0xFF, 0xF1, 0x02]);
export const CMD_ENABLE2: ArrayBuffer = bytesOf([0x1F, 0xB2, 0x10]);
export const CMD_STOP: ArrayBuffer = bytesOf([0x10, 0xFF, 0xF1, 0x45]);
/** 唤醒:12 个 0x00 */
export const CMD_WAKEUP: ArrayBuffer = new ArrayBuffer(12);

// ── 查询 ────────────────────────────────────────────────────
export const CMD_STATUS: ArrayBuffer = bytesOf([0x10, 0xFF, 0x40]);
export const CMD_BATTERY: ArrayBuffer = bytesOf([0x10, 0xFF, 0x50, 0xF1]);
export const CMD_MODEL: ArrayBuffer = bytesOf([0x10, 0xFF, 0x20, 0xF0]);
export const CMD_FW_VERSION: ArrayBuffer = bytesOf([0x10, 0xFF, 0x20, 0xF1]);
export const CMD_SN: ArrayBuffer = bytesOf([0x10, 0xFF, 0x20, 0xF2]);
export const CMD_BT_NAME: ArrayBuffer = bytesOf([0x10, 0xFF, 0x30, 0x11]);

/** 打印完成 ACK */
export const ACK_PRINT_DONE: number = 0xAA;
/** 主动上报帧头 */
export const FAULT_FRAME_HEAD: number = 0xFF;

// ── 状态字节位 ──────────────────────────────────────────────
export const ST_PRINTING: number = 0x01;
export const ST_COVER_OPEN: number = 0x02;
export const ST_NO_PAPER: number = 0x04;
export const ST_LOW_BATTERY: number = 0x08;
export const ST_OVERHEAT: number = 0x10;

/** FF xx 主动上报的故障码 */
export enum FaultCode {
  NO_PAPER = 0x01,
  COVER_OPEN = 0x02,
  OVERHEAT = 0x03,
  LOW_BATTERY = 0x04,
}

/** 状态字节为 0 表示一切正常 */
// parseStatus: raw & 0x01=打印中, 0x02=开盖, 0x04=缺纸, 0x08=低电, 0x10=过热

// ── 指令构造 ────────────────────────────────────────────────

/** 打印浓度 / 加热强度。APP 打文字用 1 */
export function cmdThickness(level: number): ArrayBuffer {
  return bytesOf([0x10, 0xFF, 0x10, 0x00, level]);
}

/** 自动关机时间,大端 16 位,单位秒 */
export function cmdShutdownTime(seconds: number): ArrayBuffer {
  return bytesOf([0x10, 0xFF, 0x12, (seconds / 256) & 0xFF, seconds % 256]);
}

/**
 * ESC J n —— 走纸 n 点行。
 * n 是单字节,超过 255 要拆成多条,所以返回数组。
 */
export function cmdFeed(dots: number): ArrayBuffer[] {
  const commands: ArrayBuffer[] = [];
  let remaining: number = dots;
  while (remaining > 0) {
    const n: number = Math.min(remaining, 255);
    commands.push(bytesOf([0x1B, 0x4A, n]));
    remaining -= n;
  }
  return commands;
}

/** GS v 0 —— 光栅位图头。data 紧跟其后单独发送 */
export function cmdRasterHeader(widthBytes: number, height: number, mode: number): ArrayBuffer {
  return bytesOf([
    0x1D, 0x76, 0x30, mode & 0x03,
    widthBytes % 256, (widthBytes / 256) & 0xFF,
    height % 256, (height / 256) & 0xFF,
  ]);
}

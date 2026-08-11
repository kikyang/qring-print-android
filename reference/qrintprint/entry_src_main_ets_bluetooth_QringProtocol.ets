/**
 * 小印 (Qring / BeePrt BY) 热敏打印机私有协议。
 *
 * 协议来自对 com.zxxk.xiaoyin.App 的逆向整理,仅供互操作参考。
 *
 * 注意:这**不是**标准 ESC/POS 状态协议。
 * 标准 ESC/POS 用 DLE EOT (10 04 n) 查状态、且没有电量指令;
 * Qring 用自己的 10 FF 系列命令,一个状态字节里同时带
 * 打印中/开盖/缺纸/低电压/过热五个位,并且有独立的电量查询。
 * 只有走纸 (ESC J) 和光栅位图 (GS v 0) 两条沿用了 ESC/POS。
 *
 * 本文件是纯协议层:只拼字节、解析字节,不碰 socket。
 */

/** 58mm 热敏头点数 */
export const WIDTH_DOTS: number = 384;
/** 每行字节数 384/8 = 48,无补位 */
export const WIDTH_BYTES: number = 48;
/** SDK 单次 write 上限,超过要分包 */
export const CHUNK_SIZE: number = 1024;
/** 分包之间的间隔,照搬 SDK 行为 */
export const CHUNK_DELAY_MS: number = 1;

export function bytesOf(values: number[]): ArrayBuffer {
  const buffer: ArrayBuffer = new ArrayBuffer(values.length);
  const view: Uint8Array = new Uint8Array(buffer);
  for (let i = 0; i < values.length; i++) {
    view[i] = values[i] & 0xFF;
  }
  return buffer;
}

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

export function faultLabel(code: number): string {
  switch (code) {
    case FaultCode.NO_PAPER:
      return '缺纸';
    case FaultCode.COVER_OPEN:
      return '开盖';
    case FaultCode.OVERHEAT:
      return '过热';
    case FaultCode.LOW_BATTERY:
      return '低电量';
    default:
      return `未知故障 (0x${code.toString(16)})`;
  }
}

/** 状态字节解析结果 */
export interface QringStatus {
  raw: number;
  printing: boolean;
  coverOpen: boolean;
  noPaper: boolean;
  lowBattery: boolean;
  overheat: boolean;
}

export function parseStatus(raw: number): QringStatus {
  return {
    raw: raw,
    printing: (raw & ST_PRINTING) !== 0,
    coverOpen: (raw & ST_COVER_OPEN) !== 0,
    noPaper: (raw & ST_NO_PAPER) !== 0,
    lowBattery: (raw & ST_LOW_BATTERY) !== 0,
    overheat: (raw & ST_OVERHEAT) !== 0,
  };
}

/** 状态字节为 0 表示一切正常 */
export function isStatusHealthy(status: QringStatus): boolean {
  return status.raw === 0;
}

/**
 * 打印前体检文案。返回 null 表示可以打印。
 *
 * 判断顺序是有讲究的:**开盖必须排在缺纸前面**。
 * 上盖打开时纸传感器看不到纸,会同时把缺纸位也置�
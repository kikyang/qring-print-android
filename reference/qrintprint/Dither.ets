/**
 * 图像抖动 (dithering)。
 *
 * 热敏头是 1-bit 输出,只能打黑或不打。单纯按阈值二值化会把所有中间灰度
 * 一刀切成纯黑纯白,照片就丢光了层次。抖动通过把量化误差扩散到邻近像素,
 * 用点阵的疏密在视觉上模拟灰阶。
 *
 * 纯计算模块,不依赖 ImageKit,方便单测。
 */

export enum DitherMode {
  /** 直接阈值二值化,不扩散误差。线稿/文字/二维码用这个最锐利 */
  NONE = 0,
  /** Floyd-Steinberg:经典误差扩散,层次最细腻,照片首选 */
  FLOYD_STEINBERG = 1,
  /** Atkinson:只扩散 6/8 误差,对比度更高、亮部更干净,早期 Mac 的做法 */
  ATKINSON = 2,
}

export interface DitherOption {
  mode: DitherMode;
  label: string;
  hint: string;
}

export const DITHER_OPTIONS: DitherOption[] = [
  { mode: DitherMode.NONE, label: '无', hint: '纯阈值 · 线稿/文字最锐利' },
  { mode: DitherMode.FLOYD_STEINBERG, label: 'Floyd', hint: 'Floyd-Steinberg · 层次细腻,照片首选' },
  { mode: DitherMode.ATKINSON, label: 'Atkinson', hint: 'Atkinson · 对比度更高,亮部更干净' },
];

/** 灰度图。data 长度 = width * height,取值 0(黑)~255(白) */
export interface GrayImage {
  data: Uint8Array;
  width: number;
  height: number;
}

/**
 * 误差扩散用的中点阈值。
 *
 * 抖动模式恒用 128:误差扩散的前提是量化点落在灰阶中点,
 * 用别的值(比如文字那套 212)会让整幅图整体压黑,失去抖动的意义。
 * 只有 NONE 模式才使用调用方传入的 threshold。
 */
const DITHER_PIVOT: number = 128;

/**
 * 灰度 → 二值。返回每像素 1 字节:1 = 黑(要打印),0 = 白。
 *
 * @param threshold 仅 DitherMode.NONE 生效;抖动模式固定用 128,见 DITHER_PIVOT。
 */
export function ditherToBinary(gray: GrayImage, mode: DitherMode, threshold: number): Uint8Array {
  const width: number = gray.width;
  const height: number = gray.height;
  const total: number = width * height;
  const out: Uint8Array = new Uint8Array(total);

  if (mode === DitherMode.NONE) {
    for (let i = 0; i < total; i++) {
      out[i] = gray.data[i] < threshold ? 1 : 0;
    }
    return out;
  }

  // 误差扩散会把值推到 0~255 之外,必须用带符号的浮点缓冲,不能原地改 Uint8Array
  const buffer: Float32Array = new Float32Array(total);
  for (let i = 0; i < total; i++) {
    buffer[i] = gray.data[i];
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const index: number = y * width + x;
      const oldValue: number = buffer[index];
      const newValue: number = oldValue < DITHER_PIVOT ? 0 : 255;
      out[index] = newValue === 0 ? 1 : 0;
      const error: number = oldValue - newValue;

      if (mode === DitherMode.FLOYD_STEINBERG) {
        //        X   7/16
        //  3/16 5/16 1/16
        if (x + 1 < width) {
          buffer[index + 1] += error * 7 / 16;
        }
        if (y + 1 < height) {
          if (x > 0) {
            buffer[index + width - 1] += error * 3 / 16;
          }
          buffer[index + width] += error * 5 / 16;
          if (x + 1 < width) {
            buffer[index + width + 1] += error * 1 / 16;
          }
        }
      } else {
        //       X   1/8  1/8
        //  1/8 1/8  1/8
        //       1/8
        // 只扩散 6/8,剩下 2/8 丢弃 —— 这正是 Atkinson 对比度更高的原因
        const share: number = error / 8;
        if (x + 1 < width) {
          buffer[index + 1] += share;
        }
        if (x + 2 < width) {
          buffer[index + 2] += share;
        }
        if (y + 1 < height) {
          if (x > 0) {
            buffer[index + width - 1] += share;
          }
          buffer[index + width] += share;
          if (x + 1 < width) {
            buffer[index + width + 1] += share;
          }
        }
        if (y + 2 < height) {
          buffer[index + 2 * width] += share;
        }
      }
    }
  }

  return out;
}

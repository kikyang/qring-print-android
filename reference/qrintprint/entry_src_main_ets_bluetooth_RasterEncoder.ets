import { image } from '@kit.ImageKit';
import { fileIo } from '@kit.CoreFileKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DitherMode, ditherToBinary, GrayImage } from './Dither';
import { WIDTH_BYTES, WIDTH_DOTS } from './QringProtocol';

const DOMAIN: number = 0x0001;
const TAG: string = 'RasterEncoder';

/** 图片二值化阈值,对应 Python 的 --threshold 默认值。仅 DitherMode.NONE 生效 */
export const THRESHOLD_IMAGE: number = 128;
/** 文字二值化阈值。APP 打文字用 212,比图片高很多,笔画才不会被吃掉 */
export const THRESHOLD_TEXT: number = 212;

export interface RasterData {
  data: ArrayBuffer;
  widthBytes: number;
  height: number;
}

/**
 * 从相册 URI 解码图片,并等比缩放到 384 点宽。
 *
 * PhotoViewPicker 返回的是 `file://media/Photo/...` 这类媒体库 URI,
 * image.createImageSource(uri) **处理不了**这种 URI(它认的是沙箱路径),
 * 必须先用 fileIo 打开成 fd 再喂给它。
 * (预览用的 Image 组件原生支持媒体库 URI,走的不是同一条路,所以预览正常不代表这里能解。)
 *
 * 直接用 desiredSize 在解码期缩放,比解码全尺寸再 scale 省内存 ——
 * 手机拍的照片动辄 4000px 宽,全解出来是几十 MB。
 */
export async function decodeImageToPrintWidth(uri: string): Promise<image.PixelMap> {
  let file: fileIo.File | null = null;
  let source: image.ImageSource | null = null;

  try {
    try {
      file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
      source = image.createImageSource(file.fd);
    } catch (fdErr) {
      // 兜底:沙箱内的普通文件路径可以直接按字符串解
      hilog.warn(DOMAIN, TAG, 'open by fd failed, fallback to uri: %{public}s',
        JSON.stringify(fdErr));
      source = image.createImageSource(uri);
    }

    if (source === null) {
      throw new Error('createImageSource 返回空');
    }

    const info: image.ImageInfo = await source.getImageInfo();
    const srcWidth: number = info.size.width;
    const srcHeight: number = info.size.height;
    if (srcWidth <= 0 || srcHeight <= 0) {
      throw new Error(`图片尺寸异常 ${srcWidth}x${srcHeight}`);
    }
    const targetHeight: number = Math.max(1, Math.round(srcHeight * WIDTH_DOTS / srcWidth));

    return await source.createPixelMap({
      desiredSize: { width: WIDTH_DOTS, height: targetHeight },
      desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
      editable: true,
    });
  } finally {
    if (source !== null) {
      source.release();
    }
    // PixelMap 解码时已把数据读进内存,此处关闭 fd 不影响已返回的位图
    if (file !== null) {
      try {
        fileIo.closeSync(file);
      } catch (closeErr) {
        hilog.warn(DOMAIN, TAG, 'close fd failed: %{public}s', JSON.stringify(closeErr));
      }
    }
  }
}

/**
 * PixelMap → 灰度图,**按原尺寸读取,不动传入的位图**。
 *
 * 画布合成必须用这个而不是 pixelMapToGray:后者遇到宽度 ≠ 384 会把传入的
 * PixelMap 原地放大到 384,画布上那些窄元素(缩小的图片、窄排的文字)会被强行拉满整幅宽。
 *
 * 透明像素按白色处理(SDK 里 alpha==0 视为不打印)。
 */
export async function pixelMapToGrayRaw(pixelMap: image.PixelMap): Promise<GrayImage> {
  const info: image.ImageInfo = await pixelMap.getImageInfo();
  const width: number = info.size.width;
  const height: number = info.size.height;
  const pixelBytes: number = pixelMap.getPixelBytesNumber();
  const buffer: ArrayBuffer = new ArrayBuffer(pixelBytes);
  await pixelMap.readPixelsToBuffer(buffer);

  const src: Uint8Array = new Uint8Array(buffer);
  const bytesPerPixel: number = Math.max(1, Math.floor(pixelBytes / (width * height)));
  const gray: Uint8Array = new Uint8Array(width * height);

  for (let i = 0; i < width * height; i++) {
    const p: number = i * bytesPerPixel;
    const r: number = src[p];
    const g: number = bytesPerPixel > 1 ? src[p + 1] : r;
    const b: number = bytesPerPixel > 2 ? src[p + 2] : r;
    const a: number = bytesPerPixel > 3 ? src[p + 3] : 255;

    // 先按白底合成 alpha,再转灰度
    const alpha: number = a / 255;
    const rr: number = r * alpha + 255 * (1 - alpha);
    const gg: number = g * alpha + 255 * (1 - alpha);
    const bb: number = b * alpha + 255 * (1 - alpha);
    gray[i] = Math.round(0.299 * rr + 0.587 * gg + 0.114 * bb);
  }

  return { data: gray, width: width, height: height };
}

/**
 * PixelMap → 灰度图,并把宽度归一到 384。
 *
 * 拆成独立一步是为了让抖动可以复用:切换抖动算法时不必重新解码图片,
 * 拿缓存的灰度数据重跑一遍算法即可。
 *
 * 注意:宽度不是 384 时会**原地缩放传入的 PixelMap**(副作用),
 * 且要求它 editable。整幅打印走这条;画布里的单个元素走 pixelMapToGrayRaw。
 */
export async function pixelMapToGray(pixelMap: image.PixelMap): Promise<GrayImage> {
  // 宽度对不上就先缩放 —— 离屏画布可能按 vp 出图,高密度屏上会是 384 的整数倍
  const info: image.ImageInfo = await pixelMap.getImageInfo();
  if (info.size.width !== WIDTH_DOTS && info.size.width > 0) {
    const ratio: number = WIDTH_DOTS / info.size.width;
    await pixelMap.scale(ratio, ratio);
  }
  return await pixelMapToGrayRaw(pixelMap);
}

/**
 * 二值数据 → 光栅字节。
 *
 * 编码规则与 com.beeprt.sdk.d.b(Bitmap,int,int) 一致:
 *   每行 48 字节,MSB first(bit7 = 最左像素),置 1 = 黑。
 */
export function packBinaryToRaster(binary: Uint8Array, width: number, height: number): RasterData {
  const out: ArrayBuffer = new ArrayBuffer(WIDTH_BYTES * height);
  const dst: Uint8Array = new Uint8Array(out);
  // 超出 384 的列直接丢弃,不足的列留白(dst 初始全 0 = 白)
  const limit: number = Math.min(width, WIDTH_DOTS);

  for (let y = 0; y < height; y++) {
    const rowBase: number = y * width;
    const outBase: number = y * WIDTH_BYTES;
    for (let x = 0; x < limit; x++) {
      if (binary[rowBase + x] === 1) {
        dst[outBase + (x >> 3)] |= (0x80 >> (x & 7));
      }
    }
  }

  return { data: out, widthBytes: WIDTH_BYTES, height: height };
}

/** 便捷封装:PixelMap → 光栅。文字打印走这条,固定用纯阈值不抖动 */
export async function pixelMapToRaster(
  pixelMap: image.PixelMap, threshold: number): Promise<RasterData> {
  const gray: GrayImage = await pixelMapToGray(pixelMap);
  const binary: Uint8Array = ditherToBinary(gray, DitherMode.NONE, threshold);
  return packBinaryToRaster(binary, gray.width, gray.height);
}

/**
 * 二值数据 → 可显示的 PixelMap,用于预览「实际会打印成什么样」。
 *
 * 黑白两色在 RGBA 和 BGRA 下字节完全相同,
 * 所以不用纠结 createPixelMap 的输入通道序。
 */
/**
 * 二值数据 → 可显示的 PixelMap。
 *
 * transparentWhite = true 时,白像素(二值 0)输出为全透明。
 * 画布上的文字元素用它:文字本身是黑点,白底变成透明,元素框不再是一块白块,
 * 底下画布的白纸透出来,文字像是直接印在纸上。
 * 默认 false(白=不透明白),整幅打印预览等场景需要真实的白色背景。
 */
export async function binaryToPreviewPixelMap(
  binary: Uint8Array, width: number, height: number,
  transparentWhite?: boolean): Promise<image.PixelMap> {
  const buffer: ArrayBuffer = new ArrayBuffer(width * height * 4);
  const view: Uint8Array = new Uint8Array(buffer);

  for (let i = 0; i < width * height; i++) {
    const value: number = binary[i] === 1 ? 0 : 255;
    const p: number = i * 4;
    view[p] = value;
    view[p + 1] = value;
    view[p + 2] = value;
    view[p + 3] = (transparentWhite === true && binary[i] === 0) ? 0 : 255;
  }

  return await image.createPixelMap(buffer, {
    size: { width: width, height: height },
    pixelFormat: image.PixelMapFormat.RGBA_8888,
    editable: false,
  });
}

export interface TextRenderOptions {
  /** 字体族。取自系统字体列表,含空格的族名会自动加引号 */
  fontFamily: string;
  fontSize: number;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  /** 字间距,单位:打印点(1 点 = 1/8 mm) */
  letterSpacing: number;
  /** 行间距,叠加在字号之上 */
  lineSpacing: number;
  margin: number;
}

export const DEFAULT_TEXT_OPTIONS: TextRenderOptions = {
  fontFamily: 'sans-serif',
  fontSize: 24,
  bold: false,
  italic: false,
  underline: false,
  letterSpacing: 0,
  lineSpacing: 6,
  margin: 8,
};

/**
 * 拼 Canvas 的 font 简写:`[italic] [bold] <size>px <family>`。
 * 族名含空格必须加引号(比如 HarmonyOS Sans),否则解析会断在空格处。
 */
function buildFontSpec(options: TextRenderOptions): string {
  const style: string = options.italic ? 'italic ' : '';
  const weight: string = options.bold ? 'bold ' : '';
  const family: string = options.fontFamily.indexOf(' ') >= 0
    ? `"${options.fontFamily}"` : options.fontFamily;
  return `${style}${weight}${options.fontSize}px ${family}`;
}

/** 按可用宽度逐字符折行。中文没有词边界,只能按字符量宽度 */
function wrapText(
  text: string, ctx: OffscreenCanvasRenderingContext2D, usable: number): string[] {
  const lines: string[] = [];
  const paragraphs: string[] = text.split('\n');

  for (const paragraph of paragraphs) {
    if (paragraph.length === 0) {
      lines.push('');
      continue;
    }
    let current: string = '';
    for (const ch of paragraph) {
      const candidate: string = current + ch;
      if (ctx.measureText(candidate).width <= usable) {
        current = candidate;
      } else {
        lines.push(current);
        current = ch;
      }
    }
    lines.push(current);
  }
  return lines;
}

/**
 * 文本 → 384 点宽位图,自动换行。对应 Python 的 text_to_image,并扩展了排版选项。
 *
 * 用 LengthMetricsUnit.PX 建离屏画布:默认单位是 vp,
 * 3 倍屏上 384vp 会画成 1152px,出来的图就不是 384 点宽了。
 * (即便如此,pixelMapToGray 里仍有一道宽度兜底缩放。)
 */
export function renderTextToPixelMap(
  text: string, options: TextRenderOptions): image.PixelMap {
  return renderTextToPixelMapIn(text, options, WIDTH_DOTS);
}

/**
 * 量出文本在 maxWidth 内排版后的**内容自然宽度**。
 *
 * 文字元素默认不该占满整幅 384 —— 只有一行时宽度就是这行文字 + 两边边距,
 * 多行(手动 \n 或超宽折行)时取最长一行的宽度。上限 maxWidth,不超纸宽。
 *
 * 和 renderTextToPixelMapIn 用同一套折行逻辑,保证「量出来的宽度」和「按这个宽度
 * 渲染出来的结果」一致。
 */
export function measureTextContentWidth(
  text: string, options: TextRenderOptions, maxWidth: number): number {
  const width: number = Math.max(1 + 2 * options.margin, Math.round(maxWidth));
  const settings: RenderingContextSettings = new RenderingContextSettings(true);
  const fontSpec: string = buildFontSpec(options);
  const spacing: string = `${options.letterSpacing}px`;
  const usable: number = width - 2 * options.margin;

  const probe: OffscreenCanvasRenderingContext2D =
    new OffscreenCanvasRenderingContext2D(width, 1, settings, LengthMetricsUnit.PX);
  probe.font = fontSpec;
  probe.letterSpacing = spacing;
  const lines: string[] = wrapText(text, probe, usable);

  let widest: number = 0;
  for (const line of lines) {
    const w: number = probe.measureText(line).width;
    if (w > widest) {
      widest = w;
    }
  }
  // 至少留一个字的可画空间,避免内容再宽也被 clamp 死
  const content: number = Math.max(options.fontSize, Math.ceil(widest));
  return Math.min(width, content + 2 * options.margin);
}

/**
 * 同上,但可指定排版宽度。
 *
 * 自定义画布里的文字元素不一定占满整幅宽 —— 放在 x=200 处的文字只能用剩下的 184 点,
 * 折行必须按这个宽度算,否则会排到纸外面去被裁掉。
 *
 * boxWidth 是**元素的总宽**,margin 在它内部再往里收。
 */
export function renderTextToPixelMapIn(
  text: string, options: TextRenderOptions, boxWidth: number): image.PixelMap {

  // 至少留一列可画,否则 measureText 会在负宽度上死循环折行
  const width: number = Math.max(1 + 2 * options.margin, Math.round(boxWidth));
  const settings: RenderingContextSettings = new RenderingContextSettings(true);
  const fontSpec: string = buildFontSpec(options);
  const spacing: string = `${options.letterSpacing}px`;
  const usable: number = width - 2 * options.margin;

  // 先用一个 1 像素高的画布量文本宽度,决定怎么折行。
  // letterSpacing 必须在 measureText 之前设好 —— 它会计入测量结果,
  // 漏设的话字间距一拉大就会折行不准、右边溢出。
  const probe: OffscreenCanvasRenderingContext2D =
    new OffscreenCanvasRenderingContext2D(width, 1, settings, LengthMetricsUnit.PX);
  probe.font = fontSpec;
  probe.letterSpacing = spacing;
  const lines: string[] = wrapText(text, probe, usable);

  const lineHeight: number = options.fontSize + options.lineSpacing;
  // 下划线粗细随字号缩放,固定 1px 在大字号下会细得几乎打不出来
  const underlineWeight: number = Math.max(1, Math.round(options.fontSize / 14));
  /**
   * 末行之后不再多留一个行距。
   *
   * lineHeight * lines.length 会把最后一行的行距也算进去,
   * 结果下边距变成 margin + lineSpacing,和上边距不对称 —— 白白多走一截纸。
   * 正确的文本高度是「首行字高 + 其余行各推进一个 lineHeight」。
   * 带下划线时补上它自身占的那几个像素,否则末行下划线会被裁掉。
   */
  const underlineExtra: number = options.underline ? 2 + underlineWeight : 0;
  const textHeight: number =
    options.fontSize + Math.max(0, lines.length - 1) * lineHeight + underlineExtra;
  const height: number = Math.max(1, options.margin * 2 + textHeight);

  const ctx: OffscreenCanvasRenderingContext2D =
    new OffscreenCanvasRenderingContext2D(width, height, settings, LengthMetricsUnit.PX);

  // 必须先铺白底:画布默认透明,透明会被当成白,但显式填充更稳
  ctx.fillStyle = '#FFFFFF';
  ctx.fillRect(0, 0, width, height);

  ctx.fillStyle = '#000000';
  ctx.font = fontSpec;
  ctx.letterSpacing = spacing;
  ctx.textBaseline = 'top';

  for (let i = 0; i < lines.length; i++) {
    const y: number = options.margin + i * lineHeight;
    ctx.fillText(lines[i], options.margin, y);

    // Canvas 2D 没有原生下划线,只能自己量出行宽再画一条实心矩形
    if (options.underline && lines[i].length > 0) {
      const lineWidth: number = ctx.measureText(lines[i]).width;
      ctx.fillRect(options.margin, y + options.fontSize + 2, lineWidth, underlineWeight);
    }
  }

  hilog.info(DOMAIN, TAG, 'rendered %{public}d lines, height %{public}d', lines.length, height);
  return ctx.getPixelMap(0, 0, width, height);
}

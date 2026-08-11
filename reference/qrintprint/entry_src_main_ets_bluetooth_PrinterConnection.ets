import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { connection, socket } from '@kit.ConnectivityKit';
import { image } from '@kit.ImageKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  applyQringStatus,
  ConnState,
  getPrinterStatus,
  HardwareState,
  PaperState,
  PrinterStatus,
} from '../model/PrinterStatus';
import { matchesDeviceFilter } from './PrinterDiscovery';
import {
  ACK_PRINT_DONE,
  CHUNK_DELAY_MS,
  CHUNK_SIZE,
  CMD_BATTERY,
  CMD_ENABLE,
  CMD_ENABLE2,
  CMD_FW_VERSION,
  CMD_MODEL,
  CMD_STATUS,
  CMD_STOP,
  CMD_WAKEUP,
  cmdFeed,
  cmdRasterHeader,
  cmdThickness,
  FAULT_FRAME_HEAD,
  faultLabel,
  faultMessage,
  parseStatus,
  QringStatus,
} from './QringProtocol';
import { pixelMapToRaster, RasterData } from './RasterEncoder';

const DOMAIN: number = 0x0001;
const TAG: string = 'PrinterConnection';

/** 串口服务标准 UUID,经典蓝牙 SPP 固定用这个 */
const SPP_UUID: string = '00001101-0000-1000-8000-00805f9b34fb';

const PREF_NAME: string = 'qringprint_printer';
const KEY_LAST_DEVICE: string = 'lastDeviceId';

/** 状态轮询间隔 */
const POLL_INTERVAL_MS: number = 10000;
/** 查询响应等待上限 */
const QUERY_TIMEOUT_MS: number = 1500;
/** 发命令后等打印机准备响应的时间,照搬 SDK */
const QUERY_SETTLE_MS: number = 150;
/** 等打印完成 ACK 的上限 */
const ACK_TIMEOUT_MS: number = 120000;

/** 打印前后走纸点行,对应 Python 的 feed_before / feed_after */
const FEED_BEFORE: number = 10;
const FEED_AFTER: number = 100;

export interface PrintResult {
  ok: boolean;
  message: string;
}

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve: () => void) => {
    setTimeout(() => {
      resolve();
    }, ms);
  });
}

/**
 * Qring / BeePrt 打印机连接管理。全局单例 —— 同一时刻只连一台。
 *
 * 职责边界:
 *   本类           —— socket 生命周期、分包收发、查询/ACK 时序、轮询调度、持久化
 *   QringProtocol  —— 纯协议,拼字节 / 解析位
 *   RasterEncoder  —— 图像与文本 → 光栅字节
 *   UI             —— 只读 PrinterStatus,或调本类的高层打印方法
 *
 * 所有蓝牙 API 调用都包了 try/catch:Previewer 里没有蓝牙能力,
 * 不吞异常整个页面会白屏,界面就没法预览了。
 */
export class PrinterConnection {
  private static instance: PrinterConnection | null = null;

  private status: PrinterStatus = getPrinterStatus();
  private context: common.UIAbilityContext | null = null;
  private clientSocket: number | null = null;
  private pollTimer: number = -1;
  private foreground: boolean = true;
  /** 打印任务进行中 —— 期间暂停状态轮询,避免查询字节混进打印流 */
  private busy: boolean = false;

  /** 滚动接收缓冲。响应长度不定,还会随时插入 FF xx 主动上报帧 */
  private rxBuffer: number[] = [];

  static getInstance(): PrinterConnection {
    if (PrinterConnection.instance === null) {
      PrinterConnection.instance = new PrinterConnection();
    }
    return PrinterConnection.instance;
  }

  /** 具名回调,off() 时要用同一个引用 */
  private handleRead: (data: ArrayBuffer) => void = (data: ArrayBuffer) => {
    const bytes: Uint8Array = new Uint8Array(data);
    for (let i = 0; i < bytes.length; i++) {
      this.rxBuffer.push(bytes[i]);
    }
    // 缓冲兜底,防止长时间不读导致无限增长
    if (this.rxBuffer.length > 4096) {
      this.rxBuffer.splice(0, this.rxBuffer.length - 4096);
    }
  };

  // ── 生命周期 ──────────────────────────────────────────────

  init(context: common.UIAbilityContext): void {
    this.context = context;
    // 刻意不订阅 connection.on('batteryChange'):
    // 那是平台级 HFP/HID 电量通道,这台机器不走那条,只会回 -1,
    // 反而会把私有协议 10 FF 50 F1 查到的真实电量覆盖掉。
  }

  onForeground(): void {
    this.foreground = true;
    if (this.isAlive() && this.pollTimer < 0) {
      this.startPolling();
    }
  }

  onBackground(): void {
    this.foreground = false;
    this.stopPolling();
  }

  isAlive(): boolean {
    if (this.clientSocket === null) {
      return false;
    }
    try {
      return socket.isConnected(this.clientSocket);
    } catch (err) {
      return false;
    }
  }

  isBusy(): boolean {
    return this.busy;
  }

  // ── 连接 / 断开 ───────────────────────────────────────────

  async connect(deviceId: string): Promise<boolean> {
    this.disconnect();

    this.status.deviceId = deviceId;
    this.status.deviceName = this.resolveName(deviceId);
    this.status.connState = ConnState.CONNECTING;
    this.status.lastError = '';

    const options: socket.SppOptions = {
      uuid: SPP_UUID,
      secure: true,
      type: socket.SppType.SPP_RFCOMM,
    };

    const connected: boolean = await new Promise<boolean>((resolve: (ok: boolean) => void) => {
      try {
        socket.sppConnect(deviceId, options, (err: BusinessError, clientSocket: number) => {
          if (err) {
            hilog.error(DOMAIN, TAG, 'sppConnect failed: %{public}s', JSON.stringify(err));
            this.status.connState = ConnState.DISCONNECTED;
            this.status.lastError = `连接失败(错误码 ${err.code})`;
            resolve(false);
            return;
          }
          this.clientSocket = clientSocket;
          try {
            socket.on('sppRead', clientSocket, this.handleRead);
          } catch (subErr) {
            hilog.warn(DOMAIN, TAG, 'sppRead subscribe failed: %{public}s', JSON.stringify(subErr));
          }
          this.status.connState = ConnState.CONNECTED;
          resolve(true);
        });
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'sppConnect threw: %{public}s', JSON.stringify(err));
        this.status.connState = ConnState.DISCONNECTED;
        this.status.lastError = '当前环境不支持蓝牙';
        resolve(false);
      }
    });

    if (!connected) {
      return false;
    }

    this.persistDeviceId(deviceId);
    await this.refreshAll();
    this.queryDeviceInfo();
    if (this.foreground) {
      this.startPolling();
    }
    return true;
  }

  disconnect(): void {
    this.stopPolling();
    this.rxBuffer = [];
    this.busy = false;

    const activeSocket: number | null = this.clientSocket;
    this.clientSocket = null;

    if (activeSocket !== null) {
      try {
        socket.off('sppRead', activeSocket, this.handleRead);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'sppRead off failed: %{public}s', JSON.stringify(err));
      }
      try {
        socket.sppCloseClientSocket(activeSocket);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'close socket failed: %{public}s', JSON.stringify(err));
      }
    }

    this.status.connState = ConnState.DISCONNECTED;
    // 断开后必须清掉读数,否则会残留上次连接的纸张/电量,变成假数据
    this.status.paperState = PaperState.UNKNOWN;
    this.status.hardwareState = HardwareState.UNKNOWN;
    this.status.batteryPercent = null;
    this.status.printing = false;
  }

  /** 冷启动静默重连上次用过的设备。失败不弹任何提示,用户点卡片时再手动选 */
  async autoReconnect(): Promise<void> {
    const deviceId: string = await this.loadDeviceId();
    if (deviceId.length === 0) {
      return;
    }
    try {
      const paired: Array<string> = connection.getPairedDevices();
      if (paired.indexOf(deviceId) < 0) {
        return;
      }
    } catch (err) {
      return;
    }
    // 与设备列表用同一套过滤规则:万一过滤前缀改了,
    // 别让历史保存的 deviceId 把一台已不匹配的设备重新连上
    if (!matchesDeviceFilter(this.resolveName(deviceId))) {
      return;
    }
    await this.connect(deviceId);
  }

  // ── 底层收发 ──────────────────────────────────────────────

  /** 按 SDK 的方式分包:每 1024 字节一包,包间 1ms */
  private async send(data: ArrayBuffer): Promise<boolean> {
    const activeSocket: number | null = this.clientSocket;
    if (activeSocket === null) {
      return false;
    }
    const total: number = data.byteLength;
    for (let offset = 0; offset < total; offset += CHUNK_SIZE) {
      const end: number = Math.min(offset + CHUNK_SIZE, total);
      try {
        await socket.sppWriteAsync(activeSocket, data.slice(offset, end));
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'sppWrite failed: %{public}s', JSON.stringify(err));
        return false;
      }
      await sleep(CHUNK_DELAY_MS);
    }
    return true;
  }

  private async sendAll(commands: ArrayBuffer[]): Promise<boolean> {
    for (const command of commands) {
      if (!await this.send(command)) {
        return false;
      }
    }
    return true;
  }

  /** 等至少 n 字节。超时就把已收到的返回(可能不足 n) */
  private async waitBytes(n: number, timeoutMs: number): Promise<number[]> {
    const deadline: number = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.rxBuffer.length >= n) {
        return this.rxBuffer.splice(0, n);
      }
      await sleep(20);
    }
    return this.rxBuffer.splice(0, this.rxBuffer.length);
  }

  /** 清空输入 → 发命令 → 稍等 → 读响应。这是 SDK 的固定套路 */
  private async query(command: ArrayBuffer, nbytes: number): Promise<number[]> {
    this.rxBuffer = [];
    if (!await this.send(command)) {
      return [];
    }
    await sleep(QUERY_SETTLE_MS);
    return await this.waitBytes(nbytes, QUERY_TIMEOUT_MS);
  }

  /** 等打印完成 ACK (0xAA),同时盯着 FF xx 故障帧 */
  private async waitAck(timeoutMs: number): Promise<PrintResult> {
    const deadline: number = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.rxBuffer.indexOf(ACK_PRINT_DONE) >= 0) {
        this.rxBuffer = [];
        return { ok: true, message: '打印完成' };
      }
      for (let i = 0; i + 1 < this.rxBuffer.length; i++) {
        if (this.rxBuffer[i] === FAULT_FRAME_HEAD) {
          const code: number = this.rxBuffer[i + 1];
          if (code >= 0x01 && code <= 0x04) {
            this.rxBuffer = [];
            return { ok: false, message: faultLabel(code) };
          }
        }
      }
      await sleep(100);
    }
    return { ok: false, message: '等待打印完成超时' };
  }

  // ── 查询 ──────────────────────────────────────────────────

  async queryStatus(): Promise<QringStatus | null> {
    const response: number[] = await this.query(CMD_STATUS, 1);
    if (response.length < 1) {
      return null;
    }
    return parseStatus(response[0]);
  }

  /** 电量:响应 2 字节,第 2 字节才是百分比 */
  async queryBattery(): Promise<number | null> {
    const response: number[] = await this.query(CMD_BATTERY, 2);
    if (response.length < 2) {
      return null;
    }
    return response[1];
  }

  /**
   * 字符串类查询。Python 用 gb2312 解码,这里只按可打印 ASCII 取 ——
   * 型号 / 固件版本实测都是 ASCII,避免为此引入编码依赖。
   */
  private async queryString(command: ArrayBuffer): Promise<string> {
    const response: number[] = await this.query(command, 64);
    let text: string = '';
    for (const byte of response) {
      if (byte >= 0x20 && byte < 0x7F) {
        text += String.fromCharCode(byte);
      }
    }
    return text.trim();
  }

  async queryDeviceInfo(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.status.model = await this.queryString(CMD_MODEL);
    this.status.firmware = await this.queryString(CMD_FW_VERSION);
  }

  /** 查一轮状态 + 电量,写回全局状态 */
  async refreshAll(): Promise<void> {
    const status: QringStatus | null = await this.queryStatus();
    if (status !== null) {
      applyQringStatus(this.status, status);
    }
    const battery: number | null = await this.queryBattery();
    if (battery !== null) {
      this.status.batteryPercent = battery;
    }
  }

  /**
   * 打印前体检。返回故障文案,null 表示可以打印。
   *
   * 这里**现查一次**而不是读轮询的缓存值 —— 轮询间隔 10s,
   * 用户完全可能刚掀开上盖或刚把纸用完就点了打印,缓存值是过期的。
   *
   * 查不到状态(打印机没回包)时返回 null 放行:
   * 宁可让打印去试一次、失败时由 ACK 阶段的故障帧兜住,
   * 也不要因为一次查询超时就把用户拦在门外。
   */
  async preflightCheck(): Promise<string | null> {
    if (!this.isAlive()) {
      return '打印机未连接';
    }
    const status: QringStatus | null = await this.queryStatus();
    if (status === null) {
      return null;
    }
    applyQringStatus(this.status, status);
    return faultMessage(status);
  }

  // ── 打印 ──────────────────────────────────────────────────

  /**
   * 打印一张已经转好的光栅位图。
   * 时序照搬 Python 的 print_image:
   *   enable → thickness → wakeup → feed(前) → 光栅 → feed(后) → stop → 等 ACK
   */
  async printRaster(raster: RasterData, thickness: number | null): Promise<PrintResult> {
    if (!this.isAlive()) {
      return { ok: false, message: '打印机未连接' };
    }
    if (this.busy) {
      return { ok: false, message: '上一个打印任务还没结束' };
    }

    this.busy = true;
    // 打印期间停掉轮询,别让状态查询的字节混进打印数据流
    this.stopPolling();
    this.rxBuffer = [];

    try {
      if (!await this.sendAll([CMD_ENABLE, CMD_ENABLE2])) {
        return { ok: false, message: '发送失败,连接可能已断开' };
      }
      if (thickness !== null) {
        await this.send(cmdThickness(thickness));
      }
      await this.send(CMD_WAKEUP);
      await this.sendAll(cmdFeed(FEED_BEFORE));

      await this.send(cmdRasterHeader(raster.widthBytes, raster.height, 0));
      if (!await this.send(raster.data)) {
        return { ok: false, message: '位图发送中断' };
      }

      await this.sendAll(cmdFeed(FEED_AFTER));
      await this.send(CMD_STOP);

      this.status.printing = true;
      const result: PrintResult = await this.waitAck(ACK_TIMEOUT_MS);
      this.status.printing = false;
      if (!result.ok) {
        this.status.lastError = result.message;
      }
      return result;
    } finally {
      this.busy = false;
      // 打完刷新一次状态,纸张/电量会有变化
      await this.refreshAll();
      if (this.foreground && this.isAlive()) {
        this.startPolling();
      }
    }
  }

  /** 打印 PixelMap(图片或文本渲染结果) */
  async printPixelMap(pixelMap: image.PixelMap, threshold: number,
    thickness: number | null): Promise<PrintResult> {
    try {
      const raster: RasterData = await pixelMapToRaster(pixelMap, threshold);
      return await this.printRaster(raster, thickness);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'raster encode failed: %{public}s', JSON.stringify(err));
      return { ok: false, message: '图像转换失败' };
    }
  }

  // ── 状态轮询 ──────────────────────────────────────────────

  private startPolling(): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      this.pollOnce();
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private async pollOnce(): Promise<void> {
    if (this.clientSocket === null || this.busy) {
      return;
    }
    await this.refreshAll();
  }

  // ── 工具 ──────────────────────────────────────────────────

  private resolveName(deviceId: string): string {
    try {
      const name: string = connection.getRemoteDeviceName(deviceId);
      return name.length > 0 ? name : deviceId;
    } catch (err) {
      return deviceId;
    }
  }

  private async persistDeviceId(deviceId: string): Promise<void> {
    if (this.context === null) {
      return;
    }
    try {
      const store: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      await store.put(KEY_LAST_DEVICE, deviceId);
      await store.flush();
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'persist deviceId failed: %{public}s', JSON.stringify(err));
    }
  }

  private async loadDeviceId(): Promise<string> {
    if (this.context === null) {
      return '';
    }
    try {
      const store: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      return (await store.get(KEY_LAST_DEVICE, '')) as string;
    } catch (err) {
      return '';
    }
  }
}

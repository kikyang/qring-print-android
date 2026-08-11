/**
 * ble.js —— 错题小印 X1 BLE 透传连接层（安卓版 BlePrinterConnection 的 JS 翻译）。
 *
 * X1 透传通道：服务 0000ff00-…，写特征 0000ff02-…，通知特征 0000ff01-…。
 * 微信限制：仅 BLE（无 SPP）；writeBLECharacteristicValue 默认 20B/包，
 * 用 setBLEMTU 协商提升（安卓端可到 500+，协商失败回退 20B 慢速）。
 * 透传芯片缓冲小：分包 + 包间间隔防丢包（安卓实测 32B/80ms；小程序按 MTU 折算）。
 */

const P = require('./protocol');

const SERVICE_UUID = '0000FF00-0000-1000-8000-00805F9B34FB';
const WRITE_UUID = '0000FF02-0000-1000-8000-00805F9B34FB';
const NOTIFY_UUID = '0000FF01-0000-1000-8000-00805F9B34FB';

/** 设备名前缀（同安卓 DEVICE_NAME_PREFIXES） */
const NAME_PREFIXES = ['Qring', 'qring', 'BY-288', 'BY288', 'Beeprt', 'FlashToy', 'F2'];

class BlePrinter {
  constructor() {
    this.deviceId = '';
    this.connected = false;
    this.writeCharId = '';
    this.notifyCharId = '';
    this.mtu = 20;            // 协商后的单包上限（默认 20B）
    this.packetDelay = 80;    // 包间间隔 ms（安卓实测 32B/80ms 稳定）
    this.rxBuffer = [];       // 滚动接收缓冲（通知回调入队）
    this.listeners = { connected: [], disconnected: [], status: [], battery: [] };
  }

  on(event, cb) { this.listeners[event]?.push(cb); }

  _emit(event, ...args) { this.listeners[event]?.forEach((cb) => cb(...args)); }

  /** Promise 包装微信 API（reject 带错误信息） */
  _call(name, options = {}) {
    return new Promise((resolve, reject) => {
      wx[name]({
        ...options,
        success: resolve,
        fail: (err) => reject(new Error(`${name}: ${err.errMsg || JSON.stringify(err)}`)),
      });
    });
  }

  /** 扫描附近的 Qring 打印机（返回设备列表） */
  async scan(timeoutMs = 10000) {
    await this._call('openBluetoothAdapter');
    const found = [];
    await new Promise((resolve) => {
      wx.onBluetoothDeviceFound((res) => {
        for (const dev of res.devices) {
          const name = dev.name || dev.localName || '';
          if (NAME_PREFIXES.some((p) => name.startsWith(p))) {
            if (!found.some((f) => f.deviceId === dev.deviceId)) found.push(dev);
          }
        }
      });
      setTimeout(resolve, timeoutMs);
    });
    await this._call('stopBluetoothDevicesDiscovery');
    return found;
  }

  /** 连接并定位特征，协商 MTU */
  async connect(device) {
    this.deviceId = device.deviceId;
    await this._call('createBLEConnection', { deviceId: this.deviceId });
    // 服务发现（轮询等待，微信有时延迟）
    for (let i = 0; i < 10; i++) {
      try {
        const res = await this._call('getBLEDeviceServices', { deviceId: this.deviceId });
        const svc = res.services.find((s) => s.uuid.toUpperCase() === SERVICE_UUID);
        if (svc) {
          const chars = await this._call('getBLEDeviceCharacteristics', {
            deviceId: this.deviceId, serviceId: svc.uuid,
          });
          const wc = chars.characteristics.find((c) => c.uuid.toUpperCase() === WRITE_UUID);
          const nc = chars.characteristics.find((c) => c.uuid.toUpperCase() === NOTIFY_UUID);
          if (wc && nc) {
            this.writeCharId = wc.uuid;
            this.notifyCharId = nc.uuid;
            // 订阅通知
            await this._call('notifyBLECharacteristicValueChange', {
              deviceId: this.deviceId, serviceId: svc.uuid,
              characteristicId: nc.uuid, state: true,
            });
            wx.onBLECharacteristicValueChange((res) => {
              if (res.characteristicId.toUpperCase() === NOTIFY_UUID) {
                const bytes = Array.from(new Uint8Array(res.value));
                this.rxBuffer.push(...bytes);
              }
            });
            // 尽力提升 MTU（失败回退 20B）
            try {
              const m = await this._call('setBLEMTU', { deviceId: this.deviceId, mtu: 517 });
              this.mtu = Math.max(20, m.mtu || 20);
            } catch (e) {
              this.mtu = 20;
            }
            this.connected = true;
            this._emit('connected', device);
            return true;
          }
        }
      } catch (e) { /* 服务未就绪，继续轮询 */ }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error('未找到透传服务（FF00/FF02/FF01），可能不是 BLE 透传版 X1');
  }

  async disconnect() {
    if (this.deviceId) {
      try { await this._call('closeBLEConnection', { deviceId: this.deviceId }); } catch (e) {}
    }
    this.connected = false;
    this.rxBuffer = [];
    this._emit('disconnected');
  }

  /** 发字节（分包 + 间隔；透传芯片消化慢，宁可慢不能丢） */
  async send(bytes) {
    if (!this.connected) throw new Error('打印机未连接');
    const chunkSize = Math.min(this.mtu, 512); // 单包上限（特征写通常限制 512）
    for (let off = 0; off < bytes.length; off += chunkSize) {
      const chunk = bytes.slice(off, off + chunkSize);
      await this._call('writeBLECharacteristicValue', {
        deviceId: this.deviceId,
        serviceId: SERVICE_UUID,
        characteristicId: this.writeCharId,
        value: this._toArrayBuffer(chunk),
      });
      // 透传芯片缓冲小，包间固定间隔（安卓 32B/80ms；大包可略快）
      await new Promise((r) => setTimeout(r, this.packetDelay));
    }
  }

  _toArrayBuffer(arr) {
    const buf = new ArrayBuffer(arr.length);
    new Uint8Array(buf).set(arr);
    return buf;
  }

  /** 清空输入 → 发命令 → 稍等 → 读响应（固定套路） */
  async query(command, nbytes) {
    this.rxBuffer = [];
    await this.send(command);
    await new Promise((r) => setTimeout(r, 150));
    const deadline = Date.now() + 1500;
    while (Date.now() < deadline) {
      if (this.rxBuffer.length >= nbytes) {
        return this.rxBuffer.splice(0, nbytes);
      }
      await new Promise((r) => setTimeout(r, 20));
    }
    const all = this.rxBuffer;
    this.rxBuffer = [];
    return all;
  }

  /** 状态查询：响应 1 字节，0=正常 */
  async queryStatus() {
    const resp = await this.query(P.CMD_STATUS, 1);
    if (resp.length === 0) return null;
    const raw = resp[0];
    return {
      raw,
      coverOpen: (raw & 0x02) !== 0,
      noPaper: (raw & 0x04) !== 0,
      overheat: (raw & 0x10) !== 0,
      healthy: raw === 0,
    };
  }

  /** 电量：响应 2 字节，第 2 字节是百分比 */
  async queryBattery() {
    const resp = await this.query(P.CMD_BATTERY, 2);
    if (resp.length < 2) return null;
    return resp[1];
  }

  /** 设备信息：10 FF 70 → 名称|MAC|MAC|固件|SN|电量 */
  async queryDeviceInfo() {
    const resp = await this.query(P.CMD_DEVICE_INFO, 128);
    const text = resp.filter((b) => b >= 0x20 && b <= 0x7e).map((b) => String.fromCharCode(b)).join('').trim();
    if (!text) return null;
    const parts = text.split('|');
    return {
      model: parts[0] || '',
      firmware: parts[3] || '',
      raw: text,
    };
  }

  /**
   * 打印一张光栅（协议时序同安卓版）。
   * @param {Uint8Array} raster packRaster 输出（48B/行）
   * @param {number} height 光栅行数
   * @param {object} opts { thickness, mode, halveRows, feedBefore, feedAfter, chunkRows }
   *   halveRows 已由调用方处理（行合并减半），mode=2 时数据高度已减半
   */
  async printRaster(raster, height, opts = {}) {
    const { thickness = 2, mode = 0, feedBefore = 10, feedAfter = 100, chunkRows = 64 } = opts;
    const sendAll = async (cmds) => { for (const c of cmds) await this.send(c); };

    this.rxBuffer = [];
    await this.send(P.CMD_STOP);
    await new Promise((r) => setTimeout(r, 100));
    await this.send(P.CMD_ENABLE);
    await this.send(P.cmdThickness(thickness));
    await this.send(P.CMD_WAKEUP);
    await this.send(P.CMD_ESC_INIT);
    await sendAll(P.cmdFeed(feedBefore));

    const w = P.WIDTH_BYTES;
    for (let row = 0; row < height; row += chunkRows) {
      const rows = Math.min(chunkRows, height - row);
      await this.send(P.cmdRasterHeader(w, rows, mode));
      const chunk = raster.slice(row * w, (row + rows) * w);
      await this.send(chunk);
      await new Promise((r) => setTimeout(r, 150)); // 块间缓冲
    }

    await sendAll(P.cmdFeed(feedAfter));
    await this.send(P.CMD_STOP);

    // 等打印完成 ACK（0xAA）或故障帧（FF xx）
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      if (this.rxBuffer.includes(P.ACK_PRINT_DONE)) {
        this.rxBuffer = [];
        return { ok: true, message: '打印完成' };
      }
      for (let i = 1; i < this.rxBuffer.length; i++) {
        if (this.rxBuffer[i - 1] === P.FAULT_FRAME_HEAD) {
          const label = P.FAULT_LABELS[this.rxBuffer[i]];
          if (label) {
            this.rxBuffer = [];
            return { ok: false, message: label };
          }
        }
      }
      await new Promise((r) => setTimeout(r, 100));
    }
    return { ok: false, message: '等待打印完成超时' };
  }
}

module.exports = { BlePrinter, SERVICE_UUID, WRITE_UUID, NOTIFY_UUID };

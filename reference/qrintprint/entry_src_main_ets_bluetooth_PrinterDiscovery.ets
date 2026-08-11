import { access, connection } from '@kit.ConnectivityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'PrinterDiscovery';

/**
 * 设备名过滤前缀 —— 只展示自家 Qring 打印机,滤掉耳机/手环等无关蓝牙设备。
 * 要放宽或改规则,改这一个常量(或下面的 matchesDeviceFilter)即可。
 */
export const DEVICE_NAME_PREFIX: string = 'Qring';

/**
 * 大小写不敏感匹配 —— 不同批次固件可能上报 Qring / QRing / QRING,
 * 严格区分大小写会漏掉设备。
 */
export function matchesDeviceFilter(name: string): boolean {
  if (name.length === 0) {
    return false;
  }
  return name.toLowerCase().startsWith(DEVICE_NAME_PREFIX.toLowerCase());
}

export interface BtDevice {
  deviceId: string;
  name: string;
  paired: boolean;
}

/**
 * 蓝牙开关状态。
 * Previewer / 模拟器没有蓝牙能力,access.getState() 会直接抛异常,
 * 这里吞掉并返回 false —— 否则整个页面会白屏。
 */
export function isBluetoothEnabled(): boolean {
  try {
    return access.getState() === access.BluetoothState.STATE_ON;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'access.getState unavailable: %{public}s', JSON.stringify(err));
    return false;
  }
}

/** 取远端设备名。取不到返回空串 —— 空串过不了过滤器,不会混进列表 */
function remoteName(deviceId: string): string {
  try {
    return connection.getRemoteDeviceName(deviceId);
  } catch (err) {
    return '';
  }
}

/**
 * 系统里已配对、且名字匹配过滤器的设备。
 * 打印机通常已在系统设置里配过,这份列表优先展示。
 */
export function listPairedDevices(): BtDevice[] {
  try {
    const ids: Array<string> = connection.getPairedDevices();
    const devices: BtDevice[] = [];
    for (const id of ids) {
      const name: string = remoteName(id);
      if (!matchesDeviceFilter(name)) {
        continue;
      }
      devices.push({ deviceId: id, name: name, paired: true });
    }
    return devices;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'getPairedDevices failed: %{public}s', JSON.stringify(err));
    return [];
  }
}

/**
 * 一次扫描会话。
 *
 * 用 'discoveryResult' 而不是 'bluetoothDeviceFind':后者回调只给 MAC 地址,
 * 名字得再调 getRemoteDeviceName 查,而刚发现的设备名字未必已解析出来 ——
 * 按名字过滤的话会把设备误判成不匹配而漏掉。
 * 'discoveryResult' 的回调直接带 deviceName,过滤才可靠。
 *
 * 关键:回调必须是**具名引用**,匿名函数无法 off()。
 * 半模态关闭 / 组件销毁时必须 stop(),否则扫描会一直跑着耗电。
 */
export class DiscoverySession {
  private seenIds: string[] = [];
  private devices: BtDevice[] = [];
  private running: boolean = false;
  private onUpdate: (devices: BtDevice[]) => void = () => {};

  /** 具名回调,stop() 时用同一个引用注销 */
  private handleDiscoveryResult: (results: Array<connection.DiscoveryResult>) => void =
    (results: Array<connection.DiscoveryResult>) => {
      let changed: boolean = false;
      for (const result of results) {
        if (!matchesDeviceFilter(result.deviceName)) {
          continue;
        }
        if (this.seenIds.indexOf(result.deviceId) < 0) {
          this.seenIds.push(result.deviceId);
          this.devices.push({
            deviceId: result.deviceId,
            name: result.deviceName,
            paired: false,
          });
          changed = true;
        }
      }
      if (changed) {
        // 一次性整体替换数组,避免逐条 push 触发多次重渲染
        this.onUpdate(this.devices.slice());
      }
    };

  isRunning(): boolean {
    return this.running;
  }

  /**
   * 开始扫描。已配对设备预置进结果里,避免列表先空一下再跳出来。
   * 返回 false 表示环境不支持(无蓝牙 / 未授权 / 蓝牙未开)。
   */
  start(paired: BtDevice[], onUpdate: (devices: BtDevice[]) => void): boolean {
    if (this.running) {
      return true;
    }
    this.onUpdate = onUpdate;
    this.seenIds = [];
    this.devices = [];
    for (const device of paired) {
      this.seenIds.push(device.deviceId);
      this.devices.push(device);
    }

    try {
      connection.on('discoveryResult', this.handleDiscoveryResult);
      connection.startBluetoothDiscovery();
      this.running = true;
      return true;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'startBluetoothDiscovery failed: %{public}s', JSON.stringify(err));
      // 订阅可能已成功但启动失败,统一撤干净,避免留下悬空监听
      try {
        connection.off('discoveryResult', this.handleDiscoveryResult);
      } catch (offErr) {
        // 未订阅成功时 off 会抛,忽略
      }
      this.running = false;
      return false;
    }
  }

  stop(): void {
    if (!this.running) {
      return;
    }
    this.running = false;
    try {
      connection.stopBluetoothDiscovery();
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'stopBluetoothDiscovery failed: %{public}s', JSON.stringify(err));
    }
    try {
      connection.off('discoveryResult', this.handleDiscoveryResult);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'off discoveryResult failed: %{public}s', JSON.stringify(err));
    }
  }
}

/**
 * 监听蓝牙开关变化。返回反注册函数。
 * 用户在设置里关掉蓝牙时页面要能立刻反应,不能等到下次操作才发现连不上。
 */
export function watchBluetoothState(onChange: (enabled: boolean) => void): () => void {
  const handler: (state: access.BluetoothState) => void = (state: access.BluetoothState) => {
    onChange(state === access.BluetoothState.STATE_ON);
  };
  try {
    access.on('stateChange', handler);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'access.on stateChange unavailable: %{public}s', JSON.stringify(err));
    return () => {};
  }
  return () => {
    try {
      access.off('stateChange', handler);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'access.off stateChange failed: %{public}s', JSON.stringify(err));
    }
  };
}

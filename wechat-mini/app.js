// app.js —— 错题小印 X1 打印客户端（微信小程序版，2026-08-11 立项）
// 对应安卓版 com.qring.print：BLE 透传通道（FF00/FF02/FF01）+ Qring 私有协议。
// 微信限制：仅 BLE（无 SPP）；分包 20B 默认，setBLEMTU 协商提升。

App({
  globalData: {
    ble: null,          // BlePrinter 实例（utils/ble.js）
    connected: false,
  },

  onLaunch() {
    const { BlePrinter } = require('./utils/ble');
    this.globalData.ble = new BlePrinter();
  },
});

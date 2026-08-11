// 首页：连接 + 入口
const app = getApp();

Page({
  data: {
    connected: false,
    scanning: false,
    devices: [],
    deviceName: '',
    modelText: '',
  },

  onShow() {
    this.ble = app.globalData.ble;
    this.syncState();
    this.ble.on('connected', (dev) => {
      this.setData({ connected: true, deviceName: dev.name || dev.localName || '打印机' });
      this.loadInfo();
    });
    this.ble.on('disconnected', () => {
      this.setData({ connected: false, devices: [], deviceName: '', modelText: '' });
    });
  },

  syncState() {
    this.setData({ connected: this.ble.connected });
  },

  async loadInfo() {
    try {
      const info = await this.ble.queryDeviceInfo();
      if (info) {
        this.setData({ modelText: `${info.model} · 固件 ${info.firmware}` });
      }
    } catch (e) {}
  },

  async onScan() {
    this.setData({ scanning: true, devices: [] });
    try {
      const devices = await this.ble.scan(10000);
      this.setData({ devices });
      if (devices.length === 0) {
        wx.showToast({ title: '未发现打印机', icon: 'none' });
      }
    } catch (e) {
      wx.showToast({ title: e.message, icon: 'none' });
    }
    this.setData({ scanning: false });
  },

  async onConnect(e) {
    const { id, name } = e.currentTarget.dataset;
    wx.showLoading({ title: '正在连接…' });
    try {
      await this.ble.connect({ deviceId: id, name });
      wx.hideLoading();
      wx.showToast({ title: '连接成功', icon: 'success' });
      this.setData({ connected: true, deviceName: name || '打印机' });
      this.loadInfo();
    } catch (err) {
      wx.hideLoading();
      wx.showModal({ title: '连接失败', content: err.message, showCancel: false });
    }
  },

  async onDisconnect() {
    await this.ble.disconnect();
  },

  goText() { wx.navigateTo({ url: '/pages/print-text/print-text' }); },
  goImage() { wx.navigateTo({ url: '/pages/print-image/print-image' }); },
  goTemplates() { wx.navigateTo({ url: '/pages/templates/templates' }); },
});

// 文字打印：canvas 384 宽渲染 → 灰度二值化 → 光栅 → BLE 打印
const app = getApp();
const P = require('../../utils/protocol');

const W = P.WIDTH_DOTS; // 384

Page({
  data: {
    text: '',
    fontSizes: [32, 48, 64],
    fontIndex: 1,
    status: '',
  },

  onInput(e) { this.setData({ text: e.detail.value }); },
  onFontChange(e) { this.setData({ fontIndex: Number(e.detail.value) }); },

  async _getCanvas() {
    return new Promise((resolve) => {
      wx.createSelectorQuery()
        .select('#previewCanvas')
        .fields({ node: true, size: true })
        .exec((res) => resolve(res[0]));
    });
  },

  /** 渲染文字 → 返回 { binary: Uint8Array(1=黑), height } */
  async renderText() {
    const text = this.data.text.trim();
    if (!text) throw new Error('请先输入文字');

    const canvasRes = await this._getCanvas();
    const canvas = canvasRes.node;
    const ctx = canvas.getContext('2d');
    const fontSize = this.data.fontSizes[this.data.fontIndex];

    // 测量换行（canvas 像素是物理像素，需按 devicePixelRatio 适配；
    // 简化：直接按 css 像素测量，打印分辨率 384 对应 canvas 宽度）
    const cssW = 320; // 预览画布 css 宽
    canvas.width = W; // 384 物理像素
    ctx.font = `${fontSize}px sans-serif`;

    const lineHeight = Math.ceil(fontSize * 1.3);
    // 换行：逐字符（中文无词边界）
    const lines = [];
    let cur = '';
    for (const ch of text) {
      if (ctx.measureText(cur + ch).width <= W - 16 && ch !== '\n') {
        cur += ch;
      } else {
        if (cur) lines.push(cur);
        cur = ch === '\n' ? '' : ch;
      }
    }
    if (cur) lines.push(cur);

    const height = Math.max(1, lines.length * lineHeight + 16);
    canvas.height = height;
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, W, height);
    ctx.fillStyle = '#000000';
    ctx.font = `${fontSize}px sans-serif`;
    ctx.textBaseline = 'top';
    lines.forEach((line, i) => ctx.fillText(line, 8, 8 + i * lineHeight));

    // 二值化（文字高阈值 212，同安卓 THRESHOLD_TEXT）
    const imgData = ctx.getImageData(0, 0, W, height);
    const px = imgData.data;
    const binary = new Uint8Array(W * height);
    for (let i = 0; i < W * height; i++) {
      const gray = (px[i * 4] * 299 + px[i * 4 + 1] * 587 + px[i * 4 + 2] * 114) / 1000;
      binary[i] = gray < 212 ? 1 : 0;
    }
    return { binary, height };
  },

  /** 预览：把二值结果画回 canvas（白底黑点） */
  async onPreview() {
    try {
      const { binary, height } = await this.renderText();
      const canvasRes = await this._getCanvas();
      const canvas = canvasRes.node;
      const ctx = canvas.getContext('2d');
      canvas.width = W;
      canvas.height = height;
      const img = ctx.createImageData(W, height);
      for (let i = 0; i < W * height; i++) {
        const v = binary[i] ? 0 : 255;
        img.data[i * 4] = v; img.data[i * 4 + 1] = v; img.data[i * 4 + 2] = v; img.data[i * 4 + 3] = 255;
      }
      ctx.putImageData(img, 0, 0);
      this.setData({ status: `共 ${height} 行，确认效果后打印` });
    } catch (e) {
      wx.showToast({ title: e.message, icon: 'none' });
    }
  },

  async onPrint() {
    const ble = app.globalData.ble;
    if (!ble.connected) return wx.showToast({ title: '请先连接打印机', icon: 'none' });
    try {
      const { binary, height } = await this.renderText();
      const raster = P.packRaster(binary, height);
      wx.showLoading({ title: '打印中…' });
      const result = await ble.printRaster(raster, height, { thickness: 2, mode: 0 });
      wx.hideLoading();
      this.setData({ status: result.ok ? '✅ 打印完成' : `❌ ${result.message}` });
    } catch (e) {
      wx.hideLoading();
      wx.showToast({ title: e.message, icon: 'none' });
    }
  },
});

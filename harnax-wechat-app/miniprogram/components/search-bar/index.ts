Component({
  properties: {
    value: { type: String, value: '' },
    placeholder: { type: String, value: '请输入关键词' },
    showAction: { type: Boolean, value: false },
  },
  methods: {
    onTChange(e: any) {
      const value = e.detail.value || '';
      this.setData({ value });
      this.triggerEvent('input', { value });
    },
    onTSubmit() {
      this.triggerEvent('search', { value: this.data.value });
    },
    onTClear() {
      this.setData({ value: '' });
      this.triggerEvent('input', { value: '' });
      this.triggerEvent('search', { value: '' });
    },
  },
});

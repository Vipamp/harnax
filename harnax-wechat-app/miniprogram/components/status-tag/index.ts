Component({
  properties: {
    // success | default | processing | warning | error
    type: { type: String, value: 'default' },
    text: { type: String, value: '' },
    dot: { type: Boolean, value: true },
  },
});

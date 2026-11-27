import { defineConfig } from 'wxt';

export default defineConfig({
  manifest: {
    name: 'Futaba',
    icons: {
      16: 'icon/16.png',
      32: 'icon/32.png',
      48: 'icon/48.png',
      128: 'icon/128.png',
    },
    action: {
      default_icon: {
        16: 'icon/16.png',
        32: 'icon/32.png',
      },
    },
    permissions: ['identity', 'contextMenus', 'storage', 'activeTab'],
    key: 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqqJAJCDUOmXQY1yCtKqweSYAIi8LsdavjkK8ylAnFm9NzyDzc9HkAF7ErZV0RUKMThCLxJJjUOaV/LogKj295T+30Ua1dZBQcU+/eG8GVSXozOjU4mgXVuGpz+q8tmDtImAI6QzRzKlvkMp7lU4z0DPiSdp6FYnfe3LRdtv7G702Tgsynx4CHLHue6oRI7epfhwYHTaJERKhmb+s8h5FncM41LZ56krRbR9LITT5XBccOY5K9YtqmwZR3rhWzW8kbE0IRa1bf9Vf2LhYUFoEnulKuXsqUoegzIMYK1o5HIn7vep/bWa9cmF/2tuQ/qoif1IMEZF/yxzKLysenFDepwIDAQAB',
  },
});

import fmt from '@caffelatte23/oxc-config/fmt';
import { defineConfig } from 'oxfmt';

export default defineConfig({
  ...fmt,
  ignorePatterns: ['.output/**', '.wxt/**'],
});

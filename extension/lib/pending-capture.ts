import { storage } from 'wxt/utils/storage';

export const pendingCapture = storage.defineItem<string>(
  'local:pendingCapture',
);

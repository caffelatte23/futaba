import { storage } from 'wxt/utils/storage';

export const signedInFlag = storage.defineItem<boolean>('local:signedIn', {
  fallback: false,
});

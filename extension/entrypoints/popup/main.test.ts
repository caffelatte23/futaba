import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineBackground } from 'wxt/utils/define-background';

import popupHtml from './index.html?raw';

const currentUser = vi.fn();
const signIn = vi.fn();
const getFlag = vi.fn();
const setFlag = vi.fn();
const getPending = vi.fn();
const setPending = vi.fn();
const onMessage = vi.fn();
const sendMessage = vi.fn();

vi.mock('../../lib/firebase', () => ({
  currentUser,
  signIn,
  FirebaseConfigError: Error,
}));
vi.mock('../../lib/notes', () => ({ addNote: vi.fn() }));
vi.mock('wxt/utils/storage', () => ({
  storage: {
    defineItem: (key: string, options?: { fallback: boolean }) =>
      key === 'local:signedIn'
        ? {
            getValue: () => getFlag() ?? options?.fallback,
            setValue: setFlag,
          }
        : { getValue: getPending, setValue: setPending },
  },
}));
vi.mock('wxt/browser', () => ({
  browser: { runtime: { sendMessage } },
}));

class Control extends EventTarget {
  hidden = false;
  disabled = false;
  readOnly = false;
  value = '';
  textContent = '';
  selectionStart = 0;
  selectionEnd = 0;
  selectionDirection = 'none';
  focus = vi.fn();
  setSelectionRange = vi.fn();
}

function popupControls() {
  const controls = {
    '#signed-out': new Control(),
    '#sign-in': new Control(),
    '#capture': new Control(),
    '#note': new Control(),
    '#save': new Control(),
    '#status': new Control(),
  };
  for (const [selector, control] of Object.entries(controls)) {
    const tag = popupHtml.match(
      new RegExp(`<[^>]+id="${selector.slice(1)}"[^>]*>`),
    );
    if (tag) {
      control.disabled = /\bdisabled\b/u.test(tag[0]);
      control.hidden = /\bhidden\b/u.test(tag[0]);
      const content = popupHtml.slice((tag.index ?? 0) + tag[0].length);
      control.textContent = /^[^<]*/u.exec(content)?.[0].trim() ?? '';
    }
  }
  vi.stubGlobal('document', {
    querySelector: (selector: keyof typeof controls) => controls[selector],
  });
  return controls;
}

async function startBackground() {
  vi.stubGlobal('defineBackground', defineBackground);
  vi.stubGlobal('browser', {
    runtime: {
      onInstalled: { addListener: vi.fn() },
      onMessage: { addListener: onMessage },
    },
    contextMenus: { onClicked: { addListener: vi.fn() } },
  });
  const { default: background } = await import('../background');
  background.main();
  sendMessage.mockImplementation(
    (message: unknown) =>
      new Promise((resolve) =>
        onMessage.mock.lastCall?.[0](message, {}, resolve),
      ),
  );
}

describe('popup sign-in state', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.resetAllMocks();
    getPending.mockResolvedValue(null);
    setFlag.mockResolvedValue(undefined);
    setPending.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('flagSetShowsCaptureBeforeAuthCheck', async () => {
    const auth = Promise.withResolvers<object | null>();
    currentUser.mockReturnValue(auth.promise);
    getFlag.mockResolvedValue(true);
    const controls = popupControls();

    const started = import('./main');
    await vi.waitFor(() => expect(currentUser).toHaveBeenCalledTimes(1));
    const beforeAuth = {
      hidden: controls['#capture'].hidden,
      focusCalls: controls['#note'].focus.mock.calls.length,
    };
    auth.resolve({});
    await started;

    expect(beforeAuth).toEqual({ hidden: false, focusCalls: 1 });
  });

  it('staleSignedInFlagKeepsTypedText', async () => {
    const auth = Promise.withResolvers<null>();
    currentUser.mockReturnValue(auth.promise);
    getFlag.mockResolvedValue(true);
    getPending.mockResolvedValue('右クリックで保留したメモ');
    await startBackground();
    const controls = popupControls();

    const started = import('./main');
    await vi.waitFor(() => expect(currentUser).toHaveBeenCalledTimes(1));
    controls['#note'].value = '  入力中のアイデア  ';
    controls['#note'].dispatchEvent(new Event('input'));
    const saveDisabled = controls['#save'].disabled;
    auth.resolve(null);
    await started;

    expect(setPending).toHaveBeenCalledExactlyOnceWith(
      '右クリックで保留したメモ\n\n  入力中のアイデア  ',
    );
    expect(setFlag).toHaveBeenCalledExactlyOnceWith(false);
    expect(saveDisabled).toBe(true);
    expect(controls['#capture'].hidden).toBe(true);
    expect(controls['#signed-out'].hidden).toBe(false);
    expect(controls['#sign-in'].hidden).toBe(false);
    expect(controls['#sign-in'].disabled).toBe(false);
  });

  it('noFlagShowsSignedOutWithoutCheckingText', async () => {
    const auth = Promise.withResolvers<null>();
    currentUser.mockReturnValue(auth.promise);
    getFlag.mockResolvedValue(false);
    const controls = popupControls();
    const initialStatus = controls['#status'].textContent;

    const started = import('./main');
    await vi.waitFor(() => expect(currentUser).toHaveBeenCalledTimes(1));
    const beforeAuth = {
      signedOutHidden: controls['#signed-out'].hidden,
      captureHidden: controls['#capture'].hidden,
      signInDisabled: controls['#sign-in'].disabled,
      status: controls['#status'].textContent,
    };
    auth.resolve(null);
    await started;

    expect(initialStatus).toBe('');
    expect(beforeAuth).toEqual({
      signedOutHidden: false,
      captureHidden: true,
      signInDisabled: true,
      status: '',
    });
    expect(controls['#sign-in'].disabled).toBe(false);
  });

  it('signInSetsFlag', async () => {
    const auth = Promise.withResolvers<object>();
    signIn.mockReturnValue(auth.promise);
    await startBackground();

    const response = sendMessage('futaba:sign-in');
    expect(signIn).toHaveBeenCalledTimes(1);
    expect(setFlag).not.toHaveBeenCalled();
    auth.resolve({});

    await expect(response).resolves.toBe(true);
    expect(setFlag).toHaveBeenCalledExactlyOnceWith(true);
  });

  it('keeps early input when the pending capture finishes loading', async () => {
    const auth = Promise.withResolvers<object>();
    const pending = Promise.withResolvers<string>();
    currentUser.mockReturnValue(auth.promise);
    getFlag.mockResolvedValue(true);
    getPending.mockReturnValue(pending.promise);
    const controls = popupControls();

    const started = import('./main');
    await vi.waitFor(() => expect(currentUser).toHaveBeenCalledTimes(1));
    controls['#note'].value = '新しいアイデア';
    controls['#note'].dispatchEvent(new Event('input'));
    auth.resolve({});
    await vi.waitFor(() => expect(getPending).toHaveBeenCalledTimes(1));
    pending.resolve('保留中のメモ');
    await started;

    expect(controls['#note'].value).toBe('保留中のメモ\n\n新しいアイデア');
    expect(controls['#save'].disabled).toBe(false);
    expect(controls['#note'].focus).toHaveBeenCalledTimes(1);
  });
});

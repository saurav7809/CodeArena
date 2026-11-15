function read(area, key) {
  try { return window[area].getItem(key); }
  catch { return null; }
}

function write(area, key, value) {
  try {
    if (value == null) window[area].removeItem(key);
    else window[area].setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

export const storage = {
  local: { get: (key) => read('localStorage', key), set: (key, value) => write('localStorage', key, value) },
  session: { get: (key) => read('sessionStorage', key), set: (key, value) => write('sessionStorage', key, value) },
};

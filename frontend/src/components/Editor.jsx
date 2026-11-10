import { useEffect, useRef } from 'react';
import { editor } from 'monaco-editor/esm/vs/editor/editor.api.js';
import 'monaco-editor/esm/vs/editor/editor.all.js';
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/cpp/cpp.contribution.js';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker.js?worker';

globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };

editor.defineTheme('arena-dark', {
  base: 'vs-dark', inherit: true, rules: [],
  colors: {
    'editor.background': '#0e1220',
    'editor.foreground': '#dfe5f7',
    'editorLineNumber.foreground': '#68738d',
    'editorLineNumber.activeForeground': '#aba8f7',
    'editor.lineHighlightBackground': '#171c2f',
    'editor.selectionBackground': '#35346a',
    'editorCursor.foreground': '#a5a3ff',
  },
});

const MODE = { PYTHON: 'python', JAVA: 'java', CPP: 'cpp' };

export default function Editor({ value, language, theme, height, onChange }) {
  const host = useRef(null);
  const instance = useRef(null);
  const initial = useRef({ value, language, theme });
  const change = useRef(onChange);
  const replacing = useRef(false);

  useEffect(() => { change.current = onChange; }, [onChange]);
  useEffect(() => {
    const saved = initial.current;
    const model = editor.createModel(saved.value, MODE[saved.language]);
    const control = editor.create(host.current, {
      model, theme: saved.theme === 'dark' ? 'arena-dark' : 'vs',
      ariaLabel: 'Solution code editor', automaticLayout: true, minimap: { enabled: false },
      fontSize: 14, lineHeight: 23, padding: { top: 14, bottom: 14 }, tabSize: 4,
      insertSpaces: true, scrollBeyondLastLine: false, wordWrap: 'on', renderLineHighlight: 'line',
      overviewRulerLanes: 0, wordBasedSuggestions: 'currentDocument',
    });
    instance.current = control;
    const listener = model.onDidChangeContent(() => {
      if (!replacing.current) change.current(model.getValue());
    });
    return () => {
      listener.dispose();
      control.dispose();
      model.dispose();
      instance.current = null;
    };
  }, []);
  useEffect(() => { editor.setTheme(theme === 'dark' ? 'arena-dark' : 'vs'); }, [theme]);
  useEffect(() => {
    const control = instance.current;
    if (!control || control.getValue() === value) return;
    replacing.current = true;
    try { control.setValue(value); } finally { replacing.current = false; }
  }, [value]);
  return <div ref={host} className="monaco-host" style={{ height }} />;
}

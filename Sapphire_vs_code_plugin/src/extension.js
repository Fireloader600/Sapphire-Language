const vscode = require('vscode');
const cp = require('child_process');
const path = require('path');
const fs = require('fs');

/* ============================================================
 *  文档数据：悬停提示 + 自动补全共用
 * ============================================================ */
const K = vscode.CompletionItemKind;

const OS_DOCS = {
  /* ---------- 关键字 ---------- */
  'import': {
    kind: K.Keyword,
    detail: '导入模块 / 库文件',
    doc: '导入模块：`import os;`、`import style;`。\n\n导入库文件：`import lib.oslm;`（路径含空格时用 `import "my lib.oslm";`）。',
    insert: 'import ${1:os};'
  },
  'com': {
    kind: K.Class,
    detail: '执行块（必选）',
    doc: '主容器块，用来放置页面元素。子元素会直接铺到页面里，不生成包装标签。\n\n注意：`com` 是**执行块**，`org` 是**定义块**，别混用。',
    insert: 'com {\n    $0\n}'
  },
  'org': {
    kind: K.Class,
    detail: '定义块（用于 .oslm）',
    doc: '定义块，在 `.oslm` 库文件里用来写 `function`。不要把页面元素放进 `org`。',
    insert: 'org {\n    $0\n}'
  },
  'net': {
    kind: K.Class,
    detail: '网络块',
    doc: '和 `com` 同级的块，用于描述网络请求相关内容。',
    insert: 'net {\n    $0\n}'
  },
  'event': {
    kind: K.Keyword,
    detail: '事件定义',
    doc: '定义一段事件处理逻辑。用法：`event("名字") { ... }`，在 `botton` 的第二个参数里引用名字。',
    insert: 'event("${1:event_name}") {\n    $0\n}'
  },
  'style': {
    kind: K.Keyword,
    detail: '样式块',
    doc: '类 CSS 语法，需要先 `import style;`。选择器里的 `st1` 会自动映射为 `h1`，`tip` 映射为 `.os-tip`。',
    insert: 'style {\n    ${1:st1} {\n        ${2:color}: ${3:#5fd3ff};\n    }\n}'
  },
  'function': {
    kind: K.Keyword,
    detail: '函数定义（.oslm）',
    doc: '在 `.oslm` 的 `org` 块里定义一个函数。\n\n参数用分号隔开，类型可选：\n\n```os\nfunction example(int a; int b) {\n    st1(a);\n    st2(b);\n}\n```\n\n主文件里 `import lib.oslm;` 后即可调用 `example("Hello", "World");`。',
    insert: 'function ${1:name}(${2:int a; int b}) {\n    $0\n}'
  },

  /* ---------- 内置元素 ---------- */
  'st1': { kind: K.Function, detail: '一级标题 → h1', doc: '大标题。', insert: 'st1("${1:Hello World!}");' },
  'st2': { kind: K.Function, detail: '二级标题 → h2', doc: '副标题。', insert: 'st2("${1:副标题}");' },
  'st3': { kind: K.Function, detail: '三级标题 → h3', doc: '三级标题。', insert: 'st3("${1:标题}");' },
  'st4': { kind: K.Function, detail: '四级标题 → h4', doc: '四级标题。', insert: 'st4("${1:标题}");' },
  'st5': { kind: K.Function, detail: '五级标题 → h5', doc: '五级标题。', insert: 'st5("${1:标题}");' },
  'st6': { kind: K.Function, detail: '六级标题 → h6', doc: '六级标题。', insert: 'st6("${1:标题}");' },
  'image': {
    kind: K.Function,
    detail: '图片 → img',
    doc: '插入图片。`src` 是路径，可选 `alt`、`width`、`height`。',
    insert: 'image(src="${1:test.svg}", alt="${2:图片}");'
  },
  'botton': {
    kind: K.Function,
    detail: '按钮 → button',
    doc: '第一个参数是按钮文字，第二个参数是事件名（对应 `event("名字")`）。',
    insert: 'botton("${1:按钮}", "${2:event_name}");'
  },
  'tip': {
    kind: K.Function,
    detail: '提示框',
    doc: '输出一个提示框，默认浅蓝底 + 左侧蓝色条。可在 `style { tip { ... } }` 里改。',
    insert: 'tip("${1:提示内容}");'
  },
  'text': { kind: K.Function, detail: '文本 → p', doc: '插入一段普通文本。', insert: 'text("${1:内容}");' },
  'p': { kind: K.Function, detail: '段落 → p', doc: '等同 `text`。', insert: 'p("${1:内容}");' },
  'link': {
    kind: K.Function,
    detail: '链接 → a',
    doc: '第一个参数是显示文字，`href` 是目标网址。',
    insert: 'link("${1:文字}", href="${2:https://example.com}");'
  },
  'input': {
    kind: K.Function,
    detail: '输入框 → input',
    doc: '可选参数 `type`、`placeholder`。',
    insert: 'input(type="${1:text}", placeholder="${2:请输入}");'
  },
  'hr': { kind: K.Function, detail: '分隔线 → hr', doc: '插入一条水平分隔线。', insert: 'hr();' },
  'row': { kind: K.Function, detail: '横向布局 → div', doc: '子元素水平排列。', insert: 'row {\n    $0\n}' },
  'col': { kind: K.Function, detail: '纵向布局 → div', doc: '子元素垂直排列。', insert: 'col {\n    $0\n}' },
  'box': { kind: K.Function, detail: '盒子 → div', doc: '通用容器。', insert: 'box {\n    $0\n}' },

  /* ---------- 事件指令 ---------- */
  'addhttp': { kind: K.Method, detail: '打开网址', doc: '在新标签页打开指定网址。', insert: 'addhttp("${1:https://cn.bing.com}");' },
  'openurl': { kind: K.Method, detail: '打开网址', doc: '同 `addhttp`。', insert: 'openurl("${1:https://example.com}");' },
  'alert': { kind: K.Method, detail: '弹窗', doc: '弹出浏览器提示框。', insert: 'alert("${1:消息}");' },
  'print': { kind: K.Method, detail: '控制台输出', doc: '输出到浏览器控制台。', insert: 'print("${1:消息}");' },
  'settext': { kind: K.Method, detail: '修改文字', doc: '把触发元素的文字改成指定内容。', insert: 'settext("${1:新文字}");' },
  'setcolor': { kind: K.Method, detail: '修改颜色', doc: '修改触发元素的文字颜色。', insert: 'setcolor("${1:#5fd3ff}");' },
  'hide': { kind: K.Method, detail: '隐藏元素', doc: '把触发元素隐藏。', insert: 'hide();' }
};

/* ---------- 参数类型提示 ---------- */
const TYPE_DOCS = ['int', 'string', 'float', 'bool', 'any', 'text', 'number'];

/* ============================================================
 *  激活
 * ============================================================ */
function activate(context) {
  const output = vscode.window.createOutputChannel('OpenSapphire');
  context.subscriptions.push(output);

  /* ---- 命令 ---- */
  context.subscriptions.push(
    vscode.commands.registerCommand('opensapphire.run', () => runCurrentFile(output))
  );
  context.subscriptions.push(
    vscode.commands.registerCommand('opensapphire.showOutput', () => output.show())
  );

  /* ---- 悬停提示（同时支持 .os 和 .oslm） ---- */
  const hoverProvider = {
    provideHover(document, position) {
      const range = document.getWordRangeAtPosition(position);
      if (!range) return undefined;
      const word = document.getText(range);
      const info = OS_DOCS[word];
      if (!info) return undefined;

      const md = new vscode.MarkdownString();
      md.appendMarkdown(`**\`${word}\`** — ${info.detail}\n\n${info.doc}`);
      if (info.insert) {
        md.appendCodeblock(
          info.insert.replace(/\$\{\d+:?([^}]*)\}/g, '$1').replace(/\$\d+/g, ''),
          'opensapphire'
        );
      }
      md.isTrusted = true;
      return new vscode.Hover(md, range);
    }
  };
  context.subscriptions.push(
    vscode.languages.registerHoverProvider('opensapphire', hoverProvider)
  );
  context.subscriptions.push(
    vscode.languages.registerHoverProvider('opensapphire-lib', hoverProvider)
  );

  /* ---- 自动补全（同时支持 .os 和 .oslm） ---- */
  const completionProvider = {
    provideCompletionItems(document) {
      const items = [];

      // 内置指令
      for (const [label, info] of Object.entries(OS_DOCS)) {
        const item = new vscode.CompletionItem(label, info.kind);
        item.detail = info.detail;
        item.documentation = new vscode.MarkdownString(info.doc);
        if (info.insert) {
          item.insertText = new vscode.SnippetString(info.insert);
        }
        items.push(item);
      }

      // 模块名
      for (const m of ['os', 'style']) {
        const it = new vscode.CompletionItem(m, K.Module);
        it.detail = '模块名';
        items.push(it);
      }

      // 参数类型
      for (const t of TYPE_DOCS) {
        const it = new vscode.CompletionItem(t, K.TypeParameter);
        it.detail = '参数类型';
        items.push(it);
      }

      // .oslm 库名自动补全：扫描当前目录下的 .oslm 文件
      const dir = path.dirname(document.uri.fsPath);
      try {
        const files = fs.readdirSync(dir);
        for (const f of files) {
          if (f.toLowerCase().endsWith('.oslm')) {
            const it = new vscode.CompletionItem(f, K.File);
            it.detail = 'OpenSapphire 库文件';
            it.documentation = new vscode.MarkdownString(`导入 \`${f}\``);
            it.insertText = f.includes(' ') ? `"${f}"` : f;
            items.push(it);
          }
        }
      } catch (e) { /* ignore */ }

      return items;
    }
  };
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider(
      'opensapphire', completionProvider, '.', '"', '(', ' '
    )
  );
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider(
      'opensapphire-lib', completionProvider, '.', '"', '(', ' '
    )
  );

  /* ---- 保存 .os / .oslm 后自动重编译（可选） ---- */
  context.subscriptions.push(
    vscode.workspace.onDidSaveTextDocument((doc) => {
      if (doc.languageId === 'opensapphire') {
        // 只重编译当前打开的主文件；若想更激进的自动编译，可在这里调用 runCurrentFile
      }
    })
  );
}

function deactivate() {}

/* ============================================================
 *  编译逻辑
 * ============================================================ */
async function runCurrentFile(output) {
  const editor = vscode.window.activeTextEditor;
  if (!editor) {
    vscode.window.showWarningMessage('OpenSapphire: 请先打开一个文件');
    return;
  }
  const langId = editor.document.languageId;
  if (langId !== 'opensapphire') {
    if (langId === 'opensapphire-lib') {
      vscode.window.showWarningMessage('OpenSapphire: .oslm 是库文件，不能被直接编译。请打开 main.os 后运行。');
    } else {
      vscode.window.showWarningMessage('OpenSapphire: 请先打开一个 .os 文件');
    }
    return;
  }

  const doc = editor.document;
  if (doc.isDirty) {
    await doc.save();
  }

  const filePath = doc.uri.fsPath;
  const wsFolder = vscode.workspace.getWorkspaceFolder(doc.uri);
  const cwd = wsFolder ? wsFolder.uri.fsPath : path.dirname(filePath);

  const cfg = vscode.workspace.getConfiguration('opensapphire');
  const jarPath = String(cfg.get('jarPath') || '').trim();
  const javaPath = String(cfg.get('javaPath') || 'java').trim() || 'java';
  const autoOpen = cfg.get('autoOpen', true);

  const cmdLine = jarPath
    ? `${q(javaPath)} -jar ${q(jarPath)} ${q(filePath)}`
    : `osw ${q(filePath)}`;

  output.appendLine('> ' + cmdLine);

  const result = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'OpenSapphire 正在编译…',
      cancellable: false
    },
    () => execShell(cmdLine, cwd)
  );

  if (result.stdout) output.appendLine(result.stdout.trimEnd());
  if (result.stderr) output.appendLine(result.stderr.trimEnd());

  if (result.code === 0) {
    vscode.window.showInformationMessage('OpenSapphire: 编译成功 ✓');
    if (autoOpen) {
      const base = path.basename(filePath, path.extname(filePath));
      const htmlPath = path.join(cwd, 'dist', base + '.html');
      if (fs.existsSync(htmlPath)) {
        vscode.env.openExternal(vscode.Uri.file(htmlPath));
      }
    }
  } else {
    output.show(true);
    vscode.window.showErrorMessage('OpenSapphire: 编译失败，详见输出面板');
  }
}

function execShell(cmd, cwd) {
  return new Promise((resolve) => {
    cp.exec(cmd, { cwd, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      resolve({
        code: err ? (typeof err.code === 'number' ? err.code : 1) : 0,
        stdout: stdout || '',
        stderr: stderr || ''
      });
    });
  });
}

/** 按平台给路径加引号 */
function q(s) {
  if (process.platform === 'win32') {
    return '"' + String(s).replace(/"/g, '""') + '"';
  }
  return "'" + String(s).replace(/'/g, "'\\''") + "'";
}

module.exports = { activate, deactivate };
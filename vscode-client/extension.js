const { window, workspace } = require('vscode');
const {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} = require('vscode-languageclient/node');

let client;

function serverCommand() {
  const cfg = workspace.getConfiguration('jdtls-native');
  const configured = cfg.get('serverPath');
  if (configured) {
    return { command: configured };
  }
  // fall back to the native exe next to the repo's build output
  const fs = require('fs');
  const path = require('path');
  const candidates = [
    path.join(__dirname, '..', 'target', 'native', 'jdtls-native.exe'),
    path.join(__dirname, '..', 'target', 'native', 'jdtls-native'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) {
      return { command: c };
    }
  }
  return null;
}

function activate(context) {
  const cmd = serverCommand();
  if (!cmd) {
    window.showErrorMessage(
      'jdtls-native: no server found. Set "jdtls-native.serverPath" or build target/native first.');
    return;
  }
  const env = { ...process.env };
  const jdk = workspace.getConfiguration('jdtls-native').get('jdkHome');
  if (jdk) {
    env.JAVA_HOME = jdk;
    env['jdtls.native.jdk'] = jdk;
  }

  const serverOptions = {
    ...cmd,
    options: { env, cwd: require('path').dirname(cmd.command) },
  };

  const clientOptions = {
    documentSelector: [{ scheme: 'file', language: 'java' }],
    synchronize: {
      fileEvents: workspace.createFileSystemWatcher('**/*.java'),
    },
  };

  client = new LanguageClient('jdtls-native', 'Java (jdtls-native)', serverOptions, clientOptions);
  client.start();
  context.subscriptions.push(client);
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };

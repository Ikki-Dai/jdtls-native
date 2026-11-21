# jdtls-native VS Code client

Minimal LSP client that launches the jdtls-native server (native image by
default, JVM fallback possible by pointing `jdtls-native.serverPath` at a
launcher script).

## Try it

```bash
cd vscode-client
npm install
npx @vscode/vsce package          # produces jdtls-native-vscode-0.1.0.vsix
code --install-extension jdtls-native-vscode-0.1.0.vsix
```

Set `jdtls-native.serverPath` to your `target/native/jdtls-native(.exe)` if it
is not next to this repo's build output.

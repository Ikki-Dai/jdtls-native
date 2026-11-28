# jdtls-native

**Java Language Server，GraalVM 原生可执行文件，无 OSGi 容器，完整 Eclipse JavaModel 引擎。**

## 结果（2026-11-20）

| 指标 | JVM (Temurin 21) | native exe | 提升 |
|---|---|---|---|
| 进程启动 → initialize 应答 | 583 ms | **16 ms** | 36× |
| RSS（握手完成后） | ~148 MB | **~61 MB** | 2.4× |
| 模型引擎引导（JavaModel+索引） | 863 ms | **311 ms** | 2.8× |

验收（JVM 与 native 双跑 PASS）：
- 冒烟 `smoke_client.py`：握手/诊断/补全 + hover/definition/references/rename/formatting/workspaceSymbol
- **E2E `e2e/e2e_demo.py`**：对真实 Maven 项目（`../demo-project`，commons-lang3 依赖）11 项断言全过 ——
  第三方 jar 补全（maven CLI 导入 classpath）、hover 签名、定义跳转、3 处引用、文档/工作区符号、
  跨文件重命名（引用搜索）、格式化、诊断零误报、**反编译文件大纲**
  （documentSymbol on decompiled jar/jrt class files）。

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ jdtls-native.exe (GraalVM 21, ~54MB, 无 JVM)                  │
│                                                              │
│  lsp4j 1.0.0            LSP over stdio（纯 Java + Gson）      │
│  ── 自研 OSGi API 模拟层（dev.jdtls.lite.osgi）                │
│     FakeBundle/BundleContext/ServiceRegistry/FrameworkUtilHelper│
│  ── 平台引导层（dev.jdtls.lite.bootstrap）                     │
│     standalone 扩展注册表 + preferences + workspace + JavaCore │
│     真实 bundle Activator 以假 BundleContext 启动              │
│  ── JavaModelEngine       补全/诊断/索引（ICompilationUnit、   │
│     SearchEngine、IndexManager 全量工作）                      │
│  ── JavaEngine (ASTParser) 单文件语法模式兜底                   │
│                                                              │
│  ✗ 无 Equinox 容器 ✗ 无扩展注册表 OSGi 实现 ✗ 无 IDE 运行时     │
└──────────────────────────────────────────────────────────────┘
```

Eclipse 平台库（org.eclipse.jdt.core 3.42.0 + core.resources/registry/preferences/
contenttype/filesystem/text）作为**普通 jar** 平铺 classpath，由自写模拟层驱动；
各平台 jar 的 `plugin.xml`/`MANIFEST.MF` 在磁盘上被注册表读取（native 下是合法文件 IO）。

## 构建与验收

```bash
mvn package                                   # classes + target/libs/*.jar
python smoke/smoke_client.py                  # JVM 冒烟
# native 构建（Windows；MSVC + GraalVM 21，见 scripts/build-native.bat）
scripts\build-native.bat
JAVA_HOME=<graalvm> python -u smoke/smoke_client.py --exe target/native/jdtls-native.exe
python tools/bench.py                         # JVM vs native 对比
```

## 关键 native 适配点（踩坑记录）

- `java.class.path` 在 native 下不可用 → `ClassPathScanner` 兜底扫描 exe 旁的 `libs/`
- `java.home` 在 native 下为 null → `JdkLocator`：`-Djdtls.native.jdk` → `JAVA_HOME` → `java.home`
- **jrt NIO 文件系统**：SVM 默认删除 jrtfs → 构建加 `-H:+AllowJRTFileSystem`，
  并把 JDK 的 `lib/jrt-fs.jar` 放上构建类路径（ServiceLoaderFeature 注册其纯 Java 存根 provider）
- 反射/Proxy/资源配置：native-image agent 跑一次完整冒烟会话采集（`--agent`）
- 启动顺序：runtime → jobs → preferences → resources → jdt.core（common 在 JVM 顺序无碍）

## 日志（IDE 侧排障）

服务器把日志同时写 stderr 与 `~/.jdtls-native/logs/server.log`（每次启动截断；
stdout 永远是 LSP 通道不占用）。每个请求记录方法名 + 耗时 + 结果摘要：

```
[14:20:01.123] [LSP] → textDocument/hover (37 ms) hover
[14:20:01.456] [LSP] → workspace/symbol (157 ms) query='Gateway' 105 found
[14:20:02.001] [INFO] build tool import finished in 11807 ms (E:\workspace\seal-gateway)
```

IDE 里操作出问题时 `tail -f` 该文件即可看到每个请求的响应情况。

## 路线图

- 反编译文件上的 references
- rename 覆盖注释/字符串（LTK TEXTUAL_MATCHES）
- 发布渠道：推 tag `v*` 自动构建双平台发行包并发布 GitHub Releases（release.yml）

## Releases

- `v0.1.0`（首个）：linux-amd64 / windows-amd64 双平台原生发行包 + sha256，
  由 `release.yml` 自动构建发布；`vscode-client/` 内含最小 VS Code LSP 客户端

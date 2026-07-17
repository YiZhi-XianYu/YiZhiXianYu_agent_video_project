# 在 VS Code 中配置 Java 21

## 当前机器状态

本机当前安装的是 JDK 25：

```text
C:\software\Java
```

IntelliJ IDEA 自带的 JetBrains Runtime 也是 Java 25，因此目前没有可以直接配置的 JDK 21 目录。项目的 Maven 编译目标已经设置为 Java 21，所以即使用 JDK 25 启动 Maven，也只会接受 Java 21 范围内的源码并输出 Java 21 字节码。

## 推荐安装方式：使用 VS Code 安装 JDK

1. 在 VS Code 扩展市场安装 `Extension Pack for Java`。
2. 按 `Ctrl+Shift+P` 打开命令面板。
3. 执行 `Java: Install New JDK`。
4. 选择 `Download`。
5. 发行版建议选择 Eclipse Temurin，版本选择 `21`，架构选择 `x64`。
6. 安装完成后执行 `Java: Configure Java Runtime`。
7. 在 `Project JDKs` 中将 JavaSE-21 设置为新安装的 JDK 21。
8. 执行 `Java: Clean Java Language Server Workspace`，然后重启 VS Code。

## 手动安装后的配置

如果 JDK 21 安装在：

```text
C:\Program Files\Eclipse Adoptium\jdk-21.0.x.x-hotspot
```

在 `.vscode/settings.json` 中增加：

```json
{
  "java.jdt.ls.java.home": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.x.x-hotspot",
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.x.x-hotspot",
      "default": true
    },
    {
      "name": "JavaSE-25",
      "path": "C:\\software\\Java"
    }
  ]
}
```

请把示例中的 `jdk-21.0.x.x-hotspot` 替换为实际目录，不要原样复制不存在的路径。

## 终端中的 JAVA_HOME

VS Code 的 Java Runtime 配置主要服务于 Java 扩展。若希望集成终端中的 Maven 也使用 Java 21，可在 PowerShell 当前会话执行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.x.x-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

也可以在 Windows“环境变量”中永久设置 `JAVA_HOME`，但建议先在当前终端验证路径正确。

## 如何确认项目确实使用 Java 21

在 VS Code 中检查：

1. 状态栏或 `Java: Configure Java Runtime` 显示项目为 JavaSE-21。
2. `java -version` 显示 21。
3. `mvn -version` 的 Java version 显示 21。
4. 在 `control-plane` 中运行 `mvn test` 成功。


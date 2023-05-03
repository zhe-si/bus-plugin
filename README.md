# bus-plugin

![Build](https://github.com/zhe-si/bus-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)

<!-- Plugin description -->
事件总线（Event Bus）辅助插件。

为了提升组件间通过事件总线通信的调用关系可读性，明确组件间的交互逻辑，本项目通过静态代码分析和动态数据收集的方式，来辅助展示事件的订阅、发布、流转等信息，让大家在体验事件总线带来的组件解耦等便利的同时，尽享调用函数的逻辑清晰性、可读性和易调试性。

支持 Java、Kotlin。
<!-- Plugin description end -->


## Installation

- Using IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "bus-plugin"</kbd> >
  <kbd>Install Plugin</kbd>
  
- Manually:

  Download the [latest release](https://github.com/zhe-si/bus-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

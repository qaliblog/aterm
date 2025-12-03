# aTerm

**aTerm** is a modern, Material 3-inspired terminal emulator for Android, designed as a sleek alternative to legacy terminal emulators. Built on [Termux's](https://github.com/termux/termux-app) robust TerminalView library, aTerm provides a powerful and user-friendly terminal experience on Android devices.

## Features

- **Modern Material 3 Design** - Beautiful, intuitive interface following Material Design 3 guidelines
- **Multiple Terminal Sessions** - Run multiple terminal sessions simultaneously with easy switching
- **Virtual Keyboard** - Customizable virtual keys for quick access to common terminal commands
- **Alpine Linux Support** - Built-in support for Alpine Linux rootfs with easy setup
- **AI-Powered Agent** - Integrated AI assistant to help with terminal tasks and code generation
- **Full Terminal Emulation** - Complete VT100/xterm terminal emulation with ANSI color support
- **Scrollback Buffer** - Extensive scrollback history to review past commands and output
- **Customizable** - Customizable themes, fonts, and terminal appearance

## Screenshots

<div>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="32%" />
</div>

## Download

Download the latest APK from the [Releases Section](https://github.com/qaliblog/aterm/releases/latest).

## Getting Started

### Basic Usage

1. Launch aTerm from your app drawer
2. Start a new terminal session
3. Use the virtual keyboard or your device's physical keyboard to enter commands
4. Switch between multiple sessions using the session tabs

### Alpine Linux Setup

aTerm includes built-in support for Alpine Linux. You can set up a rootfs environment directly from the app settings.

## FAQ

### **Q: Why do I get a "Permission Denied" error when trying to execute a binary or script?**

**A:** This happens because aTerm runs on the latest Android API, which enforces **W^X restrictions**. Since files in `$PREFIX` or regular storage directories can't be executed directly, you need to use one of the following workarounds:

---

### **Option 1: Use the Dynamic Linker (for Binaries)**

If you're trying to run a binary (not a script), you can use the dynamic linker to execute it:

```bash
$LINKER /absolute/path/to/binary
```

✅ **Note:** This method won't work for **statically linked binaries** (binaries without external dependencies).

---

### **Option 2: Use `sh` for Scripts**

If you're trying to execute a shell script, simply use `sh` to run it:

```bash
sh /path/to/script
```

This bypasses the need for execute permissions since the script is interpreted by the shell.

---

### **Option 3: Use Shizuku for Full Shell Access (Recommended)**

If you have **Shizuku** installed, you can gain shell access to `/data/local/tmp`, which has executable permissions. This is the easiest way to run binaries without restrictions.

## Community

> [!TIP]
> Join the aTerm community to stay updated and engage with other users:
> - [Telegram](https://t.me/reTerminal)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## License

This project is licensed under the terms specified in the [LICENSE](LICENSE) file.

## Acknowledgments

- Built on [Termux TerminalView](https://github.com/termux/termux-app) - A robust terminal emulation library
- Inspired by [Jackpal Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)

## Found this app useful? ❤️

Support it by giving a star ⭐ <br>
Also, **__[follow](https://github.com/qaliblog)__** for more updates!

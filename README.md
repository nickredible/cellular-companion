This is a fork of [Cellular Companion](https://github.com/XuanHanTan/cellular-companion) by Xuan Han Tan, which is no longer updated. It has been modified since October 2026 to work with current versions of Android; see the commit history for the changes. Builds of this fork are not available on Google Play.

# About

Cellular Companion is the Android half of [Cellular](https://github.com/nickredible/cellular), which lets your Mac connect to your phone's mobile hotspot as if it had cellular data. The app runs in the background, turns your hotspot on and off when your Mac asks, and shares your phone's signal strength, network type and battery level with your Mac over Bluetooth.

# Download

Download the latest APK from the [releases of this repository](https://github.com/nickredible/cellular-companion/releases) on your phone and open it. Android will ask you to allow your browser or file manager to install apps the first time. You also need [Cellular](https://github.com/nickredible/cellular/releases) on your Mac. Cellular Companion needs Android 8 or later.

Cellular Companion checks for new releases once a day and lets you know when one is available, but you have to download and install it yourself. You can turn this off on the About screen.

## Turning on the hotspot on Android 16 and later

Android 16 stopped letting apps turn on the hotspot by themselves. To let Cellular Companion do it, install and start [Shizuku](https://shizuku.rikka.app/), then tap **Turn on hotspot automatically** on Cellular Companion's home screen and allow it in Shizuku. Shizuku has to be started again after your phone restarts.

Without Shizuku, Cellular Companion asks you to turn on the hotspot whenever your Mac needs it, and tells your Mac to connect as soon as you do.

# Building from source

You need JDK 17 or later and the Android SDK.

```sh
./gradlew assembleDebug
```

# Code documentation

View code documentation [here](DEVDOCS.md).

# Credits

Cellular Companion was designed and built by Xuan Han Tan. This fork is by [nickredible](https://github.com/nickredible).

# License

Copyright © 2023 - 2024 Xuan Han Tan<br>
Copyright © 2026 nickredible

Cellular Companion is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3 of the License. It is distributed in the hope that it will be useful, but without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose. See [LICENSE](LICENSE) for details.

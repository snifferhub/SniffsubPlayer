# Application icons

The supplied gold and charcoal airplane artwork is used for the light and dark
launcher icons. External black margins and baked-in rounded tile rims were
removed. Background colors extend to the square edges, leaving the airplane
intact; platform launchers apply their own masks.

## iOS

`iosApp/SniffSubPlayer/Assets.xcassets/AppIcon.appiconset` contains opaque RGB
1024×1024 masters. `AppIcon.png` is the default/light appearance and
`dark_icon.png` is the dark luminosity appearance in `Contents.json`.
Dark home-screen icons require a supported iOS version and the user's home-screen
appearance selection (Automatic follows the system appearance).

## Android

The manifest uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
Default and `night` resources provide gold and charcoal icons respectively.
Legacy icons use 48dp at mdpi through xxxhdpi (48, 72, 96, 144, 192 pixels).
Adaptive layers use 108dp (108, 162, 216, 324, 432 pixels), with the master
occupying the central 72dp and edge colors extended through the overscan area.
This preserves the airplane under circle, squircle, and other launcher masks.

Regenerate Android resources after changing the iOS masters:

```sh
python -m pip install Pillow
python tools/generate_android_icons.py
```

Night resources follow Android's resource configuration. Actual launcher refresh
behavior depends on the launcher and its icon cache. This does not supply a
separate Android wallpaper-tinted monochrome icon or force a launcher's theme.

Resource dimensions, opacity, XML/JSON references, and square/circle previews
were checked. No Android build, device test, or iOS build was performed here.

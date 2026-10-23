# Launcher icon appearances

The default resources retain the pink launcher icon. The `night` resource
variants supply the dark icon when resources are loaded with night mode enabled.
Both legacy square/round icons and adaptive icons are provided, in all five
existing density buckets. The adaptive foreground keeps the existing 66 dp
artwork inside a 108 dp canvas.

The `mipmap-night-anydpi-v26` definitions are intentional: the night-qualified
legacy PNGs must not take precedence over the default adaptive icon XML.

Launcher refresh and resource configuration are controlled by the launcher.
Some launchers cache icons or do not load application icons with night mode,
so these resources cannot guarantee immediate home-screen switching on every
device. This is a full-color night variant, not a Material You monochrome icon.

Device verification: install a build, select light and dark system appearance,
and inspect the home screen and app drawer in both modes. Verify round and
squircle masks in the target launcher and confirm opening the app still works.

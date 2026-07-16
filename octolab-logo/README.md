# OctoLab — Logo & App Icon Kit

The **Fox-Fan** mark: a GitLab-homage built from faceted tiles + two ear tiles.
All geometry is **solid fills, no gradients** — so it converts cleanly to Android
vector drawables and stays crisp at any size.

## Color tokens

| Role            | Hex       | Used for                    |
|-----------------|-----------|-----------------------------|
| Brand Red       | `#E24329` | Ears                        |
| Orange-Red      | `#FC6D26` | Body, left facet            |
| Orange          | `#FCA326` | Body, right facet           |
| Surface (light) | `#FFFFFF` | App-icon background (light) |
| Surface (dark)  | `#1A1A1A` | App-icon background (dark)  |

```xml
<!-- res/values/colors.xml -->
<color name="octolab_red">#E24329</color>
<color name="octolab_orange_red">#FC6D26</color>
<color name="octolab_orange">#FCA326</color>
```

## Files

```
octolab-mark.svg            Full-color logo, transparent background
octolab-mark-mono.svg       White silhouette (recolor via fill / currentColor)

android/
  drawable/
    ic_launcher_foreground.xml   Adaptive-icon foreground (the mark, in safe zone)
    ic_launcher_background.xml   Adaptive-icon background (solid #FFFFFF — editable)
    ic_launcher_monochrome.xml   Themed-icon layer for Android 13+ (system-tinted)
  mipmap-anydpi-v26/
    ic_launcher.xml              Adaptive-icon descriptor (wires the 3 layers)
```

## Android install

1. Copy `android/drawable/*.xml` → `app/src/main/res/drawable/`
2. Copy `android/mipmap-anydpi-v26/ic_launcher.xml` →
   `app/src/main/res/mipmap-anydpi-v26/` and duplicate it as `ic_launcher_round.xml`.
3. Make sure `AndroidManifest.xml` points at it:
   `android:icon="@mipmap/ic_launcher"` and
   `android:roundIcon="@mipmap/ic_launcher_round"`.
4. (Optional) Generate legacy PNG `mipmap-*` densities for pre-API-26 devices —
   in Android Studio: right-click `res` → New → Image Asset → use
   `ic_launcher_foreground.xml` as the foreground and `#FFFFFF` as the background.

### Notes
- Foreground is scaled to the 66dp safe zone, centered — survives circle/squircle masks.
- Want a dark icon background? Change `fillColor` in `ic_launcher_background.xml`
  to `#1A1A1A`.
- The mark uses no gradients, so it renders identically in Android, web, and print.

## Web / other use
- Use `octolab-mark.svg` anywhere (favicons, README badges, splash).
- For a tintable single-color mark, use `octolab-mark-mono.svg` and set `fill`.

## Source viewBox
Both SVGs use `viewBox="0 0 120 120"` with the mark centered (~16–104 horizontal,
20–100 vertical), leaving icon-safe padding.

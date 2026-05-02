# Play Store assets

Assets for the Google Play Console store listing. Not used by the app build —
upload these manually through the Play Console (or via fastlane `supply` in a
follow-up).

| File | Spec | Notes |
| --- | --- | --- |
| `icon.png` | 512×512, 32-bit PNG with alpha | App icon |
| `feature_graphic.png` | 1024×500, 24-bit PNG (no alpha) | Feature graphic |
| `screenshots/*.jpg` | Phone portrait | Same files as `fastlane/metadata/android/en-US/images/phoneScreenshots/` |

Source SVGs are checked in (`icon.svg`, `feature_graphic.svg`) so the assets
can be regenerated.

## Regenerate

```bash
# Icon (ImageMagick is fine; no text)
magick -background none -density 600 icon.svg -resize 512x512 PNG32:icon.png

# Feature graphic (use Inkscape; it renders the gradient and text correctly)
inkscape --export-type=png --export-filename="$PWD/feature_graphic.png" \
  --export-width=1024 --export-height=500 "$PWD/feature_graphic.svg"
magick feature_graphic.png -background "#1E2A78" -alpha remove -alpha off \
  PNG24:feature_graphic.png
```

# Store metadata (F-Droid / Triple-T layout)

F-Droid reads the app listing straight from this directory. Nothing here affects
the build — it is text and images only.

```
metadata/android/<locale>/
  title.txt              app name              max 50 chars
  short_description.txt  one-line summary      max 80 chars
  full_description.txt   the listing body      max 4000 chars
  images/
    icon.png             512x512
    featureGraphic.png   1024x500, banner above the description
    phoneScreenshots/    01-*.png … 08-*.png, shown in filename order
  changelogs/<versionCode>.txt                 max 500 chars
```

Locales present: `en-US`, `zh-CN`. Keep both in sync when either changes.

## Rules that are easy to get wrong

- **Do not hard-wrap paragraphs.** F-Droid turns every newline into `<br>`, so a
  paragraph wrapped at 80 columns renders as a column of short broken lines on a
  phone. Write each paragraph as one long line; separate paragraphs with a blank
  line.
- **Only this HTML subset is rendered**: `b, big, blockquote, br, cite, em, i,
  li, ol, small, strike, strong, sub, sup, tt, u, ul` (plus `a href`).
  `img`, `video`, `iframe` and `script` are stripped. Markdown is *not* rendered
  — use `<ul><li>` for lists, not `-`.
- **Every release needs its own changelog file**, named after the **versionCode**
  (not the version name): bumping to 282 means adding
  `changelogs/282.txt` in both locales. A missing file is not an error, it just
  means that version shows no changelog.
- **The F-Droid build is the `libre` flavor — it has no RAR support.** The
  descriptions say so. If the flavors ever diverge further, update that paragraph.

## Screenshots

Eight, in `images/phoneScreenshots/`, and eight is a decision rather than a
budget: the listing is a horizontal strip, nobody swipes past the first handful,
and every extra shot dilutes the ones that actually argue for the app. The order
is the argument — what it is, then what nothing else does:

```
01-one-tree            the tree itself, one source expanded in place
02-dual-pane           landscape, both panes (the headline claim)
03-restic-repository   a restic backup browsed on device
04-media-server        a Jellyfin/Emby library as a filesystem
05-directory-compare   two trees aligned row by row
06-space-map           the treemap
07-wifi-sharing        Twig serving its own files
08-terminal            htop in the built-in terminal
```

Rules that are easy to get wrong:

- **PNG or JPEG only** — the extension must be `png`, `jpg` or `jpeg`. A `.webp`
  is not rendered badly, it is ignored, and the listing silently shows nothing.
- **The filename is the sort key.** F-Droid displays them in sorted order, so the
  numeric prefix is what fixes the sequence; zero-pad it or 10 sorts before 2.
- **No `+` in a name.** It decodes as a space in form-encoded contexts — the same
  trap `docs/lessons/s3.md` records for object keys.
- **F-Droid strips metadata and may recompress**, so squeezing the last KB out
  here buys little. Capture, crop, commit.
- Compression, copying, favourites, context menus — every file manager has those.
  They belong in the README, not in the eight.

**Images live in `en-US` only, and that is deliberate.** F-Droid falls back to
en-US for graphics as well as text, so anything placed there is shown to every
visitor whatever their language — duplicating it into `zh-CN` costs 2.1 MB in
the repository and changes nothing on screen. Localised images are worth adding
only when they are actually localised: if these are ever recaptured with the app
in Chinese, `zh-CN/images/` can be created then and will take precedence for
Chinese users on its own.

The text is the opposite case. `title` / `short_description` / `full_description`
/ `changelogs` in `zh-CN` are a few KB and are the whole listing a Chinese user
reads, so both locales carry them and both must be updated together.

The icon and feature graphic are generated from `app/src/main/res/drawable/ic_launcher.xml`
and can be regenerated with `rsvg-convert`; see the commit that added them.
